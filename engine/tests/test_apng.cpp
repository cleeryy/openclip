// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip T3 — APNG writer + PNG sequence plan/write
// (ARCHITECTURE.md "T3 additions" §4).
#include "test.h"

#include <cstdio>
#include <vector>

#include "openclip/apng.h"
#include "openclip/cels.h"
#include "openclip/surface.h"

using openclip::ApngFrame;
using openclip::flattenMatte;
using openclip::planPngSeq;
using openclip::writeApng;
using openclip::writePngSeq;

namespace {

// Straight red/green frames for writer tests.
ApngFrame solidFrame(int w, int h, std::uint8_t r, std::uint8_t g,
                     std::uint8_t b, int delayCs) {
    ApngFrame f;
    f.w = w;
    f.h = h;
    f.delayCs = delayCs;
    f.rgba.assign(static_cast<std::size_t>(w) * h * 4, 0);
    for (std::size_t i = 0; i < static_cast<std::size_t>(w) * h; ++i) {
        f.rgba[i * 4 + 0] = r;
        f.rgba[i * 4 + 1] = g;
        f.rgba[i * 4 + 2] = b;
        f.rgba[i * 4 + 3] = 255;
    }
    return f;
}

std::vector<std::uint8_t> readFile(const std::string& p) {
    std::vector<std::uint8_t> o;
    FILE* fp = std::fopen(p.c_str(), "rb");
    if (!fp) return o;
    std::fseek(fp, 0, SEEK_END);
    const long n = std::ftell(fp);
    std::fseek(fp, 0, SEEK_SET);
    o.assign(static_cast<std::size_t>(n), 0);
    if (n > 0)
        std::fread(o.data(), 1, static_cast<std::size_t>(n), fp);
    std::fclose(fp);
    return o;
}

bool hasChunk(const std::vector<std::uint8_t>& png, const char t[4]) {
    // Scan chunk types after the 8-byte signature.
    std::size_t p = 8;
    while (p + 8 <= png.size()) {
        const std::uint32_t n = (png[p] << 24) | (png[p + 1] << 16) |
                                (png[p + 2] << 8) | png[p + 3];
        if (png[p + 4] == (std::uint8_t)t[0] &&
            png[p + 5] == (std::uint8_t)t[1] &&
            png[p + 6] == (std::uint8_t)t[2] &&
            png[p + 7] == (std::uint8_t)t[3])
            return true;
        p += 12 + n;
        if (n > png.size()) break;
    }
    return false;
}

int countChunk(const std::vector<std::uint8_t>& png, const char t[4]) {
    int n = 0;
    std::size_t p = 8;
    while (p + 8 <= png.size()) {
        const std::uint32_t len = (png[p] << 24) | (png[p + 1] << 16) |
                                  (png[p + 2] << 8) | png[p + 3];
        if (png[p + 4] == (std::uint8_t)t[0] &&
            png[p + 5] == (std::uint8_t)t[1] &&
            png[p + 6] == (std::uint8_t)t[2] &&
            png[p + 7] == (std::uint8_t)t[3])
            ++n;
        p += 12 + len;
        if (len > png.size()) break;
    }
    return n;
}

}  // namespace

// §4: PNG signature + acTL present, loop + frame count round trip.
TEST(apngMagicAndActl) {
    const std::string p = testh::tmpPath("apng_t3_test.png");
    std::vector<ApngFrame> fs;
    fs.push_back(solidFrame(16, 16, 255, 0, 0, 8));
    fs.push_back(solidFrame(16, 16, 0, 255, 0, 25));
    fs.push_back(solidFrame(16, 16, 0, 0, 255, 8));
    std::string err;
    CHECK(writeApng(p, fs, -1, err));
    const auto bytes = readFile(p);
    CHECK(bytes.size() > 8);
    CHECK(bytes[0] == 137 && bytes[1] == 80 && bytes[2] == 78 &&
          bytes[3] == 71);
    CHECK(hasChunk(bytes, "acTL"));
    CHECK_EQ(countChunk(bytes, "fcTL"), 3);
    CHECK_EQ(countChunk(bytes, "fdAT"), 2);  // first frame = IDAT
    CHECK(hasChunk(bytes, "IDAT"));
    std::remove(p.c_str());
}

