// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip T3 — APNG + PNG sequence implementation (clean-room).
#include "openclip/apng.h"

#include <cstdio>
#include <cstring>
#include <zlib.h>

#include "openclip/cels.h"
#include "openclip/ora.h"
#include "openclip/surface.h"

namespace openclip {
namespace {

void putU32(std::vector<std::uint8_t>& o, std::uint32_t v) {
    o.push_back(static_cast<std::uint8_t>((v >> 24) & 0xFF));
    o.push_back(static_cast<std::uint8_t>((v >> 16) & 0xFF));
    o.push_back(static_cast<std::uint8_t>((v >> 8) & 0xFF));
    o.push_back(static_cast<std::uint8_t>(v & 0xFF));
}

void putU16(std::vector<std::uint8_t>& o, std::uint16_t v) {
    o.push_back(static_cast<std::uint8_t>((v >> 8) & 0xFF));
    o.push_back(static_cast<std::uint8_t>(v & 0xFF));
}

// One PNG chunk (length + type + data + CRC over type+data).
void chunk(std::vector<std::uint8_t>& o, const char type[4],
           const std::uint8_t* data, std::size_t n) {
    putU32(o, static_cast<std::uint32_t>(n));
    const std::size_t t0 = o.size();
    for (int i = 0; i < 4; ++i) o.push_back(static_cast<std::uint8_t>(type[i]));
    if (data && n) o.insert(o.end(), data, data + n);
    uLong crc = crc32(0L, Z_NULL, 0);
    crc = crc32(crc, o.data() + t0, static_cast<uInt>(4 + n));
    putU32(o, static_cast<std::uint32_t>(crc));
}

// Raw scanlines (filter 0 per row) -> zlib stream.
bool deflateRows(const std::uint8_t* rgba, int w, int h,
                 std::vector<std::uint8_t>& z) {
    if (w <= 0 || h <= 0 || !rgba) return false;
    const std::size_t rowBytes = static_cast<std::size_t>(w) * 4;
    std::vector<std::uint8_t> raw(static_cast<std::size_t>(h) * (rowBytes + 1));
    for (int y = 0; y < h; ++y) {
        raw[static_cast<std::size_t>(y) * (rowBytes + 1)] = 0;
        std::memcpy(raw.data() + static_cast<std::size_t>(y) * (rowBytes + 1) + 1,
                    rgba + static_cast<std::size_t>(y) * rowBytes, rowBytes);
    }
    uLong bound = compressBound(static_cast<uLong>(raw.size()));
    z.assign(static_cast<std::size_t>(bound), 0);
    uLong len = bound;
    if (compress2(z.data(), &len, raw.data(), static_cast<uLong>(raw.size()),
                  Z_BEST_SPEED) != Z_OK)
        return false;
    z.resize(static_cast<std::size_t>(len));
    return true;
}

bool parseMatte(const std::string& matte, std::uint8_t& r,
                std::uint8_t& g, std::uint8_t& b) {
    if (matte.size() != 7 || matte[0] != '#') return false;
    unsigned v = 0;
    for (int i = 1; i < 7; ++i) {
        const char c = matte[i];
        unsigned d = 0;
        if (c >= '0' && c <= '9') d = static_cast<unsigned>(c - '0');
        else if (c >= 'a' && c <= 'f') d = static_cast<unsigned>(c - 'a' + 10);
        else if (c >= 'A' && c <= 'F') d = static_cast<unsigned>(c - 'A' + 10);
        else return false;
        v = (v << 4) | d;
    }
    r = static_cast<std::uint8_t>((v >> 16) & 0xFF);
    g = static_cast<std::uint8_t>((v >> 8) & 0xFF);
    b = static_cast<std::uint8_t>(v & 0xFF);
    return true;
}

}  // namespace

void flattenMatte(Surface& s, std::uint8_t r, std::uint8_t g,
                  std::uint8_t b) {
    const int w = s.width(), h = s.height();
    if (w <= 0 || h <= 0) return;
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            Pixel p = s.get(x, y);
            if (p.a == 255) continue;
            const int ia = 255 - p.a;
            // Source-over onto the opaque matte (premultiplied out).
            int qr = p.r + (r * ia + 127) / 255;
            int qg = p.g + (g * ia + 127) / 255;
            int qb = p.b + (b * ia + 127) / 255;
            p.r = static_cast<std::uint8_t>(qr > 255 ? 255 : qr);
            p.g = static_cast<std::uint8_t>(qg > 255 ? 255 : qg);
            p.b = static_cast<std::uint8_t>(qb > 255 ? 255 : qb);
            p.a = 255;
            s.set(x, y, p);
        }
    }
}

