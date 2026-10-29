// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip V+3 — TIFF baseline tests (ARCHITECTURE.md "V+3 additions"):
// minimal II/MM files are GENERATED in-code (clean-room, no binary
// assets in git): uncompressed RGB strips, LZW strips (tiny MSB-first
// encoder below, Clear/literals/EOI), and a CMYK refusal.
#include "test.h"

#include <algorithm>
#include <cstdio>
#include <string>
#include <vector>

#include "openclip/canvas.h"
#include "openclip/document.h"

using openclip::Canvas;

namespace {

void putU16(std::vector<std::uint8_t>& b, std::uint16_t v, bool le) {
    if (le) {
        b.push_back(static_cast<std::uint8_t>(v & 0xFF));
        b.push_back(static_cast<std::uint8_t>((v >> 8) & 0xFF));
    } else {
        b.push_back(static_cast<std::uint8_t>((v >> 8) & 0xFF));
        b.push_back(static_cast<std::uint8_t>(v & 0xFF));
    }
}

void putU32(std::vector<std::uint8_t>& b, std::uint32_t v, bool le) {
    if (le) {
        for (int i = 0; i < 4; ++i)
            b.push_back(static_cast<std::uint8_t>((v >> (8 * i)) & 0xFF));
    } else {
        for (int i = 3; i >= 0; --i)
            b.push_back(static_cast<std::uint8_t>((v >> (8 * i)) & 0xFF));
    }
}

void putU32At(std::vector<std::uint8_t>& b, std::size_t o, std::uint32_t v,
              bool le) {
    if (le) {
        for (int i = 0; i < 4; ++i)
            b[o + i] = static_cast<std::uint8_t>((v >> (8 * i)) & 0xFF);
    } else {
        for (int i = 3; i >= 0; --i)
            b[o + (3 - i)] = static_cast<std::uint8_t>((v >> (8 * i)) & 0xFF);
    }
}

// Tiny baseline LZW encoder (TIFF variant, MSB-first, 9-bit codes:
// Clear, one literal per input byte, EOI). Only for fixtures small
// enough to never widen the code (next stays < 512).
std::vector<std::uint8_t> lzwEncodeLit(const std::vector<std::uint8_t>& in) {
    std::vector<std::uint8_t> out;
    int bits = 0, acc = 0;
    auto emit = [&](std::uint32_t code, int width) {
        for (int i = width - 1; i >= 0; --i) {
            acc = (acc << 1) | ((code >> i) & 1);
            if (++bits == 8) {
                out.push_back(static_cast<std::uint8_t>(acc));
                acc = 0;
                bits = 0;
            }
        }
    };
    emit(256, 9);
    for (std::uint8_t v : in) emit(v, 9);
    emit(257, 9);
    if (bits > 0)
        out.push_back(static_cast<std::uint8_t>(acc << (8 - bits)));
    return out;
}

// Build a minimal baseline TIFF: width x height RGB, `strips` strips,
// compression 1 (none) or 5 (LZW), photometric 2 (RGB) or 5 (CMYK).
bool writeTiff(const std::string& path, int w, int h,
               const std::vector<std::uint8_t>& rgb, int strips,
               int compression, int photo) {
    if ((int)rgb.size() != w * h * 3) return false;
    const bool le = true;
    std::vector<std::uint8_t> b;
    b.push_back('I');
    b.push_back('I');
    putU16(b, 42, le);
    putU32(b, 8, le);
    // IFD with 9 entries.
    putU16(b, 9, le);
    auto entry = [&](int tag, int type, int cnt, std::uint32_t val) {
        putU16(b, tag, le);
        putU16(b, type, le);
        putU32(b, cnt, le);
        putU32(b, val, le);
    };
    const int rowsPerStrip = (h + strips - 1) / strips;
    // Offsets patched later: bits(3 SHORTs), strip offsets, byte counts.
    entry(256, 3, 1, w);
    entry(257, 3, 1, h);
    const std::size_t bitsPos = b.size() + 8;
    entry(258, 3, 3, 0);
    entry(259, 3, 1, compression);
    entry(262, 3, 1, photo);
    const std::size_t offPos = b.size() + 8;
    entry(273, 4, strips, 0);
    entry(277, 3, 1, 3);
    entry(278, 4, 1, rowsPerStrip);
    const std::size_t cntPos = b.size() + 8;
    entry(279, 4, strips, 0);
    putU32(b, 0, le);  // next IFD
    // Data area.
    const std::size_t bitsOff = b.size();
    putU16(b, 8, le);
    putU16(b, 8, le);
    putU16(b, 8, le);
    std::vector<std::uint32_t> offs, cnts;
    // Reserve table space when strips > 1.
    std::size_t offTab = 0, cntTab = 0;
    if (strips > 1) {
        offTab = b.size();
        for (int s = 0; s < strips; ++s) putU32(b, 0, le);
        cntTab = b.size();
        for (int s = 0; s < strips; ++s) putU32(b, 0, le);
    }
    for (int s = 0; s < strips; ++s) {
        const int y0 = s * rowsPerStrip;
        const int y1 = std::min(y0 + rowsPerStrip, h);
        std::vector<std::uint8_t> raw(rgb.begin() + (std::size_t)y0 * w * 3,
                                      rgb.begin() + (std::size_t)y1 * w * 3);
        const std::size_t at = b.size();
        if (compression == 5) {
            const auto enc = lzwEncodeLit(raw);
            b.insert(b.end(), enc.begin(), enc.end());
        } else {
            b.insert(b.end(), raw.begin(), raw.end());
        }
        offs.push_back((std::uint32_t)at);
        cnts.push_back((std::uint32_t)(b.size() - at));
    }
    putU32At(b, bitsPos, (std::uint32_t)bitsOff, le);
    if (strips == 1) {
        putU32At(b, offPos, offs[0], le);
        putU32At(b, cntPos, cnts[0], le);
    } else {
        putU32At(b, offPos, (std::uint32_t)offTab, le);
        putU32At(b, cntPos, (std::uint32_t)cntTab, le);
        for (int s = 0; s < strips; ++s) {
            putU32At(b, offTab + s * 4, offs[s], le);
            putU32At(b, cntTab + s * 4, cnts[s], le);
        }
    }
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) return false;
    const bool ok = std::fwrite(b.data(), 1, b.size(), f) == b.size();
    std::fclose(f);
    return ok;
}

}  // namespace