// §4: delays follow holds (celDelayCs ticks land in fcTL).
TEST(apngDelaysFollowHolds) {
    const std::string p = testh::tmpPath("apng_t3_delay.png");
    std::vector<ApngFrame> fs;
    fs.push_back(solidFrame(8, 8, 255, 255, 255,
                            openclip::celDelayCs(3, 12.0)));  // 25
    fs.push_back(solidFrame(8, 8, 0, 0, 0, openclip::celDelayCs(1, 12.0)));
    std::string err;
    CHECK(writeApng(p, fs, 0, err));
    const auto bytes = readFile(p);
    // First fcTL delay fields: bytes [20..23] of its data (after seq,
    // w, h, x, y = 20 bytes) = delay_num u16, delay_den u16.
    std::size_t pos = 8;
    bool found = false;
    while (pos + 8 <= bytes.size()) {
        const std::uint32_t n = (bytes[pos] << 24) | (bytes[pos + 1] << 16) |
                                (bytes[pos + 2] << 8) | bytes[pos + 3];
        if (bytes[pos + 4] == 'f' && bytes[pos + 5] == 'c' &&
            bytes[pos + 6] == 'T' && bytes[pos + 7] == 'L' && n >= 26) {
            const int num = (bytes[pos + 8 + 20] << 8) | bytes[pos + 8 + 21];
            const int den = (bytes[pos + 8 + 22] << 8) | bytes[pos + 8 + 23];
            CHECK_EQ(num, 25);
            CHECK_EQ(den, 100);
            found = true;
            break;
        }
        pos += 12 + n;
        if (n > bytes.size()) break;
    }
    CHECK(found);
    std::remove(p.c_str());
}

// §4: single frame = valid APNG (acTL + 1 fcTL + IDAT, no fdAT).
TEST(apngSingleFrameValid) {
    const std::string p = testh::tmpPath("apng_t3_single.png");
    std::vector<ApngFrame> fs;
    fs.push_back(solidFrame(8, 8, 1, 2, 3, 8));
    std::string err;
    CHECK(writeApng(p, fs, 2, err));
    const auto bytes = readFile(p);
    CHECK(hasChunk(bytes, "acTL"));
    CHECK_EQ(countChunk(bytes, "fcTL"), 1);
    CHECK_EQ(countChunk(bytes, "fdAT"), 0);
    // Failures: empty + mismatch + bad loop leave no usable file.
    std::vector<ApngFrame> empty;
    CHECK(!writeApng(testh::tmpPath("apng_t3_no.png"), empty, 0, err));
    fs.push_back(solidFrame(4, 4, 0, 0, 0, 8));
    CHECK(!writeApng(testh::tmpPath("apng_t3_no.png"), fs, 0, err));
    std::remove(p.c_str());
}

// §4: N cels x holds eclates = N files (plan), recount honored.
TEST(pngSeqCountMatches) {
    CHECK_EQ(planPngSeq(3, {1, 2, 3}, true).size(), 6u);
    CHECK_EQ(planPngSeq(3, {1, 2, 3}, false).size(), 3u);
    const auto plan = planPngSeq(3, {1, 2, 3}, true);
    CHECK_EQ(plan.front(), 0);
    CHECK_EQ(plan.back(), 2);
    // Invalid holds fall back to 1 (same clamp as celDelayCs).
    CHECK_EQ(planPngSeq(2, {99, -4}, true).size(), 2u);
}

// §4: bad dir = false + error, nothing written.
TEST(pngSeqBadDirNoOp) {
    openclip::Surface s(4, 4);
    s.clear();
    std::string err;
    CHECK(!writePngSeq(testh::tmpPath("no-such-dir-xyz"), "frame", {&s},
                       "", err));
    CHECK(!err.empty());
    CHECK(writePngSeq(testh::tmpPath("no-such-dir-xyz"), "frame", {}, "",
                      err) == false);
}

// §4: sticker matte flattens to fully opaque.
TEST(stickerPresetOpaque) {
    openclip::Surface s(8, 8);
    s.clear();
    s.set(0, 0, openclip::Pixel{200, 100, 50, 128});
    flattenMatte(s, 255, 255, 255);
    for (int y = 0; y < 8; ++y)
        for (int x = 0; x < 8; ++x) CHECK_EQ(s.get(x, y).a, 255);
    CHECK(s.get(0, 0).r >= 200);  // ink survives over white
    CHECK_EQ(s.get(7, 7).r, 255);  // transparent -> matte
    CHECK_EQ(s.get(7, 7).g, 255);
}
