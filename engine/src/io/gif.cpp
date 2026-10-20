// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip V+2 — GIF89a writer implementation (clean-room).
#include "openclip/gif.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <map>
#include <utility>

namespace openclip {
namespace {

// Ordered Bayer 8x8 (0..63), centered for dither offsets.
const int kBayer8[8][8] = {
    {0, 32, 8, 40, 2, 34, 10, 42},   {48, 16, 56, 24, 50, 18, 58, 26},
    {12, 44, 4, 36, 14, 46, 6, 38},  {60, 28, 52, 20, 62, 30, 54, 22},
    {3, 35, 11, 43, 1, 33, 9, 41},   {51, 19, 59, 27, 49, 17, 57, 25},
    {15, 47, 7, 39, 13, 45, 5, 37},  {63, 31, 55, 23, 61, 29, 53, 21},
};

struct Box {
    std::vector<std::size_t> idx;  // indexes into the color list
};

}  // namespace

void gifQuantize(const std::vector<std::uint8_t>& rgba, int w, int h,
                 bool dither, std::vector<std::uint8_t>& palette,
                 std::vector<std::uint8_t>& indexes, bool& hasTransparent) {
    palette.clear();
    indexes.clear();
    hasTransparent = false;
    if (w <= 0 || h <= 0 ||
        rgba.size() != static_cast<std::size_t>(w) * h * 4)
        return;
    const std::size_t n = static_cast<std::size_t>(w) * h;
    hasTransparent = false;
    for (std::size_t i = 0; i < n; ++i)
        if (rgba[i * 4 + 3] < 128) {
            hasTransparent = true;
            break;
        }
    const std::size_t reserveT = hasTransparent ? 1 : 0;
    // Opaque color list (straight RGB).
    std::vector<std::size_t> opaque;
    opaque.reserve(n);
    for (std::size_t i = 0; i < n; ++i)
        if (rgba[i * 4 + 3] >= 128) opaque.push_back(i);
    std::vector<std::array<std::uint8_t, 3>> pal;
    if (!opaque.empty()) {
        std::vector<Box> boxes;
        boxes.push_back({opaque});
        // Split until 255 boxes (one slot kept for transparency) or no
        // splittable box left.
        while (boxes.size() < 255 - reserveT) {
            std::size_t pick = boxes.size();
            int bestRange = 0;
            for (std::size_t b = 0; b < boxes.size(); ++b) {
                if (boxes[b].idx.size() < 2) continue;
                int mn[3] = {255, 255, 255}, mx[3] = {0, 0, 0};
                for (std::size_t pi : boxes[b].idx) {
                    for (int c = 0; c < 3; ++c) {
                        const int v = rgba[pi * 4 + c];
                        if (v < mn[c]) mn[c] = v;
                        if (v > mx[c]) mx[c] = v;
                    }
                }
                int range = 0;
                for (int c = 0; c < 3; ++c)
                    range = std::max(range, mx[c] - mn[c]);
                if (range > bestRange) {
                    bestRange = range;
                    pick = b;
                }
            }
            if (pick == boxes.size() || bestRange == 0) break;
            // Longest channel of the picked box; sort + split at median.
            int mn[3] = {255, 255, 255}, mx[3] = {0, 0, 0};
            for (std::size_t pi : boxes[pick].idx) {
                for (int c = 0; c < 3; ++c) {
                    const int v = rgba[pi * 4 + c];
                    if (v < mn[c]) mn[c] = v;
                    if (v > mx[c]) mx[c] = v;
                }
            }
            int ch = 0;
            for (int c = 1; c < 3; ++c)
                if (mx[c] - mn[c] > mx[ch] - mn[ch]) ch = c;
            Box& src = boxes[pick];
            std::sort(src.idx.begin(), src.idx.end(),
                      [&](std::size_t a, std::size_t b) {
                          return rgba[a * 4 + ch] < rgba[b * 4 + ch];
                      });
            Box nb;
            const std::size_t half = src.idx.size() / 2;
            nb.idx.assign(src.idx.begin() + half, src.idx.end());
            src.idx.erase(src.idx.begin() + half, src.idx.end());
            boxes.push_back(std::move(nb));
        }
        for (auto& b : boxes) {
            long sr = 0, sg = 0, sb = 0;
            for (std::size_t pi : b.idx) {
                sr += rgba[pi * 4 + 0];
                sg += rgba[pi * 4 + 1];
                sb += rgba[pi * 4 + 2];
            }
            const long m = static_cast<long>(b.idx.size());
            pal.push_back({static_cast<std::uint8_t>((sr + m / 2) / m),
                           static_cast<std::uint8_t>((sg + m / 2) / m),
                           static_cast<std::uint8_t>((sb + m / 2) / m)});
        }
    }
    const std::uint8_t tIndex = static_cast<std::uint8_t>(pal.size());
    // Map pixels to nearest palette entry (Bayer offset when dithering).
    indexes.assign(n, 0);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const std::size_t i = static_cast<std::size_t>(y) * w + x;
            if (rgba[i * 4 + 3] < 128) {
                indexes[i] = tIndex;  // transparent slot (== size when
                                      // none reserved... see below)
                continue;
            }
            int r = rgba[i * 4 + 0], g = rgba[i * 4 + 1],
                b = rgba[i * 4 + 2];
            if (dither && !pal.empty()) {
                const int off = (kBayer8[y & 7][x & 7] - 32) * 2;
                r = std::max(0, std::min(255, r + off));
                g = std::max(0, std::min(255, g + off));
                b = std::max(0, std::min(255, b + off));
            }
            int best = 0, bestD = 1 << 30;
            for (std::size_t p = 0; p < pal.size(); ++p) {
                const int dr = r - pal[p][0], dg = g - pal[p][1],
                          db = b - pal[p][2];
                const int d = dr * dr + dg * dg + db * db;
                if (d < bestD) {
                    bestD = d;
                    best = static_cast<int>(p);
                }
            }
            indexes[i] = static_cast<std::uint8_t>(best);
        }
    }
    for (auto& c : pal) {
        palette.push_back(c[0]);
        palette.push_back(c[1]);
        palette.push_back(c[2]);
    }
    if (hasTransparent) {
        // Transparent slot = black entry appended (never matched by
        // opaque pixels: mapping above only yields < pal.size()).
        palette.push_back(0);
        palette.push_back(0);
        palette.push_back(0);
    }
}

