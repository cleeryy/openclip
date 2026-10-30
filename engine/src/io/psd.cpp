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

void wr32(std::vector<std::uint8_t>& o, std::uint32_t v);  // defined below

// T2: minimal UTF-8 -> UTF-16BE transcode (BMP direct, astral via
// surrogate pairs, malformed bytes -> U+FFFD). Honest subset for the
// TySh text payload.
void utf8ToUtf16Be(const std::string& in, std::vector<std::uint8_t>& out) {
    std::size_t i = 0;
    while (i < in.size()) {
        const unsigned char c = static_cast<unsigned char>(in[i]);
        std::uint32_t cp = 0xFFFD;
        std::size_t adv = 1;
        if (c < 0x80) {
            cp = c;
        } else if ((c & 0xE0) == 0xC0 && i + 1 < in.size()) {
            cp = ((c & 0x1F) << 6) |
                 (static_cast<unsigned char>(in[i + 1]) & 0x3F);
            adv = 2;
            if (cp < 0x80) cp = 0xFFFD;
        } else if ((c & 0xF0) == 0xE0 && i + 2 < in.size()) {
            cp = ((c & 0x0F) << 12) |
                 ((static_cast<unsigned char>(in[i + 1]) & 0x3F) << 6) |
                 (static_cast<unsigned char>(in[i + 2]) & 0x3F);
            adv = 3;
        } else if ((c & 0xF8) == 0xF0 && i + 3 < in.size()) {
            cp = ((c & 0x07) << 18) |
                 ((static_cast<unsigned char>(in[i + 1]) & 0x3F) << 12) |
                 ((static_cast<unsigned char>(in[i + 2]) & 0x3F) << 6) |
                 (static_cast<unsigned char>(in[i + 3]) & 0x3F);
            adv = 4;
        }
        i += adv;
        if (cp > 0x10FFFF) cp = 0xFFFD;
        if (cp >= 0x10000) {
            cp -= 0x10000;
            const std::uint16_t hi =
                static_cast<std::uint16_t>(0xD800 + (cp >> 10));
            const std::uint16_t lo =
                static_cast<std::uint16_t>(0xDC00 + (cp & 0x3FF));
            out.push_back((hi >> 8) & 0xff);
            out.push_back(hi & 0xff);
            out.push_back((lo >> 8) & 0xff);
            out.push_back(lo & 0xff);
        } else {
            out.push_back((cp >> 8) & 0xff);
            out.push_back(cp & 0xff);
        }
    }
}

