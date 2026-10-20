// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip M4 — PSD baseline import/export, from-scratch clean-room parser.
// Scope (documented in ARCHITECTURE.md §M4): RGB 8-bit, baseline merged
// image import; flat export with per-channel raw planar data. Anything
// outside this scope is refused with a clear error, never guessed.
#include "openclip/psd.h"

#include "openclip/canvas.h"

#include "openclip/blend.h"

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

bool savePsd(const Canvas& c, const std::string& path, std::string& error,
             int* approxOut) {
    Surface comp;
    c.composite(comp);
    const int w = comp.width(), h = comp.height();
    if (w <= 0 || h <= 0) {
        error = "psd: canvas empty";
        return false;
    }
    if (w > 30000 || h > 30000) {
        error = "psd: dimensions out of range";
        return false;
    }
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

    // V+2 layered records. Eligibility: pixels flow untouched through
    // the normal path (text/meta fiches are pixel-neutral, so fiche
    // layers stay eligible; groups, masks, clips, adjustments and
    // vector layers merge into the base record).
    struct Rec {
        std::string name;
        bool visible;
        std::uint8_t opacity;  // 0..255
        const char* key;       // 4-char blend key
        const std::vector<std::uint8_t>* planes[4];  // R,G,B,A straight
        std::vector<std::uint8_t> owned[4];  // filled for merged base
    };
    auto blendKey = [](BlendMode m, bool& mapped) -> const char* {
        mapped = true;
        switch (m) {
            case BlendMode::Normal:
                return "norm";
            case BlendMode::Multiply:
                return "mul ";
            case BlendMode::Screen:
                return "scrn";
            case BlendMode::Overlay:
                return "over";
            case BlendMode::Add:
                return "lddg";  // linear dodge (add)
            case BlendMode::SoftLight:
                return "sSoft";
            default:
                mapped = false;  // hard-light/difference/color/
                                 // luminosity -> norm + counted
                return "norm";
        }
    };
    std::vector<Rec> recs;
    int approx = 0;
    Canvas base;
    bool needBase = false;
    for (std::size_t i = 0; i < c.layerCount(); ++i) {
        const Layer& l = c.layer(i);
        const bool eligible = !l.isGroup && !l.hasMask && !l.clipped &&
                              l.adjustType.empty() && !l.isVector;
        if (!eligible) {
            needBase = true;
            ++approx;  // merged into the base record (visible count)
            continue;
        }
        bool mapped = true;
        const char* key = blendKey(l.blend, mapped);
        if (!mapped) ++approx;
        Rec r;
        r.name = l.name.substr(0, 31);
        if (r.name.empty()) r.name = "Layer";
        r.visible = l.visible;
        float op = l.opacity < 0 ? 0 : (l.opacity > 1 ? 1 : l.opacity);
        r.opacity = static_cast<std::uint8_t>(op * 255 + 0.5f);
        r.key = key;
        // Straight (un-premultiplied) planar pixels of the raw surface.
        for (int k = 0; k < 4; ++k) r.owned[k].assign(
            static_cast<std::size_t>(w) * h, 0);
        const auto& lb = l.surface.bytes();
        if (lb.size() == static_cast<std::size_t>(w) * h * 4) {
            for (std::size_t p = 0; p < static_cast<std::size_t>(w) * h;
                 ++p) {
                const std::uint8_t a = lb[p * 4 + 3];
                std::uint8_t s3[3] = {0, 0, 0};
                if (a == 255) {
                    s3[0] = lb[p * 4 + 0];
                    s3[1] = lb[p * 4 + 1];
                    s3[2] = lb[p * 4 + 2];
                } else if (a != 0) {
                    for (int k = 0; k < 3; ++k) {
                        int v = (lb[p * 4 + k] * 255 + a / 2) / a;
                        s3[k] = static_cast<std::uint8_t>(v > 255 ? 255
                                                                 : v);
                    }
                }
                r.owned[0][p] = s3[0];
                r.owned[1][p] = s3[1];
                r.owned[2][p] = s3[2];
                r.owned[3][p] = a;
            }
        }
        for (int k = 0; k < 4; ++k) r.planes[k] = &r.owned[k];
        recs.push_back(std::move(r));
    }
    if (needBase) {
        // Composite ONLY the non-eligible layers into the base record.
        base.resize(w, h);
        for (std::size_t i = 0; i < c.layerCount(); ++i) {
            const Layer& l = c.layer(i);
            const bool eligible = !l.isGroup && !l.hasMask && !l.clipped &&
                                  l.adjustType.empty() && !l.isVector;
            if (!eligible) base.adoptLayer(l);
        }
        Surface bcomp;
        base.composite(bcomp);
        const auto& bb = bcomp.bytes();
        Rec r;
        r.name = "Merged approximations";
        r.visible = true;
        r.opacity = 255;
        r.key = "norm";
        for (int k = 0; k < 4; ++k)
            r.owned[k].assign(static_cast<std::size_t>(w) * h, 0);
        if (bb.size() == static_cast<std::size_t>(w) * h * 4) {
            for (std::size_t p = 0; p < static_cast<std::size_t>(w) * h;
                 ++p) {
                const std::uint8_t a = bb[p * 4 + 3];
                std::uint8_t s3[3] = {0, 0, 0};
                if (a == 255) {
                    s3[0] = bb[p * 4 + 0];
                    s3[1] = bb[p * 4 + 1];
                    s3[2] = bb[p * 4 + 2];
                } else if (a != 0) {
                    for (int k = 0; k < 3; ++k) {
                        int v = (bb[p * 4 + k] * 255 + a / 2) / a;
                        s3[k] = static_cast<std::uint8_t>(v > 255 ? 255
                                                                 : v);
                    }
                }
                r.owned[0][p] = s3[0];
                r.owned[1][p] = s3[1];
                r.owned[2][p] = s3[2];
                r.owned[3][p] = a;
            }
        }
        for (int k = 0; k < 4; ++k) r.planes[k] = &r.owned[k];
        recs.insert(recs.begin(), std::move(r));  // base first (bottom)
    }

    std::vector<std::uint8_t> out;
    out.reserve(64);
    out.insert(out.end(), {'8', 'B', 'P', 'S'});
    wr16(out, 1);        // version
    for (int i = 0; i < 6; ++i) out.push_back(0);  // reserved
    const std::uint16_t channels = 4;
    wr16(out, channels);
    wr32(out, static_cast<std::uint32_t>(h));
    wr32(out, static_cast<std::uint32_t>(w));
    wr16(out, 8);        // depth
    wr16(out, 3);        // RGB mode
    wr32(out, 0);        // Color Mode Data length
    wr32(out, 0);        // Image Resources length

    // Layer and mask information section.
    std::vector<std::uint8_t> layerInfo;
    const std::int16_t count = static_cast<std::int16_t>(recs.size());
    layerInfo.push_back(static_cast<std::uint8_t>((count >> 8) & 0xff));
    layerInfo.push_back(static_cast<std::uint8_t>(count & 0xff));
    const std::size_t plane = static_cast<std::size_t>(w) * h;
    std::vector<std::vector<std::uint8_t>> channelData;
    for (auto& r : recs) {
        wr32(layerInfo, 0);                       // top
        wr32(layerInfo, 0);                       // left
        wr32(layerInfo, static_cast<std::uint32_t>(h));  // bottom
        wr32(layerInfo, static_cast<std::uint32_t>(w));  // right
        wr16(layerInfo, 4);                       // R,G,B,transparency
        static const std::int16_t ids[4] = {0, 1, 2, -1};
        for (int k = 0; k < 4; ++k) {
            wr16(layerInfo, static_cast<std::uint16_t>(ids[k]));
            wr32(layerInfo, static_cast<std::uint32_t>(2 + plane));
        }
        layerInfo.insert(layerInfo.end(), {'8', 'B', 'I', 'M'});
        for (int k = 0; k < 4; ++k)
            layerInfo.push_back(static_cast<std::uint8_t>(r.key[k]));
        layerInfo.push_back(r.opacity);
        layerInfo.push_back(0);                        // clipping: base
        layerInfo.push_back(r.visible ? 0 : 2);        // flags: bit1 hidden
        layerInfo.push_back(0);                        // filler
        // Extra data: mask len(0) + ranges len(0) + Pascal name.
        std::vector<std::uint8_t> extra;
        wr32(extra, 0);
        wr32(extra, 0);
        const std::string& nm = r.name;
        extra.push_back(static_cast<std::uint8_t>(nm.size() & 0xff));
        extra.insert(extra.end(), nm.begin(), nm.end());
        if (((1 + nm.size()) & 1) != 0) extra.push_back(0);  // even pad
        wr32(layerInfo, static_cast<std::uint32_t>(extra.size()));
        layerInfo.insert(layerInfo.end(), extra.begin(), extra.end());
        // Channel image data (raw, planar, straight).
        for (int k = 0; k < 4; ++k) {
            std::vector<std::uint8_t> cd;
            cd.reserve(2 + plane);
            wr16(cd, 0);  // compression: raw
            cd.insert(cd.end(), r.planes[k]->begin(),
                      r.planes[k]->end());
            channelData.push_back(std::move(cd));
        }
    }
    // [layer info length][count+records][channel data][global mask].
    std::vector<std::uint8_t> layerBlock;
    layerBlock.insert(layerBlock.end(), layerInfo.begin(), layerInfo.end());
    for (auto& cd : channelData)
        layerBlock.insert(layerBlock.end(), cd.begin(), cd.end());
    std::vector<std::uint8_t> lm;
    wr32(lm, static_cast<std::uint32_t>(layerBlock.size()));
    lm.insert(lm.end(), layerBlock.begin(), layerBlock.end());
    wr32(lm, 0);  // global mask info length
    wr32(out, static_cast<std::uint32_t>(lm.size()));
    out.insert(out.end(), lm.begin(), lm.end());

    wr16(out, 0);        // image data compression: raw
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
    if (approxOut) *approxOut = approx;
    return true;
}