bool writeApng(const std::string& path,
               const std::vector<ApngFrame>& frames, int loop,
               std::string& error) {
    if (frames.empty()) {
        error = "apng: no frames";
        return false;
    }
    const int w = frames[0].w, h = frames[0].h;
    if (w <= 0 || h <= 0) {
        error = "apng: bad dimensions";
        return false;
    }
    for (const auto& f : frames) {
        if (f.w != w || f.h != h ||
            f.rgba.size() != static_cast<std::size_t>(w) * h * 4) {
            error = "apng: dimension mismatch";
            return false;
        }
    }
    if (loop != 0 && loop != -1 && loop < 2) {
        error = "apng: bad loop";
        return false;
    }
    std::vector<std::uint8_t> o;
    static const std::uint8_t kSig[8] = {137, 80, 78, 71, 13, 10, 26, 10};
    o.insert(o.end(), kSig, kSig + 8);
    std::vector<std::uint8_t> ihdr;
    putU32(ihdr, static_cast<std::uint32_t>(w));
    putU32(ihdr, static_cast<std::uint32_t>(h));
    ihdr.push_back(8);  // bit depth
    ihdr.push_back(6);  // color type RGBA
    ihdr.push_back(0);
    ihdr.push_back(0);
    ihdr.push_back(0);  // compression/filter/interlace
    chunk(o, "IHDR", ihdr.data(), ihdr.size());
    std::vector<std::uint8_t> actl;
    putU32(actl, static_cast<std::uint32_t>(frames.size()));
    const std::uint32_t plays = (loop == -1) ? 0 : static_cast<std::uint32_t>(
        loop == 0 ? 1 : loop);
    putU32(actl, plays);
    chunk(o, "acTL", actl.data(), actl.size());
    std::uint32_t seq = 0;
    for (std::size_t i = 0; i < frames.size(); ++i) {
        const ApngFrame& f = frames[i];
        std::vector<std::uint8_t> fctl;
        putU32(fctl, seq++);
        putU32(fctl, static_cast<std::uint32_t>(w));
        putU32(fctl, static_cast<std::uint32_t>(h));
        putU32(fctl, 0);  // x
        putU32(fctl, 0);  // y
        const int cs = f.delayCs < 0 ? 0 : f.delayCs;
        putU16(fctl, static_cast<std::uint16_t>(cs > 65535 ? 65535 : cs));
        putU16(fctl, 100);  // denominator: 1/100 s like GIF GCE
        fctl.push_back(0);  // dispose: none
        fctl.push_back(1);  // blend: over
        chunk(o, "fcTL", fctl.data(), fctl.size());
        std::vector<std::uint8_t> z;
        if (!deflateRows(f.rgba.data(), w, h, z)) {
            error = "apng: deflate failed";
            return false;
        }
        if (i == 0) {
            chunk(o, "IDAT", z.data(), z.size());
        } else {
            std::vector<std::uint8_t> fdat;
            putU32(fdat, seq++);
            fdat.insert(fdat.end(), z.begin(), z.end());
            chunk(o, "fdAT", fdat.data(), fdat.size());
        }
    }
    chunk(o, "IEND", nullptr, 0);
    FILE* fp = std::fopen(path.c_str(), "wb");
    if (!fp) {
        error = "apng: cannot open " + path;
        return false;
    }
    const std::size_t wrote = std::fwrite(o.data(), 1, o.size(), fp);
    std::fclose(fp);
    if (wrote != o.size()) {
        error = "apng: short write";
        return false;
    }
    return true;
}

std::vector<int> planPngSeq(int frames, const std::vector<int>& holds,
                            bool expand) {
    std::vector<int> plan;
    if (frames <= 0) return plan;
    for (int i = 0; i < frames; ++i) {
        int hold = 1;
        if (i < static_cast<int>(holds.size())) hold = holds[i];
        if (!validCelHold(hold)) hold = 1;
        const int reps = expand ? hold : 1;
        for (int k = 0; k < reps; ++k) plan.push_back(i);
    }
    return plan;
}

bool writePngSeq(const std::string& dir, const std::string& prefix,
                 const std::vector<const Surface*>& ticks,
                 const std::string& matte, std::string& error) {
    if (ticks.empty()) {
        error = "pngseq: no frames";
        return false;
    }
    std::uint8_t mr = 255, mg = 255, mb = 255;
    const bool useMatte = !matte.empty();
    if (useMatte && !parseMatte(matte, mr, mg, mb)) {
        error = "pngseq: bad matte";
        return false;
    }
    for (std::size_t i = 0; i < ticks.size(); ++i) {
        if (!ticks[i] || ticks[i]->width() <= 0 || ticks[i]->height() <= 0) {
            error = "pngseq: bad surface";
            return false;
        }
        Surface s = *ticks[i];  // copy: matte flatten must not touch live
        if (useMatte) flattenMatte(s, mr, mg, mb);
        std::vector<std::uint8_t> png;
        if (!encodePng(s, png)) {
            error = "pngseq: PNG encode failed";
            return false;
        }
        char name[64];
        std::snprintf(name, sizeof(name), "%s_%04d.png",
                      prefix.empty() ? "frame" : prefix.c_str(),
                      static_cast<int>(i + 1));
        const std::string path = dir + "/" + name;
        FILE* fp = std::fopen(path.c_str(), "wb");
        if (!fp) {
            error = "pngseq: cannot write " + path;
            return false;
        }
        const std::size_t wrote = std::fwrite(png.data(), 1, png.size(), fp);
        std::fclose(fp);
        if (wrote != png.size()) {
            error = "pngseq: short write " + path;
            return false;
        }
    }
    return true;
}

}  // namespace openclip
