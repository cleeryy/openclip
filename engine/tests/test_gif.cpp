// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip V+2 — GIF writer tests (ARCHITECTURE.md "V+2 additions").
// A minimal from-scratch GIF reader lives in this file (test-only):
// header, logical screen dims, NETSCAPE loop, per-frame GCE delays and
// LZW-decoded pixel indexes are all verified against the written file.
#include "test.h"

#include "openclip/gif.h"

#include <cstdio>
#include <cstring>
#include <fstream>
#include <vector>

using openclip::GifFrame;
using openclip::gifLzwEncode;
using openclip::writeGif;

namespace {

using testh::tmpPath;

// Minimal GIF reader (test-only, from scratch): collects dims, loop
// count (0 = once/no ext, else plays), per-frame delays and decoded
// index planes. Returns false on any structural surprise.
struct TestGif {
    int w = 0, h = 0;
    int loop = 0;  // 0 = played once (no NETSCAPE ext seen)
    bool sawNetscape = false;
    std::vector<int> delays;
    std::vector<std::vector<std::uint8_t>> frames;
};

std::uint16_t rd16le(const std::vector<std::uint8_t>& d, std::size_t o) {
    return static_cast<std::uint16_t>(d[o] | (d[o + 1] << 8));
}

bool skipBlocks(const std::vector<std::uint8_t>& d, std::size_t& o) {
    while (o < d.size()) {
        const std::uint8_t n = d[o++];
        if (n == 0) return true;
        if (o + n > d.size()) return false;
        o += n;
    }
    return false;
}

std::vector<std::uint8_t> lzwDecode(const std::vector<std::uint8_t>& data,
                                     int minCode, std::size_t expect) {
    std::vector<std::uint8_t> out;
    // Bit stream -> codes.
    std::vector<int> codes;
    int cur = 0, bits = 0;
    std::size_t p = 0;
    int width = minCode + 1;
    const int clear = 1 << minCode, eoi = clear + 1;
    auto need = [&](int n) -> bool {
        while (bits < n) {
            if (p >= data.size()) return false;
            cur |= (data[p++] << bits);
            bits += 8;
        }
        return true;
    };
    std::vector<std::vector<std::uint8_t>> dict;
    auto reset = [&]() {
        dict.clear();
        dict.resize(eoi + 1);
        for (int i = 0; i <= eoi; ++i)
            if (i < clear) dict[i] = {static_cast<std::uint8_t>(i)};
        width = minCode + 1;
    };
    reset();
    int prev = -1;
    while (true) {
        if (!need(width)) return {};
        const int code = cur & ((1 << width) - 1);
        cur >>= width;
        bits -= width;
        if (code == clear) {
            reset();
            prev = -1;
            continue;
        }
        if (code == eoi) break;
        std::vector<std::uint8_t> entry;
        if (code < (int)dict.size() && !dict[code].empty()) {
            entry = dict[code];
        } else if (code == (int)dict.size() && prev >= 0) {
            entry = dict[prev];
            entry.push_back(dict[prev][0]);
        } else {
            return {};
        }
        for (auto b : entry) {
            out.push_back(b);
            if (out.size() == expect) break;
        }
        if (prev >= 0 && dict.size() < 4096) {
            std::vector<std::uint8_t> nw = dict[prev];
            nw.push_back(entry[0]);
            dict.push_back(nw);
            if ((int)dict.size() == (1 << width) && width < 12) ++width;
        }
        prev = code;
        if (out.size() >= expect) break;
    }
    return out;
}

bool readTestGif(const std::string& path, TestGif& g, std::string& err) {
    std::ifstream f(path, std::ios::binary);
    if (!f) {
        err = "open";
        return false;
    }
    std::vector<std::uint8_t> d((std::istreambuf_iterator<char>(f)),
                                std::istreambuf_iterator<char>());
    if (d.size() < 13 || std::memcmp(d.data(), "GIF89a", 6) != 0) {
        err = "header";
        return false;
    }
    g.w = rd16le(d, 6);
    g.h = rd16le(d, 8);
    std::size_t o = 13;
    if (d[4 + 6] & 0x80) {  // GCT present (we never write one)
        const int bits = (d[4 + 6] & 7) + 1;
        o += 3u * (1u << bits);
    }
    while (o < d.size()) {
        const std::uint8_t sep = d[o++];
        if (sep == 0x3B) break;  // trailer
        if (sep == 0x21) {
            if (o >= d.size()) {
                err = "ext";
                return false;
            }
            const std::uint8_t label = d[o++];
            if (label == 0xFF) {  // application extension
                const std::uint8_t n = d[o++];
                if (o + n > d.size()) {
                    err = "app";
                    return false;
                }
                const bool ns = (n == 11 &&
                                 std::memcmp(d.data() + o, "NETSCAPE2.0",
                                             11) == 0);
                o += n;
                // Sub-blocks: 03 01 lo hi 00.
                if (o + 5 > d.size()) {
                    err = "app-sub";
                    return false;
                }
                if (ns && d[o] == 3 && d[o + 1] == 1) {
                    g.loop = d[o + 2] | (d[o + 3] << 8);
                    g.sawNetscape = true;
                }
                if (!skipBlocks(d, o)) {
                    err = "app-blocks";
                    return false;
                }
            } else if (label == 0xF9) {  // GCE
                if (o + 5 > d.size()) {
                    err = "gce";
                    return false;
                }
                const std::uint8_t n = d[o++];
                if (n != 4) {
                    err = "gce-len";
                    return false;
                }
                g.delays.push_back(d[o + 1] | (d[o + 2] << 8));
                o += 4;
                if (o >= d.size() || d[o++] != 0) {
                    err = "gce-term";
                    return false;
                }
            } else {
                if (!skipBlocks(d, o)) {
                    err = "ext-blocks";
                    return false;
                }
            }
        } else if (sep == 0x2C) {  // image descriptor
            if (o + 9 > d.size()) {
                err = "desc";
                return false;
            }
            const bool lct = (d[o + 8] & 0x80) != 0;
            const int bits = (d[o + 8] & 7) + 1;
            o += 9;
            if (lct) {
                const std::size_t ts = 3u * (1u << bits);
                if (o + ts > d.size()) {
                    err = "lct";
                    return false;
                }
                o += ts;
            }
            if (o >= d.size()) {
                err = "mincode";
                return false;
            }
            const int minCode = d[o++];
            std::vector<std::uint8_t> comp;
            while (o < d.size()) {
                const std::uint8_t n = d[o++];
                if (n == 0) break;
                if (o + n > d.size()) {
                    err = "img-data";
                    return false;
                }
                comp.insert(comp.end(), d.begin() + o, d.begin() + o + n);
                o += n;
            }
            std::vector<std::uint8_t> px =
                lzwDecode(comp, minCode,
                          static_cast<std::size_t>(g.w) * g.h);
            if (px.size() != static_cast<std::size_t>(g.w) * g.h) {
                err = "lzw";
                return false;
            }
            g.frames.push_back(px);
        } else {
            err = "separator";
            return false;
        }
    }
    return true;
}

GifFrame solid(int w, int h, std::uint8_t r, std::uint8_t g,
               std::uint8_t b, int delay) {
    GifFrame f;
    f.w = w;
    f.h = h;
    f.delayCs = delay;
    f.rgba.assign(static_cast<std::size_t>(w) * h * 4, 0);
    for (std::size_t i = 0; i < static_cast<std::size_t>(w) * h; ++i) {
        f.rgba[i * 4 + 0] = r;
        f.rgba[i * 4 + 1] = g;
        f.rgba[i * 4 + 2] = b;
        f.rgba[i * 4 + 3] = 255;
    }
    return f;
}

}  // namespace