// Diagnostic reader for our own layered layout: layer record names
// bottom-to-top. Tolerant walker (lengths drive every skip).
bool psdLayerNames(const std::string& path, std::vector<std::string>& names,
                   std::string& error) {
    names.clear();
    std::vector<std::uint8_t> d;
    if (!readFile(path, d, error)) return false;
    if (d.size() < 26 + 12 || std::memcmp(d.data(), "8BPS", 4) != 0) {
        error = "psd: not a PSD file";
        return false;
    }
    std::size_t off = 26;
    const std::uint32_t cm = rd32(d.data() + off);
    off += 4;
    if (cm > d.size() - off) {
        error = "psd: color mode overruns file";
        return false;
    }
    off += cm;
    if (d.size() - off < 4) {
        error = "psd: truncated image resources";
        return false;
    }
    const std::uint32_t res = rd32(d.data() + off);
    off += 4;
    if (res > d.size() - off) {
        error = "psd: image resources overruns file";
        return false;
    }
    off += res;
    if (d.size() - off < 4) {
        error = "psd: truncated layer section";
        return false;
    }
    const std::uint32_t lm = rd32(d.data() + off);
    off += 4;
    if (lm > d.size() - off || lm < 2) {
        error = "psd: layer section overruns file";
        return false;
    }
    const std::size_t lmEnd = off + lm;
    if (d.size() - off < 4) {
        error = "psd: truncated layer info";
        return false;
    }
    const std::uint32_t liLen = rd32(d.data() + off);
    off += 4;
    if (liLen > lmEnd - off || liLen < 2) {
        error = "psd: layer info overruns file";
        return false;
    }
    const std::size_t liEnd = off + liLen;
    std::int16_t count = static_cast<std::int16_t>(rd16(d.data() + off));
    off += 2;
    if (count < 0) count = -count;
    for (int i = 0; i < count; ++i) {
        if (liEnd - off < 16 + 2 + 12 + 4) {
            error = "psd: truncated layer record";
            return false;
        }
        off += 16;  // rect (full-canvas in our layout)
        const std::uint16_t nch = rd16(d.data() + off);
        off += 2;
        if (nch > 8 || liEnd - off < static_cast<std::size_t>(nch) * 6) {
            error = "psd: bad channel count";
            return false;
        }
        std::vector<std::uint32_t> chLen(nch);
        for (int k = 0; k < nch; ++k) {
            off += 2;  // channel id
            chLen[k] = rd32(d.data() + off);
            off += 4;
            (void)chLen[k];
        }
        if (liEnd - off < 12) {
            error = "psd: truncated blend record";
            return false;
        }
        off += 12;  // sig + key + opacity + clipping + flags + filler
        if (liEnd - off < 4) {
            error = "psd: truncated extra data";
            return false;
        }
        const std::uint32_t extra = rd32(d.data() + off);
        off += 4;
        if (extra > liEnd - off) {
            error = "psd: extra data overruns file";
            return false;
        }
        const std::size_t extraEnd = off + extra;
        if (extraEnd - off < 8) {
            error = "psd: extra data too short";
            return false;
        }
        off += 8;  // mask len + ranges len (both 0 in our layout)
        if (off >= extraEnd) {
            error = "psd: missing layer name";
            return false;
        }
        const std::uint8_t nl = d[off++];
        if (extraEnd - off < nl) {
            error = "psd: layer name overruns extra";
            return false;
        }
        names.emplace_back(reinterpret_cast<const char*>(d.data() + off),
                           nl);
        // Channel image data lives AFTER all records (not interleaved),
        // so consecutive record headers walk cleanly; lengths are
        // trusted to our own raw layout here (loadPsd validates the
        // merged view independently).
        off = extraEnd;
    }
    return true;
}

}  // namespace openclip