// T2 TySh subset block payload (ARCHITECTURE.md §T2 §5, documented
// minimal): version u16=1, Pascal font name, u16 size px, 4 straight
// bytes color+0, u16 unit count + UTF-16BE text, 4 big-endian floats
// frame (x,y,w,h parsed from textBox "x,y,w", h=0 when absent).
// B2 §1 appends four floats (rot, sx, sy, skew) after the frame and
// bumps the subset version to 2: a version-1 reader stops at the
// frame, so older parses stay valid. The transform itself is exact;
// runs still flatten + count (unchanged T2 rule).
std::vector<std::uint8_t> buildTypeToolPayload(const Layer& l) {
    std::vector<std::uint8_t> p;
    wr16(p, 2);  // subset version (B2: 2 adds the transform tuple)
    const std::string font =
        l.textFont.empty() ? "sans-serif" : l.textFont.substr(0, 63);
    p.push_back(static_cast<std::uint8_t>(font.size() & 0xff));
    p.insert(p.end(), font.begin(), font.end());
    if ((1 + font.size()) & 1) p.push_back(0);  // even pad
    const int sz = l.textSizePx >= 1 ? l.textSizePx : 0;
    wr16(p, static_cast<std::uint16_t>(sz > 65535 ? 65535 : sz));
    auto hexByte = [](const std::string& s, std::size_t at) -> int {
        auto hx = [](char c) -> int {
            if (c >= '0' && c <= '9') return c - '0';
            if (c >= 'a' && c <= 'f') return c - 'a' + 10;
            if (c >= 'A' && c <= 'F') return c - 'A' + 10;
            return -1;
        };
        if (at + 1 >= s.size()) return -1;
        const int hi = hx(s[at]), lo = hx(s[at + 1]);
        return (hi < 0 || lo < 0) ? -1 : hi * 16 + lo;
    };
    std::string hc = l.textColor;
    if (!hc.empty() && hc[0] == '#') hc = hc.substr(1);
    int cr = 0x14, cg = 0x14, cb = 0x14;
    if (hc.size() == 6) {
        const int a = hexByte(hc, 0), b = hexByte(hc, 2), c2 = hexByte(hc, 4);
        if (a >= 0 && b >= 0 && c2 >= 0) {
            cr = a;
            cg = b;
            cb = c2;
        }
    }
    p.push_back(static_cast<std::uint8_t>(cr));
    p.push_back(static_cast<std::uint8_t>(cg));
    p.push_back(static_cast<std::uint8_t>(cb));
    p.push_back(0);
    std::vector<std::uint8_t> u16;
    utf8ToUtf16Be(l.textContent.substr(0, 2048), u16);
    const std::size_t units = u16.size() / 2;
    wr16(p, static_cast<std::uint16_t>(units > 65535 ? 65535 : units));
    p.insert(p.end(), u16.begin(),
             u16.begin() +
                 (units > 65535 ? 65535 * 2 : u16.size()));
    float fx = 0, fy = 0, fw = 0, fh = 0;
    {
        float v[3] = {0, 0, 0};
        int n = 0;
        std::size_t pos = 0;
        while (n < 3 && pos < l.textBox.size()) {
            while (pos < l.textBox.size() &&
                   (l.textBox[pos] == ' ' || l.textBox[pos] == ','))
                ++pos;
            if (pos >= l.textBox.size()) break;
            char* e = nullptr;
            v[n++] = std::strtof(l.textBox.c_str() + pos, &e);
            if (e == l.textBox.c_str() + pos) break;
            pos = static_cast<std::size_t>(e - l.textBox.c_str());
        }
        if (n == 3) {
            fx = v[0];
            fy = v[1];
            fw = v[2];
        }
    }
    auto wrF = [&](float f) {
        std::uint32_t u = 0;
        std::memcpy(&u, &f, 4);
        p.push_back((u >> 24) & 0xff);
        p.push_back((u >> 16) & 0xff);
        p.push_back((u >> 8) & 0xff);
        p.push_back(u & 0xff);
    };
    wrF(fx);
    wrF(fy);
    wrF(fw);
    wrF(fh);
    // B2: transform tuple "rot,sx,sy,skew" (identity when absent or
    // malformed: a bad opaque value never corrupts the block).
    float xf[4] = {0.0f, 1.0f, 1.0f, 0.0f};
    {
        float v[4] = {0, 1, 1, 0};
        int n = 0;
        std::size_t pos = 0;
        while (n < 4 && pos < l.textXform.size()) {
            while (pos < l.textXform.size() &&
                   (l.textXform[pos] == ' ' || l.textXform[pos] == ','))
                ++pos;
            if (pos >= l.textXform.size()) break;
            char* e = nullptr;
            v[n++] = std::strtof(l.textXform.c_str() + pos, &e);
            if (e == l.textXform.c_str() + pos) break;
            pos = static_cast<std::size_t>(e - l.textXform.c_str());
        }
        if (n == 4) {
            for (int k = 0; k < 4; ++k) xf[k] = v[k];
        }
    }
    for (float f : xf) wrF(f);
    return p;
}