std::vector<std::uint8_t> gifLzwEncode(const std::vector<std::uint8_t>& idx,
                                       int minCodeSize) {
    std::vector<std::uint8_t> out;
    if (minCodeSize < 2) minCodeSize = 2;
    if (minCodeSize > 8) minCodeSize = 8;
    // The code space must fit the largest index (else an index would
    // alias clear/eoi): bump the code size defensively.
    int hi = 0;
    for (auto v : idx) hi = std::max(hi, (int)v);
    while (minCodeSize < 8 && hi >= (1 << minCodeSize)) ++minCodeSize;
    const int clear = 1 << minCodeSize, eoi = clear + 1;
    int codeSize = minCodeSize + 1;
    int next = eoi + 1;
    // (prefix << 8) | byte -> code.
    std::map<int, int> dict;
    std::vector<std::uint8_t> bytes;  // packed bit stream
    int cur = 0, bits = 0;
    auto emit = [&](int code) {
        cur |= (code << bits);
        bits += codeSize;
        while (bits >= 8) {
            bytes.push_back(static_cast<std::uint8_t>(cur & 0xff));
            cur >>= 8;
            bits -= 8;
        }
    };
    emit(clear);
    if (idx.empty()) {
        emit(eoi);
    } else {
        int prefix = idx[0];
        for (std::size_t i = 1; i < idx.size(); ++i) {
            const int key = (prefix << 8) | idx[i];
            auto it = dict.find(key);
            if (it != dict.end()) {
                prefix = it->second;
            } else {
                emit(prefix);
                if (next < 4096) {
                    dict[key] = next++;
                    // Bump the code width one entry LATER than the
                    // decoder side (next == 2^w + 1): the decoder adds
                    // each table entry one code after the encoder, so
                    // an immediate bump would desynchronize the two
                    // (standard GIF timing, verified against stb/PIL).
                    if (next == (1 << codeSize) + 1 && codeSize < 12)
                        ++codeSize;
                } else {
                    // Table full: clear and restart the dictionary
                    // (GIF-legal); the current byte starts the new run.
                    emit(clear);
                    dict.clear();
                    codeSize = minCodeSize + 1;
                    next = eoi + 1;
                }
                prefix = idx[i];
            }
        }
        emit(prefix);
        emit(eoi);
    }
    if (bits > 0) bytes.push_back(static_cast<std::uint8_t>(cur & 0xff));
    // Sub-block framing (data sub-blocks, no terminator — the caller
    // adds framing per image data section).
    for (std::size_t p = 0; p < bytes.size();) {
        const std::size_t take = std::min<std::size_t>(255, bytes.size() - p);
        out.push_back(static_cast<std::uint8_t>(take));
        out.insert(out.end(), bytes.begin() + p, bytes.begin() + p + take);
        p += take;
    }
    return out;
}

