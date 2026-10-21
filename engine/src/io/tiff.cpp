// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip V+3 — TIFF baseline reader (ARCHITECTURE.md "V+3 additions").
// From scratch: II/MM headers, SHORT/LONG IFD entries, strips, LZW
// (TIFF variant, MSB-first, Clear=256/EOI=257). Limits are documented
// in tiff.h and every refusal names the unsupported feature.
#include "openclip/tiff.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include "openclip/canvas.h"

namespace openclip {
namespace {

constexpr std::size_t kMaxSide = 16384;
constexpr std::size_t kMaxFile = 256u * 1024u * 1024u;  // 256 MiB cap

bool readFile(const std::string& path, std::vector<std::uint8_t>& d,
              std::string& error) {
    std::FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) {
        error = "tiff: cannot open " + path;
        return false;
    }
    std::fseek(f, 0, SEEK_END);
    const long n = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    if (n < 0 || static_cast<std::size_t>(n) > kMaxFile) {
        std::fclose(f);
        error = "tiff: file too large";
        return false;
    }
    d.resize(static_cast<std::size_t>(n));
    const std::size_t got = std::fread(d.data(), 1, d.size(), f);
    std::fclose(f);
    if (got != d.size()) {
        error = "tiff: short read";
        return false;
    }
    return true;
}

struct Cursor {
    const std::uint8_t* d = nullptr;
    std::size_t n = 0;
    bool le = true;  // false = big-endian (MM)
    bool ok = true;
    std::uint16_t u16(std::size_t o) const {
        if (o + 2 > n) return 0;
        if (le)
            return static_cast<std::uint16_t>(d[o] | (d[o + 1] << 8));
        return static_cast<std::uint16_t>((d[o] << 8) | d[o + 1]);
    }
    std::uint32_t u32(std::size_t o) const {
        if (o + 4 > n) return 0;
        if (le)
            return static_cast<std::uint32_t>(d[o] | (d[o + 1] << 8) |
                                              (d[o + 2] << 16) |
                                              (d[o + 3] << 24));
        return static_cast<std::uint32_t>((d[o] << 24) | (d[o + 1] << 16) |
                                          (d[o + 2] << 8) | d[o + 3]);
    }
    bool range(std::size_t o, std::size_t len) const {
        return o + len <= n && o + len >= o;
    }
};

// TIFF LZW decoder (MSB-first codes, start 9 bits, Clear 256, EOI 257).
// Width widens when the next free entry reaches 1<<width (max 12); the
// table holds 4096 entries max. Streams that never emit CLEAR and
// overflow the table are refused honestly (real encoders reset often).
bool lzwDecode(const std::uint8_t* src, std::size_t srcLen,
               std::vector<std::uint8_t>& out, std::string& error) {
    std::size_t bit = 0;
    auto readCode = [&](int width, std::uint32_t& code) -> bool {
        code = 0;
        for (int i = 0; i < width; ++i) {
            const std::size_t b = bit++;
            if (b / 8 >= srcLen) return false;
            code = (code << 1) | ((src[b / 8] >> (7 - (b % 8))) & 1);
        }
        return true;
    };
    // Prefix/suffix table: entry e>255 = chain(prefix[e]) + suffix[e];
    // entries 0..255 are literal bytes. Canonical LZW linking.
    std::vector<int> prefix(4096, -1);
    std::vector<std::uint8_t> suffix(4096, 0);
    auto lookup = [&](std::uint32_t code,
                       std::vector<std::uint8_t>& s) -> bool {
        s.clear();
        if (code >= 4096) return false;
        if (code < 256) {
            s.push_back(static_cast<std::uint8_t>(code));
            return true;
        }
        std::vector<std::uint8_t> rev;
        std::uint32_t e = code;
        while (e > 255) {
            if (e >= 4096 || prefix[e] < 0) return false;
            rev.push_back(suffix[e]);
            e = static_cast<std::uint32_t>(prefix[e]);
        }
        s.push_back(static_cast<std::uint8_t>(e));
        for (std::size_t i = rev.size(); i > 0; --i) s.push_back(rev[i - 1]);
        return true;
    };
    std::uint32_t next = 258;
    int width = 9;
    std::vector<std::uint8_t> prev;
    std::uint32_t prevCode = 0;
    bool havePrev = false;
    std::uint32_t code = 0;
    if (!readCode(width, code) || code != 256) {
        error = "tiff: LZW stream must start with Clear";
        return false;
    }
    for (;;) {
        if (!readCode(width, code)) {
            error = "tiff: truncated LZW stream";
            return false;
        }
        if (code == 257) return true;  // EOI
        if (code == 256) {             // mid-stream reset
            next = 258;
            width = 9;
            havePrev = false;
            continue;
        }
        std::vector<std::uint8_t> cur;
        std::uint32_t curCode = code;
        if (havePrev && code == next) {
            // KwKwK case: prev + prev[0]; the new entry IS this code.
            if (prev.empty()) {
                error = "tiff: bad LZW code";
                return false;
            }
            cur = prev;
            cur.push_back(prev[0]);
        } else {
            if (code >= next) {
                error = "tiff: bad LZW code";
                return false;
            }
            if (!lookup(code, cur)) {
                error = "tiff: bad LZW table link";
                return false;
            }
        }
        out.insert(out.end(), cur.begin(), cur.end());
        if (havePrev) {
            if (next >= 4096) {
                error = "tiff: LZW table overflow (encoder never resets)";
                return false;
            }
            prefix[next] = static_cast<int>(prevCode);
            suffix[next] = cur[0];
            curCode = (code == next) ? next : code;
            ++next;
            if (next == (1u << width) && width < 12) ++width;
        }
        prev = cur;
        prevCode = curCode;
        havePrev = true;
    }
}

}  // namespace

