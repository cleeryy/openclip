// SPDX-License-Identifier: GPL-3.0-or-later
// OpenRaster save/load + PNG export (nested groups since M4).
// Premultiplied RGBA8 <-> straight PNG conversion; zlib for zip via minizip.
#include "openclip/ora.h"

#include "openclip/canvas.h"
#include "openclip/filters.h"
#include "openclip/vector.h"

#include <cctype>
#include <cstdio>
#include <cstdlib>
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

std::string blendVectorValue(const std::string& raw) {
    // Accept "svg:multiply" / "multiply" / "linear_dodge" style names.
    const std::size_t c = raw.find(':');
    return c == std::string::npos ? raw : raw.substr(c + 1);
}

// A2: composite-op load. A known name maps to its mode; an UNKNOWN
// name (a reader that does not know our addition, or a third-party
// extension) degrades to Normal for compositing but its exact spelling
// is kept in the opaque meta fiche (x-openclip-blend), so a re-save
// writes it back verbatim: unknown modes never corrupt, never get lost
// (ARCHITECTURE.md "A2 additions").
void applyBlendName(const std::string& raw, Layer& l) {
    const std::string v = blendVectorValue(raw);
    l.blend = blendFromName(v.c_str());
    if (l.blend == BlendMode::Normal && !v.empty() && v != "normal") {
        l.meta["x-openclip-blend"] = raw;
    }
}

// A2: composite-op write. A preserved unknown name wins over the
// degraded svg:normal so round trips are lossless.
std::string compositeOpFor(const Layer& l) {
    if (l.blend == BlendMode::Normal) {
        const auto it = l.meta.find("x-openclip-blend");
        if (it != l.meta.end() && !it->second.empty()) return it->second;
    }
    return blendToName(l.blend);
}

// --- save: recursive stack writer ---

void writeLayerList(std::vector<Layer>& layers, std::ostringstream& xml,
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
    // T2 extensions (ARCHITECTURE.md §T2 §1–§2, opaque): written only
    // when set (absent = global style / horizontal / no hyphen).
    if (!l.textRuns.empty())
        xml << " x-openclip-text-runs=\"" << xmlEscape(l.textRuns) << "\"";
    if (!l.textOutline.empty())
        xml << " x-openclip-text-outline=\""
            << xmlEscape(l.textOutline) << "\"";
    if (!l.textFontFile.empty())
        xml << " x-openclip-text-fontfile=\""
            << xmlEscape(l.textFontFile) << "\"";
    if (!l.textDir.empty())
        xml << " x-openclip-text-dir=\"" << xmlEscape(l.textDir) << "\"";
    if (!l.textHyphen.empty())
        xml << " x-openclip-text-hyphen=\""
            << xmlEscape(l.textHyphen) << "\"";
    // B2 §1: transform tuple (absent = identity).
    if (!l.textXform.empty())
        xml << " x-openclip-text-xform=\""
            << xmlEscape(l.textXform) << "\"";
}

// T2 bubble fiche attrs (ARCHITECTURE.md §T2 §5): written only when
// the layer carries a balloon; readers ignoring unknown attrs degrade
// to the visible pixels.
void writeBubbleAttrs(const Layer& l, std::ostringstream& xml) {
    if (!l.hasBubble()) return;
    xml << " x-openclip-bubble=\"" << xmlEscape(l.bubbleShape) << "\""
        << " x-openclip-bubble-frame=\"" << xmlEscape(l.bubbleFrame)
        << "\"";
    if (!l.bubbleTail.empty())
        xml << " x-openclip-bubble-tail=\"" << xmlEscape(l.bubbleTail)
            << "\"";
    xml << " x-openclip-bubble-fill=\"" << xmlEscape(l.bubbleFill) << "\""
        << " x-openclip-bubble-stroke=\""
        << xmlEscape(l.bubbleStroke) << "\""
        << " x-openclip-bubble-strokew=\"" << l.bubbleStrokeW << "\""
        << " x-openclip-bubble-text=\"" << l.bubbleTextLayer << "\"";
}