namespace {

void wr16(std::vector<std::uint8_t>& o, std::uint16_t v) {
    o.push_back(v & 0xff);
    o.push_back((v >> 8) & 0xff);
}

int tableBits(std::size_t colors) {
    if (colors <= 2) return 1;
    if (colors <= 4) return 2;
    if (colors <= 16) return 4;
    return 8;
}

}  // namespace

bool writeGif(const std::string& path, const std::vector<GifFrame>& frames,
              int loop, bool dither, std::string& error) {
    if (frames.empty()) {
        error = "gif: no frames";
        return false;
    }
    const int w = frames[0].w, h = frames[0].h;
    if (w <= 0 || h <= 0 || w > 4096 || h > 4096) {
        error = "gif: bad dimensions";
        return false;
    }
    for (auto& f : frames) {
        if (f.w != w || f.h != h ||
            f.rgba.size() != static_cast<std::size_t>(w) * h * 4) {
            error = "gif: frame dimension mismatch";
            return false;
        }
    }
    std::vector<std::uint8_t> out;
    out.insert(out.end(), {'G', 'I', 'F', '8', '9', 'a'});
    wr16(out, static_cast<std::uint16_t>(w));
    wr16(out, static_cast<std::uint16_t>(h));
    out.push_back(0x70);  // no GCT, color resolution 8-bit
    out.push_back(0);     // background
    out.push_back(0);     // aspect
    if (loop != kGifLoopOnce) {
        // NETSCAPE2.0: 0 = infinite, N > 1 = N plays.
        const int count = (loop == kGifLoopInfinite) ? 0 : loop;
        if (count < 0) {
            error = "gif: bad loop value";
            return false;
        }
        out.push_back(0x21);
        out.push_back(0xFF);
        out.push_back(11);
        const char ns[] = "NETSCAPE2.0";
        out.insert(out.end(), ns, ns + 11);
        out.push_back(3);
        out.push_back(1);
        wr16(out, static_cast<std::uint16_t>(count));
        out.push_back(0);
    }
    for (auto& f : frames) {
        std::vector<std::uint8_t> palette, indexes;
        bool hasT = false;
        gifQuantize(f.rgba, w, h, dither, palette, indexes, hasT);
        const std::size_t colors = palette.size() / 3;
        if (colors < 1 || colors > 256 || indexes.empty()) {
            error = "gif: quantize failed";
            return false;
        }
        const int bits = tableBits(colors);
        const std::size_t tableSize = static_cast<std::size_t>(1) << bits;
        const int delay = f.delayCs < 0 ? 0 : f.delayCs;
        // Graphic Control Extension (disposal 2 = restore background so
        // transparent pixels never smear the previous frame).
        out.push_back(0x21);
        out.push_back(0xF9);
        out.push_back(4);
        out.push_back(static_cast<std::uint8_t>(hasT ? 0x09 : 0x08));
        wr16(out, static_cast<std::uint16_t>(delay));
        out.push_back(
            hasT ? static_cast<std::uint8_t>(colors - 1) : 0);
        out.push_back(0);
        // Image Descriptor with a LOCAL color table.
        out.push_back(0x2C);
        wr16(out, 0);
        wr16(out, 0);
        wr16(out, static_cast<std::uint16_t>(w));
        wr16(out, static_cast<std::uint16_t>(h));
        out.push_back(static_cast<std::uint8_t>(0x80 | (bits - 1)));
        for (std::size_t c = 0; c < tableSize * 3; ++c)
            out.push_back(c < palette.size() ? palette[c] : 0);
        const int minCode = bits <= 1 ? 2 : bits;
        out.push_back(static_cast<std::uint8_t>(minCode));
        std::vector<std::uint8_t> lzw = gifLzwEncode(indexes, minCode);
        out.insert(out.end(), lzw.begin(), lzw.end());
        out.push_back(0);  // block terminator
    }
    out.push_back(0x3B);  // trailer
    std::FILE* fp = std::fopen(path.c_str(), "wb");
    if (!fp) {
        error = "gif: cannot write " + path;
        return false;
    }
    const std::size_t wrote =
        std::fwrite(out.data(), 1, out.size(), fp);
    std::fclose(fp);
    if (wrote != out.size()) {
        std::remove(path.c_str());
        error = "gif: short write on " + path;
        return false;
    }
    return true;
}

}  // namespace openclip