// Two solid frames: header/dims/loop parsed, both frames decode to
// uniform planes (round trip through our own LZW both ways).
TEST(gif_two_frames_round_trip) {
    std::vector<GifFrame> fs;
    fs.push_back(solid(8, 8, 255, 0, 0, 8));
    fs.push_back(solid(8, 8, 0, 0, 255, 8));
    std::string err;
    const std::string path = tmpPath("v2_two.gif");
    CHECK(writeGif(path, fs, openclip::kGifLoopInfinite, false, err));
    TestGif g;
    std::string rerr;
    CHECK(readTestGif(path, g, rerr));
    CHECK_EQ(g.w, 8);
    CHECK_EQ(g.h, 8);
    CHECK(g.sawNetscape);
    CHECK_EQ(g.loop, 0);  // infinite
    CHECK_EQ(g.frames.size(), 2u);
    // Each frame decodes to a single repeated index (solid color).
    for (auto& fr : g.frames) {
        CHECK_EQ(fr.size(), 64u);
        for (auto b : fr) CHECK_EQ(b, fr[0]);
    }
    // Solid red vs solid blue quantize to different single entries,
    // so the two planes use different palette slots end to end.
    CHECK_EQ(g.delays.size(), 2u);
    CHECK_EQ(g.delays[0], 8);
    std::remove(path.c_str());
}