// T2 vector sibling (ARCHITECTURE.md §T2 §4): serialize vectorData as
// data/vectorNNN.json when it fits the 256 KiB cap; sets vectorStored
// (the visible counter in nLayerState). Over-cap = PNG alone.
void writeVectorSibling(Layer& l, std::ostringstream& xml,
                        std::vector<std::pair<std::string, std::vector<std::uint8_t>>>&
                            entries,
                        int idx) {
    l.vectorStored = false;
    if (!l.isVector || l.vectorData.empty()) return;
    std::ostringstream js;
    js << "{\"paths\":[";
    for (std::size_t k = 0; k < l.vectorData.size(); ++k) {
        if (k > 0) js << ",";
        const VectorPath& vp = l.vectorData[k];
        js << "{\"pts\":[";
        for (std::size_t i = 0; i < vp.pts.size(); ++i) {
            if (i > 0) js << ",";
            char buf[48];
            std::snprintf(buf, sizeof buf, "[%.2f,%.2f]", vp.pts[i].x,
                          vp.pts[i].y);
            js << buf;
        }
        js << "],\"widths\":[";
        for (std::size_t i = 0; i < vp.widthProfile.size(); ++i) {
            if (i > 0) js << ",";
            char buf[32];
            std::snprintf(buf, sizeof buf, "%.2f", vp.widthProfile[i]);
            js << buf;
        }
        js << "],\"tip\":\"" << vp.tip << "\",\"size\":" << vp.sizeMul
           << ",\"color\":\"#";
        char hex[8];
        std::snprintf(hex, sizeof hex, "%02X%02X%02X", vp.r, vp.g, vp.b);
        js << hex << "\",\"corners\":[";
        for (std::size_t i = 0; i < vp.corners.size(); ++i) {
            if (i > 0) js << ",";
            js << (vp.corners[i] ? "1" : "0");
        }
        js << "],\"kind\":\"" << vp.kind << "\"}";
    }
    js << "]}";
    const std::string s = js.str();
    if (s.size() > 262144) return;  // cap: PNG alone + counter
    char name[64];
    std::snprintf(name, sizeof name, "data/vector%03d.json", idx);
    entries.push_back({name, {s.begin(), s.end()}});
    xml << " x-openclip-vector=\"" << name << "\"";
    l.vectorStored = true;
}

// T5 lock flags (ARCHITECTURE.md "T5 additions" §1): written only
// when set (absent = false); third-party readers ignore unknown attrs
// and degrade to the visible pixels.
void writeFlagsAttrs(const Layer& l, std::ostringstream& xml) {
    if (l.locked) xml << " x-openclip-locked=\"1\"";
    if (l.alphaLock) xml << " x-openclip-alphalock=\"1\"";
    if (l.reference) xml << " x-openclip-reference=\"1\"";
    if (l.draft) xml << " x-openclip-draft=\"1\"";
}

// T5 fill fiche (ARCHITECTURE.md "T5 additions" §2): kind + stops +
// params spans; absent = normal layer (clean degrade like M8).
void writeFillAttrs(const Layer& l, std::ostringstream& xml) {
    if (!l.hasFill()) return;
    xml << " x-openclip-fill=\"" << xmlEscape(l.fillKind) << "\"";
    if (!l.fillColor.empty())
        xml << " x-openclip-fill-color=\""
            << xmlEscape(l.fillColor) << "\"";
    if (!l.fillStops.empty())
        xml << " x-openclip-fill-stops=\""
            << xmlEscape(l.fillStops) << "\"";
    if (!l.fillParams.empty())
        xml << " x-openclip-fill-params=\""
            << xmlEscape(l.fillParams) << "\"";
}

// B1 layer texture fiche (ARCHITECTURE.md "B1 additions" §5): kind +
// params span; absent = no texture (clean degrade like M8/T5).
void writeTextureAttrs(const Layer& l, std::ostringstream& xml) {
    if (!l.hasTexture()) return;
    xml << " x-openclip-texture=\"" << xmlEscape(l.textureKind) << "\"";
    if (!l.textureParams.empty())
        xml << " x-openclip-texture-params=\""
            << xmlEscape(l.textureParams) << "\"";
}

// V+2: generic opaque fiche map (nSetLayerMeta). Written verbatim,
// reloaded by applyMetaAttrs below (unknown x-openclip-* attrs on
// load are kept too, so third-party round trips preserve fiches).
void writeMetaAttrs(const Layer& l, std::ostringstream& xml) {
    for (const auto& kv : l.meta)
        xml << " " << kv.first << "=\"" << xmlEscape(kv.second) << "\"";
}