bool loadTiff(Canvas& c, const std::string& path, std::string& error) {
    std::vector<std::uint8_t> d;
    if (!readFile(path, d, error)) return false;
    if (d.size() < 8) {
        error = "tiff: truncated header";
        return false;
    }
    Cursor r;
    r.d = d.data();
    r.n = d.size();
    if (d[0] == 'I' && d[1] == 'I')
        r.le = true;
    else if (d[0] == 'M' && d[1] == 'M')
        r.le = false;
    else {
        error = "tiff: not a TIFF file";
        return false;
    }
    if (r.u16(2) != 42) {
        error = "tiff: bad magic (not Classic TIFF)";
        return false;
    }
    const std::uint32_t ifd = r.u32(4);
    if (!r.range(ifd, 2)) {
        error = "tiff: IFD out of range";
        return false;
    }
    const std::uint16_t count = r.u16(ifd);
    if (!r.range(ifd + 2, static_cast<std::size_t>(count) * 12)) {
        error = "tiff: truncated IFD";
        return false;
    }
    // Tag values (with TIFF defaults).
    std::uint32_t width = 0, height = 0, compression = 1, photo = 0;
    std::uint32_t spp = 0, rowsPerStrip = 0xFFFFFFFFu, planar = 1;
    std::uint32_t predictor = 1;
    std::vector<std::uint32_t> bits, offsets, byteCounts;
    bool hasTile = false, hasPredictor = false;
    auto readVals = [&](std::uint16_t type, std::uint32_t cnt,
                        std::uint32_t val, std::vector<std::uint32_t>& v) {
        std::size_t sz = 0;
        if (type == 3)
            sz = 2;
        else if (type == 4)
            sz = 4;
        else if (type == 1)
            sz = 1;
        else
            return false;
        const std::size_t total = static_cast<std::size_t>(cnt) * sz;
        if (total <= 4) {
            for (std::uint32_t i = 0; i < cnt; ++i) {
                std::uint32_t x = 0;
                if (type == 3)
                    x = r.le ? ((val >> (i * 16)) & 0xFFFF)
                             : ((val >> ((1 - i) * 16)) & 0xFFFF);
                else if (type == 4)
                    x = val;
                else
                    x = (val >> (i * 8)) & 0xFF;
                v.push_back(x);
            }
            return true;
        }
        if (!r.range(val, total)) return false;
        for (std::uint32_t i = 0; i < cnt; ++i) {
            if (type == 3)
                v.push_back(r.u16(val + i * 2));
            else if (type == 4)
                v.push_back(r.u32(val + i * 4));
            else
                v.push_back(d[val + i]);
        }
        return true;
    };
    for (std::uint16_t i = 0; i < count; ++i) {
        const std::size_t o = ifd + 2 + static_cast<std::size_t>(i) * 12;
        const std::uint16_t tag = r.u16(o);
        const std::uint16_t type = r.u16(o + 2);
        const std::uint32_t cnt = r.u32(o + 4);
        const std::uint32_t val = r.u32(o + 8);
        if (tag == 322 || tag == 323) hasTile = true;
        std::vector<std::uint32_t> v;
        if (tag == 256 || tag == 257 || tag == 259 || tag == 262 ||
            tag == 277 || tag == 278 || tag == 284 || tag == 317) {
            if (!readVals(type, cnt, val, v) || v.empty()) {
                error = "tiff: malformed tag";
                return false;
            }
            const std::uint32_t x = v[0];
            if (tag == 256) width = x;
            if (tag == 257) height = x;
            if (tag == 259) compression = x;
            if (tag == 262) photo = x;
            if (tag == 277) spp = x;
            if (tag == 278) rowsPerStrip = x;
            if (tag == 284) planar = x;
            if (tag == 317) {
                predictor = x;
                hasPredictor = true;
            }
        } else if (tag == 258 || tag == 273 || tag == 279) {
            if (!readVals(type, cnt, val, v) || v.empty()) {
                error = "tiff: malformed tag";
                return false;
            }
            if (tag == 258) bits = v;
            if (tag == 273) offsets = v;
            if (tag == 279) byteCounts = v;
        }
    }
    if (width < 1 || height < 1 || width > kMaxSide || height > kMaxSide) {
        error = "tiff: dimensions out of range";
        return false;
    }
    if (spp != 3 && spp != 4) {
        error = "tiff: only RGB/RGBA import supported";
        return false;
    }
    if (photo != 2) {
        error = photo == 5 ? "tiff: CMYK import unsupported (RGB only)"
                           : "tiff: only RGB photometric supported";
        return false;
    }
    if (compression != 1 && compression != 5) {
        error = compression == 7
                    ? "tiff: JPEG-in-TIFF unsupported (export PNG instead)"
                    : "tiff: compression not supported (uncompressed/LZW only)";
        return false;
    }
    if (bits.size() != spp) {
        error = "tiff: BitsPerPixel must list one 8-bit entry per sample";
        return false;
    }
    for (std::uint32_t b : bits)
        if (b != 8) {
            error = "tiff: only 8-bit samples supported";
            return false;
        }
    if (planar != 1) {
        error = "tiff: only planar-1 (chunky) supported";
        return false;
    }
    if (hasTile) {
        error = "tiff: tiled TIFF unsupported (strips only)";
        return false;
    }
    if (hasPredictor && predictor != 1) {
        error = "tiff: predictors unsupported";
        return false;
    }
    if (offsets.empty() || byteCounts.empty()) {
        error = "tiff: missing strip offsets/counts";
        return false;
    }
    const std::uint32_t rows = rowsPerStrip == 0 ? 1 : rowsPerStrip;
    const std::size_t nStrips =
        (static_cast<std::size_t>(height) + rows - 1) / rows;
    if ((offsets.size() != 1 && offsets.size() != nStrips) ||
        (byteCounts.size() != 1 && byteCounts.size() != nStrips)) {
        error = "tiff: strip count mismatch";
        return false;
    }
    // Decode strips in order (FIRST IFD = page 1 only).
    std::vector<std::uint8_t> raw;
    raw.reserve(static_cast<std::size_t>(width) * height * spp);
    for (std::size_t s = 0; s < nStrips; ++s) {
        const std::uint32_t off =
            offsets.size() == 1 ? offsets[0] : offsets[s];
        const std::uint32_t bc =
            byteCounts.size() == 1 ? byteCounts[0] : byteCounts[s];
        const std::uint32_t y0 = static_cast<std::uint32_t>(s * rows);
        const std::uint32_t y1 =
            std::min<std::uint32_t>(y0 + rows, height);
        const std::size_t expect =
            static_cast<std::size_t>(y1 - y0) * width * spp;
        if (!r.range(off, bc)) {
            error = "tiff: strip out of range";
            return false;
        }
        if (compression == 1) {
            if (bc != expect) {
                error = "tiff: strip byte-count mismatch";
                return false;
            }
            raw.insert(raw.end(), d.begin() + off, d.begin() + off + bc);
        } else {
            std::vector<std::uint8_t> dec;
            if (!lzwDecode(d.data() + off, bc, dec, error)) return false;
            if (dec.size() != expect) {
                error = "tiff: LZW strip decoded size mismatch";
                return false;
            }
            raw.insert(raw.end(), dec.begin(), dec.end());
        }
    }
    Canvas tmp(static_cast<int>(width), static_cast<int>(height));
    Layer bg;
    bg.name = "Layer 1";
    bg.surface.resize(tmp.width(), tmp.height());
    for (std::uint32_t y = 0; y < height; ++y) {
        for (std::uint32_t x = 0; x < width; ++x) {
            const std::size_t i =
                (static_cast<std::size_t>(y) * width + x) * spp;
            Pixel p;
            p.r = raw[i];
            p.g = raw[i + 1];
            p.b = raw[i + 2];
            p.a = (spp == 4) ? raw[i + 3] : 255;
            if (p.r > p.a) p.r = p.a;
            if (p.g > p.a) p.g = p.a;
            if (p.b > p.a) p.b = p.a;
            bg.surface.set(static_cast<int>(x), static_cast<int>(y), p);
        }
    }
    tmp.adoptLayer(std::move(bg));
    tmp.setActiveLayer(0);
    c = std::move(tmp);
    return true;
}

}  // namespace openclip