TEST(tiff_uncompressed_rgb_strips_roundtrip) {
    const int w = 8, h = 4;
    std::vector<std::uint8_t> rgb;
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            rgb.push_back((std::uint8_t)(x * 32));
            rgb.push_back((std::uint8_t)(y * 60));
            rgb.push_back(128);
        }
    const std::string path = testh::tmpPath("openclip_v3_plain.tif");
    CHECK(writeTiff(path, w, h, rgb, 2, 1, 2));
    Canvas c;
    std::string err;
    CHECK(openclip::loadDocument(c, path, err));
    CHECK_EQ(c.width(), w);
    CHECK_EQ(c.height(), h);
    CHECK_EQ(c.layerCount(), (std::size_t)1);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const auto p = c.layer(0).surface.get(x, y);
            CHECK_EQ(p.r, rgb[((std::size_t)y * w + x) * 3]);
            CHECK_EQ(p.g, rgb[((std::size_t)y * w + x) * 3 + 1]);
            CHECK_EQ(p.b, rgb[((std::size_t)y * w + x) * 3 + 2]);
            CHECK_EQ(p.a, 255);
        }
    std::remove(path.c_str());
}

TEST(tiff_lzw_strip_decodes) {
    const int w = 8, h = 4;
    std::vector<std::uint8_t> rgb;
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            rgb.push_back((std::uint8_t)(200 - x * 4));
            rgb.push_back((std::uint8_t)(30 + y * 8));
            rgb.push_back(77);
        }
    const std::string path = testh::tmpPath("openclip_v3_lzw.tiff");
    CHECK(writeTiff(path, w, h, rgb, 1, 5, 2));
    Canvas c;
    std::string err;
    CHECK(openclip::loadDocument(c, path, err));
    CHECK_EQ(c.width(), w);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const auto p = c.layer(0).surface.get(x, y);
            CHECK_EQ(p.r, rgb[((std::size_t)y * w + x) * 3]);
            CHECK_EQ(p.g, rgb[((std::size_t)y * w + x) * 3 + 1]);
            CHECK_EQ(p.b, rgb[((std::size_t)y * w + x) * 3 + 2]);
        }
    std::remove(path.c_str());
}

TEST(tiff_cmyk_rejected_session_intact) {
    const int w = 4, h = 4;
    std::vector<std::uint8_t> rgb((std::size_t)w * h * 3, 9);
    const std::string path = testh::tmpPath("openclip_v3_cmyk.tif");
    CHECK(writeTiff(path, w, h, rgb, 1, 1, 5));  // photometric CMYK
    Canvas c(4, 4);
    c.addLayer("keep");
    c.layer(0).surface.set(1, 1, {7, 7, 7, 255});
    const auto before = c.layer(0).surface.bytes();
    std::string err;
    CHECK(!openclip::loadDocument(c, path, err));
    CHECK(!err.empty());
    CHECK(c.layer(0).surface.bytes() == before);
    std::remove(path.c_str());
}