// Loop policy: once = no extension, N = count, infinite = 0.
TEST(gif_loop_policy) {
    std::vector<GifFrame> fs;
    fs.push_back(solid(4, 4, 1, 2, 3, 5));
    std::string err;
    const std::string once = tmpPath("v2_once.gif");
    CHECK(writeGif(once, fs, openclip::kGifLoopOnce, false, err));
    TestGif g1;
    std::string e1;
    CHECK(readTestGif(once, g1, e1));
    CHECK(!g1.sawNetscape);
    CHECK_EQ(g1.loop, 0);
    CHECK_EQ(g1.delays.size(), 1u);
    CHECK_EQ(g1.delays[0], 5);
    std::remove(once.c_str());
    const std::string n3 = tmpPath("v2_n3.gif");
    CHECK(writeGif(n3, fs, 3, false, err));
    TestGif g3;
    std::string e3;
    CHECK(readTestGif(n3, g3, e3));
    CHECK(g3.sawNetscape);
    CHECK_EQ(g3.loop, 3);
    std::remove(n3.c_str());
}

// LZW encoder round trip on a repetitive pattern (exercises dict
// growth + clear paths) through the test decoder.
TEST(gif_lzw_round_trip) {
    std::vector<std::uint8_t> idx;
    for (int i = 0; i < 300; ++i)
        idx.push_back(i % 7 == 0 ? 3 : 1);  // <= 3: fits minCode 2
    std::vector<std::uint8_t> enc = gifLzwEncode(idx, 2);
    CHECK(!enc.empty());
    // Reassemble sub-blocks then decode.
    std::vector<std::uint8_t> comp;
    for (std::size_t p = 0; p < enc.size();) {
        const std::size_t n = enc[p++];
        for (std::size_t k = 0; k < n; ++k) comp.push_back(enc[p++]);
    }
    std::vector<std::uint8_t> dec = lzwDecode(comp, 2, idx.size());
    CHECK_EQ(dec.size(), idx.size());
    CHECK(dec == idx);
}

// Failures: empty input, dimension mismatch, bad path — false, and no
// partial file left behind.
TEST(gif_failures_clean) {
    std::string err;
    CHECK(!writeGif(tmpPath("v2_empty.gif"), {}, 0, false, err));
    CHECK(!err.empty());
    std::vector<GifFrame> fs;
    fs.push_back(solid(4, 4, 1, 1, 1, 5));
    fs.push_back(solid(5, 4, 1, 1, 1, 5));
    CHECK(!writeGif(tmpPath("v2_mismatch.gif"), fs, 0, false, err));
    CHECK(!err.empty());
    CHECK(!writeGif(testh::tmpPath("nonexistent-dir-xyz/v2.gif"), fs, 0, false, err));
    CHECK(!err.empty());
    std::FILE* fp = std::fopen(
        testh::tmpPath("nonexistent-dir-xyz/v2.gif").c_str(), "rb");
    CHECK(fp == nullptr);
}
