// SPDX-License-Identifier: GPL-3.0-or-later
// OpenRaster save/load + PNG export (nested groups since M4).
// Premultiplied RGBA8 <-> straight PNG conversion; zlib for zip via minizip.
#include "openclip/ora.h"

#include "openclip/canvas.h"
#include "openclip/filters.h"

#include <cctype>
#include <cstdio>
#include <map>
#include <sstream>

#include "minizip.h"

#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"
#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"

namespace openclip {
namespace {

// --- tiny XML helpers (stack.xml is written by us; parser is lenient) ---

std::string xmlEscape(const std::string& s) {
    std::string o;
    for (char c : s) {
        switch (c) {
            case '&': o += "&amp;"; break;
            case '<': o += "&lt;"; break;
            case '>': o += "&gt;"; break;
            case '"': o += "&quot;"; break;
            default: o += c;
        }
    }
    return o;
}

std::string xmlUnescape(const std::string& s) {
    std::string o;
    for (std::size_t i = 0; i < s.size();) {
        if (s[i] == '&') {
            if (s.compare(i, 5, "&amp;") == 0) { o += '&'; i += 5; } else if (s.compare(i, 4, "&lt;") == 0) { o += '<'; i += 4; } else if (s.compare(i, 4, "&gt;") == 0) { o += '>'; i += 4; } else if (s.compare(i, 6, "&quot;") == 0) { o += '"'; i += 6; } else { o += s[i++]; }
        } else {
            o += s[i++];
        }
    }
    return o;
}

bool tagAttr(const std::string& xml, std::size_t tagStart, const char* attr,
             std::string& value) {
    const std::string a = std::string(attr) + "=";
    std::size_t p = xml.find(a, tagStart);
    const std::size_t end = xml.find('>', tagStart);
    if (p == std::string::npos || (end != std::string::npos && p > end))
        return false;
    p += a.size();
    while (p < xml.size() && std::isspace((unsigned char)xml[p])) ++p;
    if (p >= xml.size() || (xml[p] != '"' && xml[p] != '\'')) return false;
    const char q = xml[p++];
    const std::size_t e = xml.find(q, p);
    if (e == std::string::npos || (end != std::string::npos && e > end))
        return false;
    value = xmlUnescape(xml.substr(p, e - p));
    return true;
}

// Encode premultiplied surface to straight-RGBA PNG bytes in memory.
bool encodePng(const Surface& s, std::vector<std::uint8_t>& png) {
    const int w = s.width(), h = s.height();
    if (w <= 0 || h <= 0) return false;
    std::vector<std::uint8_t> straight((std::size_t)w * h * 4, 0);
    const auto& px = s.bytes();
    for (std::size_t i = 0; i < (std::size_t)w * h; ++i) {
        const std::uint8_t a = px[i * 4 + 3];
        straight[i * 4 + 3] = a;
        if (a == 0) continue;
        if (a == 255) {
            straight[i * 4 + 0] = px[i * 4 + 0];
            straight[i * 4 + 1] = px[i * 4 + 1];
            straight[i * 4 + 2] = px[i * 4 + 2];
        } else {
            for (int c = 0; c < 3; ++c) {
                int v = (px[i * 4 + c] * 255 + a / 2) / a;
                straight[i * 4 + c] = (std::uint8_t)(v > 255 ? 255 : v);
            }
        }
    }
    int len = 0;
    unsigned char* data =
        stbi_write_png_to_mem(straight.data(), 0, w, h, 4, &len);
    if (!data || len <= 0) return false;
    png.assign(data, data + len);
    STBIW_FREE(data);
    return true;
}

bool decodePng(const std::vector<std::uint8_t>& png, Surface& s) {
    int w = 0, h = 0, comp = 0;
    unsigned char* data =
        stbi_load_from_memory(png.data(), (int)png.size(), &w, &h, &comp, 4);
    if (!data || w <= 0 || h <= 0) {
        if (data) stbi_image_free(data);
        return false;
    }
    s.resize(w, h);
    auto& px = s.bytes();
    for (std::size_t i = 0; i < (std::size_t)w * h; ++i) {
        const std::uint8_t a = data[i * 4 + 3];
        px[i * 4 + 3] = a;
        if (a == 0) {
            px[i * 4 + 0] = px[i * 4 + 1] = px[i * 4 + 2] = 0;
        } else if (a == 255) {
            px[i * 4 + 0] = data[i * 4 + 0];
            px[i * 4 + 1] = data[i * 4 + 1];
            px[i * 4 + 2] = data[i * 4 + 2];
        } else {
            for (int c = 0; c < 3; ++c) {
                int v = (data[i * 4 + c] * a + 127) / 255;
                px[i * 4 + c] = (std::uint8_t)(v > a ? a : v);
            }
        }
    }
    stbi_image_free(data);
    return true;
}

// --- save: recursive stack writer ---

void writeLayerList(const std::vector<Layer>& layers, std::ostringstream& xml,
                    std::vector<std::pair<std::string, std::vector<std::uint8_t>>>&
                        entries,
                    int& idx, int indent, std::size_t depth,
                    std::string& error, bool& ok);

// V+1: text fiche attrs (x-openclip-*, ARCHITECTURE.md "V+1
// additions"). Readers that ignore unknown attrs degrade to the visible
// pixels, like the M8 adjust attrs above.
void writeTextAttrs(const Layer& l, std::ostringstream& xml) {
    if (!l.hasText()) return;
    xml << " x-openclip-text=\"" << xmlEscape(l.textContent) << "\""
        << " x-openclip-font=\"" << xmlEscape(l.textFont) << "\""
        << " x-openclip-size=\"" << l.textSizePx << "\""
        << " x-openclip-style=\"" << xmlEscape(l.textStyle) << "\""
        << " x-openclip-align=\"" << xmlEscape(l.textAlign) << "\""
        << " x-openclip-leading=\"" << l.textLeading << "\""
        << " x-openclip-tracking=\"" << l.textTracking << "\""
        << " x-openclip-color=\"" << xmlEscape(l.textColor) << "\""
        << " x-openclip-box=\"" << xmlEscape(l.textBox) << "\"";
}

void writeLayerOne(const Layer& l, std::ostringstream& xml,
                   std::vector<std::pair<std::string, std::vector<std::uint8_t>>>&
                       entries,
                   int& idx, int indent, std::size_t depth, std::string& error,
                   bool& ok) {
    if (!ok) return;
    if (depth > 16) {
        error = "ora: stack nesting limit reached";
        ok = false;
        return;
    }
    const std::string pad(indent, ' ');
    if (l.isGroup) {
        xml << pad << "<stack name=\"" << xmlEscape(l.name) << "\" opacity=\""
            << l.opacity << "\" visibility=\""
            << (l.visible ? "visible" : "hidden") << "\" composite-op=\""
            << blendToName(l.blend) << "\" isolation=\""
            << (l.passThrough ? "auto" : "isolated") << "\"";
        if (!l.adjustType.empty()) {
            xml << " adjust-type=\"" << xmlEscape(l.adjustType) << "\""
                << " adjust-amount=\"" << l.adjustAmount << "\""
                << " adjust-stops=\"" << xmlEscape(l.adjustStops) << "\"";
        }
        writeTextAttrs(l, xml);
        xml << ">\n";
        writeLayerList(l.children, xml, entries, idx, indent + 1,
                       depth + 1, error, ok);
        xml << pad << "</stack>\n";
        return;
    }
    char src[64];
    std::snprintf(src, sizeof src, "data/layer%03d.png", idx);
    xml << pad << "<layer name=\"" << xmlEscape(l.name) << "\" src=\"" << src
        << "\" opacity=\"" << l.opacity << "\" visibility=\""
        << (l.visible ? "visible" : "hidden") << "\" composite-op=\""
        << blendToName(l.blend) << "\"";
    // M8: adjustment descriptor persists as attrs (round trips through
    // tagAttr/xmlUnescape on load).
    if (!l.adjustType.empty()) {
        xml << " adjust-type=\"" << xmlEscape(l.adjustType) << "\""
            << " adjust-amount=\"" << l.adjustAmount << "\""
            << " adjust-stops=\"" << xmlEscape(l.adjustStops) << "\"";
    }
    writeTextAttrs(l, xml);
    xml << "/>\n";
    std::vector<std::uint8_t> png;
    if (!encodePng(l.surface, png)) {
        error = "ora: PNG encode failed for layer " + l.name;
        ok = false;
        return;
    }
    entries.push_back({src, std::move(png)});
    ++idx;
}

void writeLayerList(const std::vector<Layer>& layers, std::ostringstream& xml,
                    std::vector<std::pair<std::string, std::vector<std::uint8_t>>>&
                        entries,
                    int& idx, int indent, std::size_t depth,
                    std::string& error, bool& ok) {
    if (!ok) return;
    for (const auto& l : layers) writeLayerOne(l, xml, entries, idx, indent,
                                               depth, error, ok);
}

// --- load: recursive stack reader ---

bool attrFloat(const std::string& def, float& out) {
    try {
        out = std::stof(def);
    } catch (...) {
        return false;
    }
    return true;
}

// M8: read the adjustment descriptor attrs into l when present AND the
// type is known; unknown or missing = layer loads as normal (contract).
void applyAdjustAttrs(const std::string& xml, std::size_t tagStart,
                      Layer& l) {
    std::string t, a, s;
    if (!tagAttr(xml, tagStart, "adjust-type", t) || t.empty()) return;
    if (!openclip::isKnownAdjustType(t)) return;
    l.adjustType = t;
    if (tagAttr(xml, tagStart, "adjust-amount", a)) {
        try {
            l.adjustAmount = std::stod(a);
        } catch (...) {
            l.adjustAmount = 0.0;
        }
        if (l.adjustAmount < -1.0) l.adjustAmount = -1.0;
        if (l.adjustAmount > 1.0) l.adjustAmount = 1.0;
    }
    tagAttr(xml, tagStart, "adjust-stops", s);
    l.adjustStops = s;
}

// V+1: read the text fiche attrs into l when x-openclip-text is
// present AND non-empty; missing/empty = normal layer (identity, the
// M8 contract). Values are capped (DoS hygiene on foreign files) and
// numerics fall back to defaults.
void applyTextAttrs(const std::string& xml, std::size_t tagStart,
                    Layer& l) {
    std::string t;
    if (!tagAttr(xml, tagStart, "x-openclip-text", t) || t.empty()) return;
    l.textContent = t.substr(0, 8192);
    auto capped = [](const std::string& s) { return s.substr(0, 128); };
    std::string v;
    l.textFont = tagAttr(xml, tagStart, "x-openclip-font", v) ? capped(v)
                                                             : "sans-serif";
    if (tagAttr(xml, tagStart, "x-openclip-size", v)) {
        try {
            l.textSizePx = std::stoi(v);
        } catch (...) {
            l.textSizePx = 0;
        }
        if (l.textSizePx < 1 || l.textSizePx > 4096) l.textSizePx = 0;
    }
    l.textStyle = tagAttr(xml, tagStart, "x-openclip-style", v) ? capped(v)
                                                                : "regular";
    l.textAlign = tagAttr(xml, tagStart, "x-openclip-align", v) ? capped(v)
                                                                : "left";
    if (tagAttr(xml, tagStart, "x-openclip-leading", v)) {
        try {
            l.textLeading = std::stod(v);
        } catch (...) {
            l.textLeading = 1.0;
        }
        if (!(l.textLeading >= 0.5 && l.textLeading <= 4.0))
            l.textLeading = 1.0;
    }
    if (tagAttr(xml, tagStart, "x-openclip-tracking", v)) {
        try {
            l.textTracking = std::stod(v);
        } catch (...) {
            l.textTracking = 0.0;
        }
        if (!(l.textTracking >= -64.0 && l.textTracking <= 64.0))
            l.textTracking = 0.0;
    }
    l.textColor = tagAttr(xml, tagStart, "x-openclip-color", v) ? capped(v)
                                                                : "#141414";
    l.textBox = tagAttr(xml, tagStart, "x-openclip-box", v) ? capped(v) : "";
}

bool loadLayerList(const std::string& xml, std::size_t start, std::size_t end,
                   std::map<std::string, std::vector<std::uint8_t>>& entries,
                   Canvas& tmp, std::size_t depth, std::vector<Layer>& out,
                   std::string& error);

std::string blendVectorValue(const std::string& raw) {
    // Accept "svg:multiply" / "multiply" / "linear_dodge" style names.
    const std::size_t c = raw.find(':');
    return c == std::string::npos ? raw : raw.substr(c + 1);
}

bool loadLayerList(const std::string& xml, std::size_t start, std::size_t end,
                   std::map<std::string, std::vector<std::uint8_t>>& entries,
                   Canvas& tmp, std::size_t depth, std::vector<Layer>& out,
                   std::string& error) {
    if (depth > 16) {
        error = "ora: stack nesting limit reached";
        return false;
    }
    std::size_t pos = start;
    while (pos < end) {
        const std::size_t lOpen = xml.find('<', pos);
        if (lOpen == std::string::npos || lOpen >= end) break;
        if (xml.compare(lOpen, 6, "<layer") == 0 &&
            !(xml[lOpen + 6] == 's' || xml.compare(lOpen, 7, "<layers") == 0))
        {
            std::string name = "layer", src, op = "1", vis = "visible",
                         comp = "svg:normal";
            tagAttr(xml, lOpen, "name", name);
            const bool hasSrc = tagAttr(xml, lOpen, "src", src);
            if (hasSrc) {
                tagAttr(xml, lOpen, "opacity", op);
                tagAttr(xml, lOpen, "visibility", vis);
                tagAttr(xml, lOpen, "composite-op", comp);
                auto eit = entries.find(src);
                if (eit == entries.end()) {
                    error = "ora: missing layer data " + src;
                    return false;
                }
                Surface s;
                if (!decodePng(eit->second, s)) {
                    error = "ora: PNG decode failed for " + src;
                    return false;
                }
                Layer l;
                l.name = name;
                l.opacity = 1.0f;
                attrFloat(op, l.opacity);
                if (l.opacity < 0) l.opacity = 0;
                if (l.opacity > 1) l.opacity = 1;
                l.visible = (vis != "hidden" && vis != "false" && vis != "0");
                l.blend =
                    blendFromName(blendVectorValue(comp).c_str());
                applyAdjustAttrs(xml, lOpen, l);
                applyTextAttrs(xml, lOpen, l);
                l.surface.resize(tmp.width(), tmp.height());
                if (s.width() == tmp.width() && s.height() == tmp.height()) {
                    l.surface.bytes().swap(s.bytes());
                } else {
                    for (int y = 0; y < tmp.height() && y < s.height(); ++y)
                        for (int x = 0; x < tmp.width() && x < s.width(); ++x)
                            l.surface.set(x, y, s.get(x, y));
                }
                out.push_back(std::move(l));
            }
            const std::size_t close = xml.find('>', lOpen);
            pos = close == std::string::npos ? lOpen + 6 : close + 1;
            continue;
        }
        if (xml.compare(lOpen, 6, "<layer") == 0 ||
            xml.compare(lOpen, 7, "<layers") == 0)
        {
            // <layers> container or stray <layer ...> without src: skip tag.
            const std::size_t close = xml.find('>', lOpen);
            pos = close == std::string::npos ? lOpen + 7 : close + 1;
            continue;
        }
        if (xml.compare(lOpen, 6, "<stack") == 0) {
            std::string name = "group", op = "1", vis = "visible",
                         comp = "svg:normal", iso = "auto";
            tagAttr(xml, lOpen, "name", name);
            tagAttr(xml, lOpen, "opacity", op);
            tagAttr(xml, lOpen, "visibility", vis);
            tagAttr(xml, lOpen, "composite-op", comp);
            tagAttr(xml, lOpen, "isolation", iso);
            const std::size_t close = xml.find('>', lOpen);
            if (close == std::string::npos) {
                error = "ora: malformed <stack>";
                return false;
            }
            // Self-closing <stack .../> ?
            if (xml[close - 1] == '/') {
                pos = close + 1;
                Layer g;
                g.isGroup = true;
                g.name = name;
                attrFloat(op, g.opacity);
                if (g.opacity < 0) g.opacity = 0;
                if (g.opacity > 1) g.opacity = 1;
                g.visible =
                    (vis != "hidden" && vis != "false" && vis != "0");
                g.blend =
                    blendFromName(blendVectorValue(comp).c_str());
                g.passThrough = (iso != "isolated");
                applyAdjustAttrs(xml, lOpen, g);
                applyTextAttrs(xml, lOpen, g);
                out.push_back(std::move(g));
                continue;
            }
            const std::string closingTag = "</stack>";
            // Match against the FIRST following </stack> with a scan that
            // counts nested opens, bounded.
            std::size_t scan = close + 1, gEnd = std::string::npos;
            std::size_t level = 0;
            while (scan < xml.size()) {
                const std::size_t next = xml.find('<', scan);
                if (next == std::string::npos) break;
                if (xml.compare(next, 6, "<stack") == 0 &&
                    xml[next + 6] != '>') {
                    ++level;
                    scan = next + 6;
                    continue;
                }
                if (xml.compare(next, closingTag.size(), closingTag) == 0) {
                    if (level == 0) {
                        gEnd = next;
                        break;
                    }
                    --level;
                    scan = next + closingTag.size();
                    continue;
                }
                scan = next + 1;
            }
            if (gEnd == std::string::npos) {
                error = "ora: unclosed <stack>";
                return false;
            }
            Layer g;
            g.isGroup = true;
            g.name = name;
            attrFloat(op, g.opacity);
            if (g.opacity < 0) g.opacity = 0;
            if (g.opacity > 1) g.opacity = 1;
            g.visible = (vis != "hidden" && vis != "false" && vis != "0");
            g.blend = blendFromName(blendVectorValue(comp).c_str());
            g.passThrough = (iso != "isolated");
            applyAdjustAttrs(xml, lOpen, g);
                applyTextAttrs(xml, lOpen, g);
            if (!loadLayerList(xml, close + 1, gEnd, entries, tmp, depth + 1,
                               g.children, error))
                return false;
            out.push_back(std::move(g));
            pos = gEnd + closingTag.size();
            continue;
        }
        // Any other tag (<image>, <extensions>, comments...): skip it.
        const std::size_t close = xml.find('>', lOpen);
        pos = close == std::string::npos ? xml.size() : close + 1;
    }
    return true;
}

}  // namespace

bool saveOra(const Canvas& c, const std::string& path, std::string& error) {
    std::vector<std::pair<std::string, std::vector<std::uint8_t>>> entries;
    const std::string mt = "image/openraster";
    entries.push_back({"mimetype", {mt.begin(), mt.end()}});
    std::ostringstream xml;
    xml << "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<image w=\""
        << c.width() << "\" h=\"" << c.height() << "\">\n<stack>\n";
    int idx = 0;
    Surface thumb;
    c.composite(thumb);
    // View the canvas top-level as an index list (Canvas API only exposes
    // layer(i)); the recursion over group children is vector-based.
    std::vector<Layer> top;
    top.reserve(c.layerCount());
    for (std::size_t i = 0; i < c.layerCount(); ++i) top.push_back(c.layer(i));
    bool ok = true;
    writeLayerList(top, xml, entries, idx, 1, 0, error, ok);
    if (!ok) return false;
    xml << "</stack>\n</image>\n";
    const std::string xs = xml.str();
    entries.push_back({"stack.xml", {xs.begin(), xs.end()}});
    std::vector<std::uint8_t> merged;
    if (!encodePng(thumb, merged)) {
        error = "ora: PNG encode failed for mergedimage";
        return false;
    }
    entries.push_back({"mergedimage.png", std::move(merged)});
    return zip::writeZip(path, entries, true, error);
}

bool loadOra(Canvas& c, const std::string& path, std::string& error) {
    std::map<std::string, std::vector<std::uint8_t>> entries;
    if (!zip::readZip(path, entries, error)) return false;
    auto it = entries.find("stack.xml");
    if (it == entries.end()) {
        error = "ora: stack.xml missing";
        return false;
    }
    const std::string xml(it->second.begin(), it->second.end());
    std::size_t img = xml.find("<image");
    if (img == std::string::npos) {
        error = "ora: <image> missing";
        return false;
    }
    std::string ws, hs;
    if (!tagAttr(xml, img, "w", ws) || !tagAttr(xml, img, "h", hs)) {
        error = "ora: image size missing";
        return false;
    }
    int w = 0, h = 0;
    try {
        w = std::stoi(ws);
        h = std::stoi(hs);
    } catch (...) {
        error = "ora: bad image size";
        return false;
    }
    if (w <= 0 || h <= 0 || w > 16384 || h > 16384) {
        error = "ora: image size out of range";
        return false;
    }
    Canvas tmp(w, h);
    std::vector<Layer> roots;
    // The root <stack> is the document container, not a group: start the
    // scan strictly inside it and stop at its last closing tag.
    const std::size_t stackOpen = xml.find("<stack", img);
    if (stackOpen == std::string::npos) {
        error = "ora: root <stack> missing";
        return false;
    }
    const std::size_t rootOpenEnd = xml.find('>', stackOpen);
    const std::size_t rootClose = xml.rfind("</stack>");
    if (rootOpenEnd == std::string::npos || rootClose == std::string::npos ||
        rootClose <= rootOpenEnd) {
        error = "ora: malformed root stack";
        return false;
    }
    if (!loadLayerList(xml, rootOpenEnd + 1, rootClose, entries, tmp, 0,
                       roots, error))
        return false;
    if (roots.empty()) {
        error = "ora: no layers or groups found";
        return false;
    }
    for (auto& l : roots) tmp.adoptLayer(std::move(l));
    c = std::move(tmp);
    c.setActiveLayer(0);
    return true;
}

bool exportPng(const Canvas& c, const std::string& path, std::string& error) {
    Surface out;
    c.composite(out);
    const int w = out.width(), h = out.height();
    if (w <= 0 || h <= 0) {
        error = "png: canvas empty";
        return false;
    }
    std::vector<std::uint8_t> png;
    if (!encodePng(out, png)) {
        error = "png: encode failed";
        return false;
    }
    std::FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) {
        error = "png: cannot write " + path;
        return false;
    }
    const std::size_t wrote = std::fwrite(png.data(), 1, png.size(), f);
    std::fclose(f);
    if (wrote != png.size()) {
        error = "png: short write";
        return false;
    }
    return true;
}

