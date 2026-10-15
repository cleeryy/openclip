// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip M4 — PSD baseline import/export, from-scratch clean-room parser.
// Scope (documented in ARCHITECTURE.md §M4): RGB 8-bit, baseline merged
// image import; flat export with per-channel raw planar data. Anything
// outside this scope is refused with a clear error, never guessed.
#include "openclip/psd.h"

#include "openclip/canvas.h"

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <vector>

namespace openclip {
namespace {

constexpr std::size_t kMaxSide = 16384;

struct PsdHeader {
    std::uint16_t version = 0;
    std::uint16_t channels = 0;
    std::uint32_t height = 0, width = 0;
    std::uint16_t depth = 0, mode = 0;
};

std::uint16_t rd16(const std::uint8_t* p) {
    return static_cast<std::uint16_t>((p[0] << 8) | p[1]);
}

std::uint32_t rd32(const std::uint8_t* p) {
    return (static_cast<std::uint32_t>(p[0]) << 24) |
           (static_cast<std::uint32_t>(p[1]) << 16) |
           (static_cast<std::uint32_t>(p[2]) << 8) | p[3];
}

void wr16(std::vector<std::uint8_t>& o, std::uint16_t v) {
    o.push_back((v >> 8) & 0xff);
    o.push_back(v & 0xff);
}

void wr32(std::vector<std::uint8_t>& o, std::uint32_t v) {
    o.push_back((v >> 24) & 0xff);
    o.push_back((v >> 16) & 0xff);
    o.push_back((v >> 8) & 0xff);
    o.push_back(v & 0xff);
}

bool readFile(const std::string& path, std::vector<std::uint8_t>& data,
              std::string& error) {
    std::ifstream f(path, std::ios::binary);
    if (!f) {
        error = "psd: cannot open file " + path;
        return false;
    }
    f.seekg(0, std::ios::end);
    const std::streamoff n = f.tellg();
    if (n < 0) {
        error = "psd: file size unusable";
        return false;
    }
    f.seekg(0, std::ios::beg);
    data.resize(static_cast<std::size_t>(n));
    if (!data.empty()) f.read(reinterpret_cast<char*>(data.data()),
                              static_cast<std::streamsize>(data.size()));
    if (!f && !data.empty() && data.size() != static_cast<std::size_t>(n)) {
        error = "psd: short read";
        return false;
    }
    return true;
}

}  // namespace

bool loadPsd(Canvas& c, const std::string& path, std::string& error) {
    std::vector<std::uint8_t> d;
    if (!readFile(path, d, error)) return false;
    if (d.size() < 26 || std::memcmp(d.data(), "8BPS", 4) != 0) {
        error = "psd: not a PSD file";
        return false;
    }
    PsdHeader h;
    h.version = rd16(d.data() + 4);
    if (h.version != 1) {
        error = "psd: unsupported version " + std::to_string(h.version);
        return false;
    }
    h.channels = rd16(d.data() + 12);
    h.height = rd32(d.data() + 14);
    h.width = rd32(d.data() + 18);
    h.depth = rd16(d.data() + 22);
    h.mode = rd16(d.data() + 24);
    if (h.width < 1 || h.height < 1 || h.width > kMaxSide ||
        h.height > kMaxSide) {
        error = "psd: dimensions out of range";
        return false;
    }
    if (h.depth != 8) {
        error = "psd: only 8-bit depth supported (got " +
                std::to_string(h.depth) + ")";
        return false;
    }
    if (h.mode != 3) {  // 3 = RGB in PSD mode table
        error = "psd: only RGB mode import supported (mode " +
                std::to_string(h.mode) + ")";
        return false;
    }
    if (h.channels != 3 && h.channels != 4) {
        error = "psd: expected 3 or 4 channels (got " +
                std::to_string(h.channels) + ")";
        return false;
    }
    // Color Mode Data (length must be 0 for RGB).
    std::size_t off = 26;
    const std::uint32_t cm = rd32(d.data() + off);
    if (cm != 0) {
        error = "psd: unexpected color mode data";
        return false;
    }
    off += 4;
    // Image Resources: bounded skip.
    const std::uint32_t res = rd32(d.data() + off);
    off += 4;
    if (static_cast<std::size_t>(res) > d.size() - off) {
        error = "psd: image resources overruns file";
        return false;
    }
    off += res;
    // Layer and Mask Information: length (we parse nothing in baseline).
    const std::uint32_t lm = rd32(d.data() + off);
    off += 4;
    if (static_cast<std::size_t>(lm) > d.size() - off) {
        error = "psd: layer/mask section overruns file";
        return false;
    }
    off += lm;
    // Image Data section: compression code then planar raw scanlines.
    if (d.size() - off < 2) {
        error = "psd: image data section missing";
        return false;
    }
    const std::uint16_t compression = rd16(d.data() + off);
    off += 2;
    if (compression != 0) {
        error = "psd: only raw (compression 0) baseline import supported";
        return false;
    }
    if (d.size() - off <
        static_cast<uint64_t>(h.width) * h.height * h.channels) {
        error = "psd: file truncated (image data short)";
        return false;
    }

    Canvas tmp(static_cast<int>(h.width), static_cast<int>(h.height));
    Layer bg;
    bg.name = "Background";
    bg.surface.resize(tmp.width(), tmp.height());
    auto& px = bg.surface.bytes();
    // Planar order: per channel, rows top-to-bottom, columns l-to-r.
    // Imported PSD is straight (non-premultiplied) RGBA.
    const std::size_t plane = static_cast<std::size_t>(h.width) * h.height;
    for (std::size_t ch = 0; ch < h.channels; ++ch) {
        const std::uint8_t* planePtr =
            d.data() + off + plane * ch;
        for (std::size_t y = 0; y < h.height; ++y)
            for (std::size_t x = 0; x < h.width; ++x)
                px[(y * h.width + x) * 4 + ch] =
                    planePtr[y * h.width + x];
    }
    if (h.channels == 3) {
        for (std::size_t i = 0; i < plane; ++i) px[i * 4 + 3] = 255;
    }
    // Premultiply in place (engine surfaces are premultiplied).
    for (std::size_t i = 0; i < plane; ++i) {
        const std::uint8_t a = px[i * 4 + 3];
        if (a == 255) continue;
        if (a == 0) {
            px[i * 4 + 0] = px[i * 4 + 1] = px[i * 4 + 2] = 0;
            continue;
        }
        for (int k = 0; k < 3; ++k) {
            const std::uint16_t v = (px[i * 4 + k] * a + 127) / 255;
            px[i * 4 + k] = static_cast<std::uint8_t>(v > a ? a : v);
        }
    }
    tmp.adoptLayer(std::move(bg));
    c = std::move(tmp);
    c.setActiveLayer(0);
    return true;
}

bool savePsd(const Canvas& c, const std::string& path, std::string& error) {
    Surface comp;
    c.composite(comp);
    const int w = comp.width(), h = comp.height();
    if (w <= 0 || h <= 0) {
        error = "psd: canvas empty";
        return false;
    }
    // Use 4 channels (RGB + alpha) to round-trip transparency honestly.
    const std::uint16_t channels = 4;
    std::vector<std::uint8_t> out;
    out.reserve(64);
    out.insert(out.end(), {'8', 'B', 'P', 'S'});
    wr16(out, 1);        // version
    // Reserved 6 zero bytes.
    for (int i = 0; i < 6; ++i) out.push_back(0);
    wr16(out, channels);
    wr32(out, static_cast<std::uint32_t>(h));
    wr32(out, static_cast<std::uint32_t>(w));
    wr16(out, 8);        // depth
    wr16(out, 3);        // RGB mode
    wr32(out, 0);        // Color Mode Data length
    wr32(out, 0);        // Image Resources length
    wr32(out, 0);        // Layer and Mask length
    wr16(out, 0);        // compression: raw
    // Un-premultiply composite into straight planar channels.
    const auto& px = comp.bytes();
    std::vector<std::uint8_t> ch[4];
    for (int k = 0; k < 4; ++k)
        ch[k].assign(static_cast<std::size_t>(w) * h, 0);
    for (std::size_t i = 0; i < static_cast<std::size_t>(w) * h; ++i) {
        const std::uint8_t a = px[i * 4 + 3];
        std::uint8_t stra[3];
        if (a == 0) {
            stra[0] = stra[1] = stra[2] = 0;
        } else if (a == 255) {
            stra[0] = px[i * 4 + 0];
            stra[1] = px[i * 4 + 1];
            stra[2] = px[i * 4 + 2];
        } else {
            for (int k = 0; k < 3; ++k) {
                int v = (px[i * 4 + k] * 255 + a / 2) / a;
                stra[k] = static_cast<std::uint8_t>(v > 255 ? 255 : v);
            }
        }
        ch[0][i] = stra[0];
        ch[1][i] = stra[1];
        ch[2][i] = stra[2];
        ch[3][i] = a;
    }
    for (int k = 0; k < channels; ++k)
        out.insert(out.end(), ch[k].begin(), ch[k].end());
    std::ofstream f(path, std::ios::binary | std::ios::trunc);
    if (!f) {
        error = "psd: cannot write " + path;
        return false;
    }
    f.write(reinterpret_cast<const char*>(out.data()),
            static_cast<std::streamsize>(out.size()));
    if (!f) {
        error = "psd: short write on " + path;
        return false;
    }
    return true;
}

}  // namespace openclip