// Full tagged block: '8BIM' + 'TySh' + even-padded size + payload.
std::vector<std::uint8_t> buildTypeToolBlock(const Layer& l) {
    std::vector<std::uint8_t> b;
    b.insert(b.end(), {'8', 'B', 'I', 'M'});
    b.insert(b.end(), {'T', 'y', 'S', 'h'});
    const std::vector<std::uint8_t> p = buildTypeToolPayload(l);
    wr32(b, static_cast<std::uint32_t>(p.size()));
    b.insert(b.end(), p.begin(), p.end());
    if (p.size() & 1) b.push_back(0);  // even pad
    return b;
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
        // R,G,B,A straight planar pixels; always the source of truth.
        // (No pointer indirection here: Rec is moved into `recs`, so any
        // pointer to its own members would dangle after the move.)
        std::vector<std::uint8_t> owned[4];
        std::vector<std::uint8_t> tagged;   // T2: TySh block or empty
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
            // T5 additions (ARCHITECTURE.md "T5 additions" §3): real
            // Photoshop keys where they exist; exclusion has no key.
            case BlendMode::Darken:
                return "dark";
            case BlendMode::Lighten:
                return "lite";
            case BlendMode::LinearBurn:
                return "lbrn";
            case BlendMode::LinearDodge:
                return "lddg";  // same key as Add (documented twin)
            case BlendMode::VividLight:
                return "vLit";
            case BlendMode::LinearLight:
                return "lLit";
            case BlendMode::PinLight:
                return "pLit";
            case BlendMode::Subtract:
                return "fsub";
            case BlendMode::Divide:
                return "fdiv";
            // A2 additions (ARCHITECTURE.md "A2 additions"): real
            // Photoshop keys where they exist. Max shares the lighten
            // key (documented twin); add-glow has no PSD key -> norm
            // + counted (honest approximation). Exclusion and the
            // V+2 color/luminosity family use their real keys too.
            case BlendMode::ColorBurn:
                return "idiv";
            case BlendMode::ColorDodge:
                return "div ";
            case BlendMode::Max:
                return "lite";
            case BlendMode::DarkerColor:
                return "dkCl";
            case BlendMode::LighterColor:
                return "lCCl";
            case BlendMode::Hue:
                return "hue ";
            case BlendMode::Saturation:
                return "sat ";
            case BlendMode::Color:
                return "colr";
            case BlendMode::Luminosity:
                return "lum ";
            case BlendMode::Exclusion:
                return "smud";
            case BlendMode::AddGlow:
                mapped = false;  // no PSD key: norm + counted
                return "norm";
            default:
                mapped = false;  // future modes -> norm + counted
                return "norm";
        }
    };
    std::vector<Rec> recs;
    int approx = 0;
    Canvas base;
    bool needBase = false;
    for (std::size_t i = 0; i < c.layerCount(); ++i) {
        const Layer& l = c.layer(i);
        // T2: bubble layers are non-eligible (flattened + counted, the
        // V+2 §5.5 rule); text layers stay eligible (fiche is
        // pixel-neutral) with a TySh subset block (see below).
        // T5: reference/draft layers merge into the base record
        // (spec §1.6: flattened + counted); fill layers carry no
        // committable pixels (spec §2.6: base + counted).
        const bool eligible = !l.isGroup && !l.hasMask && !l.clipped &&
                              l.adjustType.empty() && !l.isVector &&
                              l.bubbleShape.empty() && !l.hasFill() &&
                              !l.reference && !l.draft;
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
        // T2 TySh subset (ARCHITECTURE.md §T2 §5): one tagged block per
        // eligible text layer — font name, size, color, frame, UTF-16BE
        // string — IN ADDITION to the pixels. runs > 1 flatten onto
        // the base style + approx +1 (heuristic: the opaque runs
        // string holding more than one run object; documented).
        if (l.hasText()) {
            int runs = 0;
            for (char ch : l.textRuns)
                if (ch == '{') ++runs;
            if (runs > 1) ++approx;
            r.tagged = buildTypeToolBlock(l);
        }
        recs.push_back(std::move(r));
    }
    if (needBase) {
        // Composite ONLY the non-eligible layers into the base record.
        base.resize(w, h);
        for (std::size_t i = 0; i < c.layerCount(); ++i) {
            const Layer& l = c.layer(i);
            const bool eligible = !l.isGroup && !l.hasMask && !l.clipped &&
                                  l.adjustType.empty() && !l.isVector &&
                                  l.bubbleShape.empty() && !l.hasFill() &&
                                  !l.reference && !l.draft;
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
        // T2 TySh subset (text layers only; readers without text
        // support skip tagged blocks and see the pixels, as today).
        if (!r.tagged.empty())
            extra.insert(extra.end(), r.tagged.begin(), r.tagged.end());
        wr32(layerInfo, static_cast<std::uint32_t>(extra.size()));
        layerInfo.insert(layerInfo.end(), extra.begin(), extra.end());
        // Channel image data (raw, planar, straight).
        for (int k = 0; k < 4; ++k) {
            std::vector<std::uint8_t> cd;
            cd.reserve(2 + plane);
            wr16(cd, 0);  // compression: raw
            cd.insert(cd.end(), r.owned[k].begin(),
                      r.owned[k].end());
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
