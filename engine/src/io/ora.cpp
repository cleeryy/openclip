// SPDX-License-Identifier: GPL-3.0-or-later
// OpenRaster save/load + PNG export.
// Premultiplied RGBA8 <-> straight PNG conversion; zlib for zip via minizip.
#include "openclip/ora.h"

#include "openclip/canvas.h"

#include <cctype>
#include <cstdio>
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
    if (!data || w <= 0 || h <= 0) return false;
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

}  // namespace

bool saveOra(const Canvas& c, const std::string& path, std::string& error) {
    std::vector<std::pair<std::string, std::vector<std::uint8_t>>> entries;
    {
        const std::string mt = "image/openraster";
        entries.push_back(
            {"mimetype", {mt.begin(), mt.end()}});
    }
    std::ostringstream xml;
    xml << "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<image w=\""
        << c.width() << "\" h=\"" << c.height() << "\">\n<stack>\n";
    int idx = 0;
    Surface thumb;
    c.composite(thumb);
    for (std::size_t i = 0; i < c.layerCount(); ++i) {
        const Layer& l = c.layer(i);
        char src[64];
        std::snprintf(src, sizeof src, "data/layer%03d.png", idx);
        xml << "  <layer name=\"" << xmlEscape(l.name) << "\" src=\"" << src
            << "\" opacity=\"" << l.opacity << "\" visibility=\""
            << (l.visible ? "visible" : "hidden") << "\" composite-op=\""
            << blendToName(l.blend) << "\"/>\n";
        std::vector<std::uint8_t> png;
        if (!encodePng(l.surface, png)) {
            error = "ora: PNG encode failed for layer " + l.name;
            return false;
        }
        entries.push_back({src, std::move(png)});
        ++idx;
    }
    xml << "</stack>\n</image>\n";
    const std::string xs = xml.str();
    entries.push_back({"stack.xml", {xs.begin(), xs.end()}});
    std::vector<std::uint8_t> merged;
    if (!encodePng(thumb, merged)) {
        error = "ora: PNG encode failed for mergedimage";
        return false;
    }
    entries.push_back({"mergedimage.png", std::move(merged)});
    // Thumbnail dir is optional in ORA; keep archive minimal but valid.
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
    // Image size.
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
    std::size_t pos = 0;
    bool any = false;
    while ((pos = xml.find("<layer", pos)) != std::string::npos) {
        // Skip <layers> container etc.: require "src=" present.
        std::string name = "layer", src, op = "1", vis = "visible", comp = "svg:normal";
        tagAttr(xml, pos, "name", name);
        if (!tagAttr(xml, pos, "src", src)) {
            pos += 6;
            continue;
        }
        tagAttr(xml, pos, "opacity", op);
        tagAttr(xml, pos, "visibility", vis);
        tagAttr(xml, pos, "composite-op", comp);
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
        const std::size_t li = tmp.addLayer(name);
        Layer& l = tmp.layer(li);
        try {
            l.opacity = std::stof(op);
        } catch (...) {
            l.opacity = 1.0f;
        }
        if (l.opacity < 0) l.opacity = 0;
        if (l.opacity > 1) l.opacity = 1;
        l.visible = (vis != "hidden" && vis != "false" && vis != "0");
        l.blend = blendFromName(comp.c_str());
        if (s.width() == w && s.height() == h) {
            l.surface = s;
        } else {
            // Blit clipped (be lenient with odd files).
            l.surface.resize(w, h);
            for (int y = 0; y < h && y < s.height(); ++y)
                for (int x = 0; x < w && x < s.width(); ++x)
                    l.surface.set(x, y, s.get(x, y));
        }
        any = true;
        const std::size_t end = xml.find('>', pos);
        pos = end == std::string::npos ? pos + 6 : end + 1;
    }
    if (!any) {
        error = "ora: no layers found";
        return false;
    }
    c = std::move(tmp);
    c.setActiveLayer(0);
    return true;
}

bool exportPng(const Canvas& c, const std::string& path, std::string& error) {
    Surface out;
    c.composite(out);
    const int w = out.width(), h = out.height();
    const auto& px = out.bytes();
    std::vector<std::uint8_t> straight((std::size_t)w * h * 4, 0);
    for (std::size_t i = 0; i < (std::size_t)w * h; ++i) {
        const std::uint8_t a = px[i * 4 + 3];
        straight[i * 4 + 3] = a;
        if (a == 0) continue;
        if (a == 255) {
            straight[i * 4 + 0] = px[i * 4 + 0];
            straight[i * 4 + 1] = px[i * 4 + 1];
            straight[i * 4 + 2] = px[i * 4 + 2];
        } else {
            for (int ch = 0; ch < 3; ++ch) {
                int v = (px[i * 4 + ch] * 255 + a / 2) / a;
                straight[i * 4 + ch] = (std::uint8_t)(v > 255 ? 255 : v);
            }
        }
    }
    if (!stbi_write_png(path.c_str(), w, h, 4, straight.data(), w * 4)) {
        error = "png: write failed for " + path;
        return false;
    }
    return true;
}

}  // namespace openclip