bool decodePngFile(const std::string& path, Surface& out,
                   std::string& error) {
    int w = 0, h = 0, comp = 0;
    unsigned char* data = stbi_load(path.c_str(), &w, &h, &comp, 4);
    if (!data || w <= 0 || h <= 0) {
        if (data) stbi_image_free(data);
        error = "png: decode failed for " + path;
        return false;
    }
    out.resize(w, h);
    auto& px = out.bytes();
    for (std::size_t i = 0; i < static_cast<std::size_t>(w) * h; ++i) {
        const std::uint8_t a = data[i * 4 + 3];
        px[i * 4 + 3] = a;
        if (a == 0) {
            px[i * 4 + 0] = px[i * 4 + 1] = px[i * 4 + 2] = 0;
        } else if (a == 255) {
            px[i * 4 + 0] = data[i * 4 + 0];
            px[i * 4 + 1] = data[i * 4 + 1];
            px[i * 4 + 2] = data[i * 4 + 2];
        } else {
            for (int k = 0; k < 3; ++k) {
                int v = (data[i * 4 + k] * a + 127) / 255;
                px[i * 4 + k] = static_cast<std::uint8_t>(v > a ? a : v);
            }
        }
    }
    stbi_image_free(data);
    return true;
}

bool loadPngInto(Canvas& c, const std::string& path, std::string& error) {
    Surface s;
    if (!decodePngFile(path, s, error)) return false;
    Canvas tmp(s.width(), s.height());
    Layer l;
    l.name = "Imported PNG";
    l.surface.resize(s.width(), s.height());
    l.surface.bytes().swap(s.bytes());
    tmp.adoptLayer(std::move(l));
    c = std::move(tmp);
    c.setActiveLayer(0);
    return true;
}

}  // namespace openclip