void writeLayerOne(Layer& l, std::ostringstream& xml,
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
            << compositeOpFor(l) << "\" isolation=\""
            << (l.passThrough ? "auto" : "isolated") << "\"";
        if (!l.adjustType.empty()) {
            xml << " adjust-type=\"" << xmlEscape(l.adjustType) << "\""
                << " adjust-amount=\"" << l.adjustAmount << "\""
                << " adjust-stops=\"" << xmlEscape(l.adjustStops)
                << "\"";
            if (l.adjustType == "curves" && !l.adjustCurve.empty())
                xml << " adjust-curve=\"" << xmlEscape(l.adjustCurve)
                    << "\"";
        }
        writeTextAttrs(l, xml);
        writeBubbleAttrs(l, xml);
        writeFlagsAttrs(l, xml);
        writeFillAttrs(l, xml);
        writeTextureAttrs(l, xml);
        writeMetaAttrs(l, xml);
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
        << compositeOpFor(l) << "\"";
    // M8: adjustment descriptor persists as attrs (round trips through
    // tagAttr/xmlUnescape on load). V+2 curves adds adjust-curve.
    if (!l.adjustType.empty()) {
        xml << " adjust-type=\"" << xmlEscape(l.adjustType) << "\""
            << " adjust-amount=\"" << l.adjustAmount << "\""
            << " adjust-stops=\"" << xmlEscape(l.adjustStops) << "\"";
        if (l.adjustType == "curves" && !l.adjustCurve.empty())
            xml << " adjust-curve=\"" << xmlEscape(l.adjustCurve) << "\"";
    }
    writeTextAttrs(l, xml);
    writeBubbleAttrs(l, xml);
    writeFlagsAttrs(l, xml);
    writeFillAttrs(l, xml);
    writeTextureAttrs(l, xml);
    writeMetaAttrs(l, xml);
    writeVectorSibling(l, xml, entries, idx);
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

void writeLayerList(std::vector<Layer>& layers, std::ostringstream& xml,
                    std::vector<std::pair<std::string, std::vector<std::uint8_t>>>&
                        entries,
                    int& idx, int indent, std::size_t depth,
                    std::string& error, bool& ok) {
    if (!ok) return;
    for (auto& l : layers) writeLayerOne(l, xml, entries, idx, indent,
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
// V+2 curves reads adjust-curve alongside (identity fallback when
// absent — applyCurvesOp treats empty as no-op).
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
    l.adjustCurve.clear();
    if (t == "curves") tagAttr(xml, tagStart, "adjust-curve", l.adjustCurve);
}

// T5: reload the lock flags + fill fiche (ARCHITECTURE.md "T5
// additions" §1–§2). Absent attrs = defaults (normal raster layer);
// never a broken layer (the M8 identity contract).
void applyFlagsFillAttrs(const std::string& xml, std::size_t tagStart,
                         Layer& l) {
    std::string v;
    l.locked = tagAttr(xml, tagStart, "x-openclip-locked", v) && v == "1";
    l.alphaLock =
        tagAttr(xml, tagStart, "x-openclip-alphalock", v) && v == "1";
    l.reference =
        tagAttr(xml, tagStart, "x-openclip-reference", v) && v == "1";
    l.draft = tagAttr(xml, tagStart, "x-openclip-draft", v) && v == "1";
    l.fillKind.clear();
    l.fillColor.clear();
    l.fillStops.clear();
    l.fillParams.clear();
    if (tagAttr(xml, tagStart, "x-openclip-fill", v) && !v.empty() &&
        (v == "solid" || v == "gradient" || v == "foil")) {
        l.fillKind = v;
        if (tagAttr(xml, tagStart, "x-openclip-fill-color", v))
            l.fillColor = v.substr(0, 32);
        if (tagAttr(xml, tagStart, "x-openclip-fill-stops", v))
            l.fillStops = v.substr(0, 1024);
        if (tagAttr(xml, tagStart, "x-openclip-fill-params", v))
            l.fillParams = v.substr(0, 1024);
    }
    // B1: texture fiche (unknown kind = no texture, pixels intact).
    l.textureKind.clear();
    l.textureParams.clear();
    if (tagAttr(xml, tagStart, "x-openclip-texture", v) && !v.empty() &&
        (v == "paper" || v == "canvas" || v == "grain")) {
        l.textureKind = v;
        if (tagAttr(xml, tagStart, "x-openclip-texture-params", v))
            l.textureParams = v.substr(0, 128);
    }
}

// V+2: reload the generic fiche map. Every x-openclip-* attr on the
// tag is kept verbatim (capped values, capped count — DoS hygiene on
// foreign files), so round trips preserve fiches even written by
// third parties.
void applyMetaAttrs(const std::string& xml, std::size_t tagStart,
                    Layer& l) {
    const std::size_t close = xml.find('>', tagStart);
    if (close == std::string::npos) return;
    static const std::string kPrefix = "x-openclip-";
    std::size_t p = tagStart;
    int kept = 0;
    while (kept < 64) {
        p = xml.find(kPrefix, p);
        if (p == std::string::npos || p >= close) break;
        // Attribute name = [a-z0-9-] run starting at p.
        std::size_t e = p + kPrefix.size();
        while (e < close && (xml[e] == '-' ||
                             (xml[e] >= 'a' && xml[e] <= 'z') ||
                             (xml[e] >= '0' && xml[e] <= '9')))
            ++e;
        if (e == p + kPrefix.size()) {
            p += kPrefix.size();
            continue;
        }
        const std::string name = xml.substr(p, e - p);
        std::size_t q = e;
        while (q < close && std::isspace((unsigned char)xml[q])) ++q;
        if (q >= close || xml[q] != '=' ||
            name == "x-openclip-text" || name == "x-openclip-font" ||
            name == "x-openclip-size" || name == "x-openclip-style" ||
            name == "x-openclip-align" || name == "x-openclip-leading" ||
            name == "x-openclip-tracking" || name == "x-openclip-color" ||
            name == "x-openclip-box" || name == "x-openclip-text-runs" ||
            name == "x-openclip-text-outline" ||
            name == "x-openclip-text-fontfile" ||
            name == "x-openclip-text-dir" ||
            name == "x-openclip-text-hyphen" ||
            name == "x-openclip-text-xform" ||
            name == "x-openclip-bubble" ||
            name == "x-openclip-bubble-frame" ||
            name == "x-openclip-bubble-tail" ||
            name == "x-openclip-bubble-fill" ||
            name == "x-openclip-bubble-stroke" ||
            name == "x-openclip-bubble-strokew" ||
            name == "x-openclip-bubble-text" ||
            name == "x-openclip-vector" ||
            name == "x-openclip-locked" ||
            name == "x-openclip-alphalock" ||
            name == "x-openclip-reference" ||
            name == "x-openclip-draft" || name == "x-openclip-fill" ||
            name == "x-openclip-fill-color" ||
            name == "x-openclip-fill-stops" ||
            name == "x-openclip-fill-params" ||
            name == "x-openclip-texture" ||
            name == "x-openclip-texture-params") {
            p = e;
            continue;  // text/bubble/vector/flags/fill fiches have
                       // their own attrs (above)
        }
        std::string v;
        if (tagAttr(xml, p, name.c_str(), v)) {
            l.meta[name] = v.substr(0, 4096);
            ++kept;
        }
        p = e;
    }
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
    // T2 extensions (ARCHITECTURE.md §T2 §1–§2, opaque): absent = the
    // global style / horizontal / no hyphen (rule M3).
    l.textRuns.clear();
    if (tagAttr(xml, tagStart, "x-openclip-text-runs", v))
        l.textRuns = v.substr(0, 16384);
    l.textOutline.clear();
    if (tagAttr(xml, tagStart, "x-openclip-text-outline", v))
        l.textOutline = v.substr(0, 256);
    l.textFontFile.clear();
    if (tagAttr(xml, tagStart, "x-openclip-text-fontfile", v))
        l.textFontFile = capped(v);
    l.textDir.clear();
    if (tagAttr(xml, tagStart, "x-openclip-text-dir", v)) {
        const std::string d = capped(v);
        if (d == "v" || d == "h") l.textDir = d;
    }
    l.textHyphen.clear();
    if (tagAttr(xml, tagStart, "x-openclip-text-hyphen", v)) {
        const std::string hh = capped(v);
        if (hh == "1" || hh == "true") l.textHyphen = "1";
    }
    // B2 §1: transform tuple, opaque (empty = identity).
    l.textXform.clear();
    if (tagAttr(xml, tagStart, "x-openclip-text-xform", v))
        l.textXform = v.substr(0, 96);
}

// T2 bubble fiche reader (ARCHITECTURE.md §T2 §5, same M8 contract as
// the text fiche above): absent/unknown shape = normal layer.
void applyBubbleAttrs(const std::string& xml, std::size_t tagStart,
                      Layer& l) {
    l.bubbleShape.clear();
    l.bubbleFrame.clear();
    l.bubbleTail.clear();
    l.bubbleFill = "#FFFFFF";
    l.bubbleStroke = "#000000";
    l.bubbleStrokeW = 4.0f;
    l.bubbleTextLayer = -1;
    std::string v;
    if (!tagAttr(xml, tagStart, "x-openclip-bubble", v)) return;
    if (v != "ellipse" && v != "rounded") return;
    l.bubbleShape = v;
    auto capped = [](const std::string& s) { return s.substr(0, 128); };
    l.bubbleFrame =
        tagAttr(xml, tagStart, "x-openclip-bubble-frame", v) ? capped(v)
                                                             : "";
    l.bubbleTail =
        tagAttr(xml, tagStart, "x-openclip-bubble-tail", v) ? capped(v)
                                                            : "";
    if (tagAttr(xml, tagStart, "x-openclip-bubble-fill", v) && !v.empty())
        l.bubbleFill = capped(v);
    if (tagAttr(xml, tagStart, "x-openclip-bubble-stroke", v) && !v.empty())
        l.bubbleStroke = capped(v);
    if (tagAttr(xml, tagStart, "x-openclip-bubble-strokew", v)) {
        try {
            l.bubbleStrokeW = std::stof(v);
        } catch (...) {
            l.bubbleStrokeW = 4.0f;
        }
        if (!(l.bubbleStrokeW >= 1.0f && l.bubbleStrokeW <= 32.0f))
            l.bubbleStrokeW = 4.0f;
    }
    if (tagAttr(xml, tagStart, "x-openclip-bubble-text", v)) {
        try {
            l.bubbleTextLayer = std::stoi(v);
        } catch (...) {
            l.bubbleTextLayer = -1;
        }
        if (l.bubbleTextLayer < -1 || l.bubbleTextLayer > 1024)
            l.bubbleTextLayer = -1;
    }
}

// T2 vector sibling loader (ARCHITECTURE.md §T2 §4): parses
// data/vectorNNN.json (the writer's format above) into vectorData.
// Missing/unreadable = PNG alone as a NORMAL raster layer (isVector
// cleared — never a broken layer, the degraded-propre contract).
bool applyVectorSibling(const std::string& xml, std::size_t tagStart,
                        const std::map<std::string, std::vector<std::uint8_t>>&
                            entries,
                        Layer& l) {
    l.vectorData.clear();
    l.vectorStored = false;
    std::string src;
    if (!tagAttr(xml, tagStart, "x-openclip-vector", src)) return false;
    auto eit = entries.find(src);
    if (eit == entries.end() || eit->second.size() > 262144) return false;
    const std::string js(eit->second.begin(), eit->second.end());
    // Tolerant scan of {"pts":[[x,y]...],"widths":[...],"tip":..,
    // "size":..,"color":..,"corners":[..],"kind":..} objects.
    std::size_t pos = 0;
    int parsed = 0;
    while (parsed < 4096) {
        const std::size_t ob = js.find('{', pos);
        if (ob == std::string::npos) break;
        // Skip the outer {"paths":[...] opener (no pts key inside).
        const std::size_t ptsK = js.find("\"pts\"", ob);
        const std::size_t nextOb = js.find('{', ob + 1);
        if (ptsK == std::string::npos ||
            (nextOb != std::string::npos && nextOb < ptsK)) {
            pos = ob + 1;
            continue;
        }
        VectorPath vp;
        // pts: [[x,y],[x,y]...]
        const std::size_t pa = js.find('[', ptsK);
        const std::size_t pe = (pa == std::string::npos)
                                   ? std::string::npos
                                   : js.find("]]", pa);
        if (pa == std::string::npos || pe == std::string::npos) break;
        {
            const std::string arr = js.substr(pa + 1, pe - pa);
            std::size_t q = 0;
            while (q < arr.size()) {
                while (q < arr.size() &&
                       (arr[q] < '0' || arr[q] > '9') && arr[q] != '-' &&
                       arr[q] != '+' && arr[q] != '.')
                    ++q;
                if (q >= arr.size()) break;
                char* e1 = nullptr;
                const float x =
                    std::strtof(arr.c_str() + q, &e1);
                if (e1 == arr.c_str() + q) break;
                q = static_cast<std::size_t>(e1 - arr.c_str());
                while (q < arr.size() &&
                       (arr[q] < '0' || arr[q] > '9') && arr[q] != '-' &&
                       arr[q] != '+' && arr[q] != '.')
                    ++q;
                if (q >= arr.size()) break;
                char* e2 = nullptr;
                const float y =
                    std::strtof(arr.c_str() + q, &e2);
                if (e2 == arr.c_str() + q) break;
                q = static_cast<std::size_t>(e2 - arr.c_str());
                vp.pts.push_back(Vec2{x, y});
                if (vp.pts.size() > 65536) break;
            }
        }
        if (vp.pts.empty()) break;
        // widths: [...]
        const std::size_t wk = js.find("\"widths\"", ob);
        if (wk != std::string::npos) {
            const std::size_t wa = js.find('[', wk);
            const std::size_t we =
                (wa == std::string::npos)
                    ? std::string::npos
                    : js.find(']', wa);
            if (wa != std::string::npos && we != std::string::npos) {
                const std::string arr = js.substr(wa + 1, we - wa - 1);
                std::size_t q = 0;
                while (q < arr.size() && vp.widthProfile.size() < 65536) {
                    while (q < arr.size() &&
                           (arr[q] < '0' || arr[q] > '9') &&
                           arr[q] != '-' && arr[q] != '+' &&
                           arr[q] != '.')
                        ++q;
                    if (q >= arr.size()) break;
                    char* e1 = nullptr;
                    const float w =
                        std::strtof(arr.c_str() + q, &e1);
                    if (e1 == arr.c_str() + q) break;
                    q = static_cast<std::size_t>(e1 - arr.c_str());
                    float cw = w;
                    if (!(cw >= 0.5f)) cw = 0.5f;
                    if (cw > 128.0f) cw = 128.0f;
                    vp.widthProfile.push_back(cw);
                }
            }
        }
        if (vp.widthProfile.size() != vp.pts.size())
            vp.widthProfile.assign(vp.pts.size(), 8.0f);
        // kind / tip / size / color / corners (M3: bad = defaults).
        auto strKey = [&](const char* key, std::string& out) {
            const std::string pat = std::string("\"") + key + "\"";
            const std::size_t at = js.find(pat, ob);
            if (at == std::string::npos) return;
            const std::size_t colon = js.find(':', at + pat.size());
            if (colon == std::string::npos) return;
            const std::size_t q1 = js.find('"', colon + 1);
            if (q1 == std::string::npos) return;
            const std::size_t q2 = js.find('"', q1 + 1);
            if (q2 == std::string::npos) return;
            out = js.substr(q1 + 1, q2 - q1 - 1);
        };
        std::string kind, tip, col;
        strKey("kind", kind);
        vp.kind = (kind == "eraser-stroke") ? "eraser-stroke" : "stroke";
        strKey("tip", tip);
        vp.tip = (tip == "flat") ? "flat" : "round";
        {
            const std::size_t at = js.find("\"size\"", ob);
            if (at != std::string::npos) {
                const std::size_t colon = js.find(':', at + 6);
                if (colon != std::string::npos) {
                    char* e1 = nullptr;
                    const double sz =
                        std::strtod(js.c_str() + colon + 1, &e1);
                    if (e1 != js.c_str() + colon + 1 && sz >= 0.25 &&
                        sz <= 4.0)
                        vp.sizeMul = static_cast<float>(sz);
                }
            }
        }
        strKey("color", col);
        if (!col.empty()) {
            std::string h = col;
            if (!h.empty() && h[0] == '#') h = h.substr(1);
            if (h.size() == 6) {
                auto hx = [](char c) -> int {
                    if (c >= '0' && c <= '9') return c - '0';
                    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
                    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
                    return -1;
                };
                int v[3];
                bool okc = true;
                for (int i = 0; i < 3 && okc; ++i) {
                    const int hi = hx(h[i * 2]), lo = hx(h[i * 2 + 1]);
                    if (hi < 0 || lo < 0)
                        okc = false;
                    else
                        v[i] = hi * 16 + lo;
                }
                if (okc) {
                    vp.r = static_cast<std::uint8_t>(v[0]);
                    vp.g = static_cast<std::uint8_t>(v[1]);
                    vp.b = static_cast<std::uint8_t>(v[2]);
                }
            }
        }
        vp.corners.assign(vp.pts.size(), 0);
        {
            const std::size_t at = js.find("\"corners\"", ob);
            if (at != std::string::npos) {
                const std::size_t ca = js.find('[', at);
                const std::size_t cb = (ca == std::string::npos)
                                           ? std::string::npos
                                           : js.find(']', ca);
                if (ca != std::string::npos && cb != std::string::npos) {
                    const std::string arr = js.substr(ca + 1, cb - ca - 1);
                    std::size_t q = 0, n = 0;
                    while (q < arr.size() && n < vp.corners.size()) {
                        while (q < arr.size() && arr[q] != '0' &&
                               arr[q] != '1')
                            ++q;
                        if (q >= arr.size()) break;
                        vp.corners[n++] = (arr[q] == '1') ? 1 : 0;
                        ++q;
                    }
                }
            }
        }
        l.vectorData.push_back(std::move(vp));
        ++parsed;
        // Advance past this object (brace-depth walk, bounded).
        int dep = 0;
        std::size_t k = ob;
        bool inStr = false;
        while (k < js.size()) {
            const char c = js[k];
            if (c == '"' && (k == 0 || js[k - 1] != '\\')) inStr = !inStr;
            if (!inStr) {
                if (c == '{') ++dep;
                if (c == '}') {
                    --dep;
                    if (dep == 0) {
                        ++k;
                        break;
                    }
                }
            }
            ++k;
            if (k - ob > 1048576) break;  // insane object: stop
        }
        pos = k;
    }
    if (l.vectorData.empty()) return false;
    l.isVector = true;
    l.vectorStored = true;
    return true;
}

bool loadLayerList(const std::string& xml, std::size_t start, std::size_t end,
                   std::map<std::string, std::vector<std::uint8_t>>& entries,
                   Canvas& tmp, std::size_t depth, std::vector<Layer>& out,
                   std::string& error);

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
                applyBlendName(comp, l);
                applyAdjustAttrs(xml, lOpen, l);
                applyTextAttrs(xml, lOpen, l);
                applyBubbleAttrs(xml, lOpen, l);
                applyFlagsFillAttrs(xml, lOpen, l);
                applyMetaAttrs(xml, lOpen, l);
                // T2 vector sibling: present + parsed = vector layer
                // restored; missing/unreadable = PNG alone as a normal
                // raster layer (degraded propre, never broken).
                applyVectorSibling(xml, lOpen, entries, l);
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
                applyBlendName(comp, g);
                g.passThrough = (iso != "isolated");
                applyAdjustAttrs(xml, lOpen, g);
                applyTextAttrs(xml, lOpen, g);
                applyBubbleAttrs(xml, lOpen, g);
                applyFlagsFillAttrs(xml, lOpen, g);
                applyMetaAttrs(xml, lOpen, g);
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
            applyBlendName(comp, g);
            g.passThrough = (iso != "isolated");
            applyAdjustAttrs(xml, lOpen, g);
            applyTextAttrs(xml, lOpen, g);
            applyFlagsFillAttrs(xml, lOpen, g);
            applyMetaAttrs(xml, lOpen, g);
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

// Encode premultiplied surface to straight-RGBA PNG bytes in memory.
// At openclip:: scope (NOT anonymous): declared in ora.h, shared with
// the T3 PNG sequence writer.
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
    // T5 §1: PNG is an export — reference/draft layers stay out.
    c.compositeExport(out);
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