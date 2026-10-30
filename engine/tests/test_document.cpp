// SPDX-License-Identifier: GPL-3.0-or-later
// M4 document I/O tests: ORA nested round trip, PSD fixture, PNG flatten.
#include "test.h"

#include <cstdio>
#include <cstdint>
#include <cstring>
#include <vector>

#include "openclip/canvas.h"
#include "openclip/document.h"
#include "openclip/ora.h"
#include "openclip/psd.h"

using openclip::Canvas;
using openclip::DocFormat;
using openclip::Layer;
using openclip::Pixel;
using openclip::Surface;

namespace {

Canvas pattern(int w, int h) {
    Canvas c(w, h);
    {
        const std::size_t li = c.addLayer("bottom");
        for (int y = 0; y < h; ++y)
            for (int x = 0; x < w; ++x)
                c.layer(li).surface.set(x, y, Pixel{255, 0, 0, 255});
    }
    {
        const std::size_t gi = c.addGroup("grp");
        Layer& g = c.layer(gi);
        g.passThrough = false;
        g.children.push_back(Layer());
        Layer& ch = g.children.back();
        ch.name = "in-group";
        ch.surface.resize(w, h);
        for (int y = 0; y < h; ++y)
            for (int x = 0; x < w; ++x)
                ch.surface.set(
                    x, y,
                    Pixel{(std::uint8_t)(x * 8), (std::uint8_t)(y * 4), 128,
                          128});
    }
    c.addLayer("top");
    return c;
}

bool compositeEquals(const Canvas& a, const Canvas& b) {
    Surface sa, sb;
    a.composite(sa);
    b.composite(sb);
    if (sa.width() != b.width() || sa.height() != a.height())
        return false;
    const auto& A = sa.bytes();
    const auto& B = sb.bytes();
    for (std::size_t i = 0; i < A.size(); ++i) {
        const int d = A[i] > B[i] ? A[i] - B[i] : B[i] - A[i];
        if (d > 1) return false;  // premult round-trip rounding allowance
    }
    if (A.size() != B.size()) return false;
    return true;
}

}  // namespace

TEST(ora_round_trip_nested_groups) {
    Canvas c = pattern(24, 16);
    const std::string p = testh::tmpPath("oc_ora_nested.ora");
    std::string err;
    CHECK(openclip::saveOra(c, p, err));
    if (!err.empty()) std::printf("    (save err: %s)\n", err.c_str());
    Canvas d;
    CHECK(openclip::loadOra(d, p, err));
    if (!err.empty()) std::printf("    (load err: %s)\n", err.c_str());
    CHECK_EQ(d.width(), 24);
    CHECK_EQ(d.height(), 16);
    CHECK_EQ(d.layerCount(), c.layerCount());
    CHECK(compositeEquals(c, d));
    // Attribute fidelity: exactly one top-level group with one child.
    bool rasterSeen = false, groupSeen = false;
    for (std::size_t i = 0; i < d.layerCount(); ++i) {
        const Layer& dl = d.layer(i);
        if (dl.isGroup) {
            groupSeen = true;
            CHECK_EQ(dl.children.size(),
                     c.layer(i).children.size() == 0
                         ? static_cast<std::size_t>(0)
                         : static_cast<std::size_t>(1));
            if (!dl.children.empty()) {
                CHECK_EQ(dl.children.front().name, std::string("in-group"));
            }
        } else {
            rasterSeen = true;
        }
    }
    CHECK(rasterSeen && groupSeen);
    std::remove(p.c_str());
}

TEST(document_detect_and_ora_save_load) {
    Canvas c = pattern(20, 14);
    const std::string p = testh::tmpPath("oc_doc.ora");
    std::string err;
    CHECK(openclip::saveDocument(c, p, "ora", err));
    CHECK_EQ(static_cast<int>(openclip::detectFormat(p)),
             static_cast<int>(DocFormat::Ora));
    Canvas d;
    CHECK(openclip::loadDocument(d, p, err));
    CHECK(compositeEquals(c, d));
    std::remove(p.c_str());
}

static void buildPsdFixture(std::vector<std::uint8_t>& b, int w, int h,
                            std::uint16_t mode, std::uint16_t depth,
                            std::uint16_t channels) {
    auto wr16 = [&](std::uint16_t v) {
        b.push_back((std::uint8_t)((v >> 8) & 0xff));
        b.push_back((std::uint8_t)(v & 0xff));
    };
    auto wr32 = [&](std::uint32_t v) {
        b.push_back((std::uint8_t)((v >> 24) & 0xff));
        b.push_back((std::uint8_t)((v >> 16) & 0xff));
        b.push_back((std::uint8_t)((v >> 8) & 0xff));
        b.push_back((std::uint8_t)(v & 0xff));
    };
    b.clear();
    b.push_back('8');
    b.push_back('B');
    b.push_back('P');
    b.push_back('S');
    wr16(1);
    for (int i = 0; i < 6; ++i) b.push_back(0);
    wr16(channels);
    wr32((std::uint32_t)h);
    wr32((std::uint32_t)w);
    wr16(depth);
    wr16(mode);
    wr32(0);  // color mode data
    wr32(0);  // resources
    wr32(0);  // layer & mask
    wr16(0);  // compression raw
    for (std::size_t ch = 0; ch < channels; ++ch)
        for (std::size_t y = 0; y < (std::size_t)h; ++y)
            for (std::size_t x = 0; x < (std::size_t)w; ++x) {
                std::uint8_t v;
                if (ch == 3) v = 255;
                else if (ch == 0) v = (std::uint8_t)x;
                else if (ch == 1) v = (std::uint8_t)y;
                else v = 128;
                b.push_back(v);
            }
}

TEST(psd_baseline_import) {
    std::vector<std::uint8_t> fx;
    buildPsdFixture(fx, 8, 6, 3, 8, 4);
    const std::string p = testh::tmpPath("oc_fixture.psd");
    std::FILE* f = std::fopen(p.c_str(), "wb");
    std::fwrite(fx.data(), 1, fx.size(), f);
    std::fclose(f);
    CHECK_EQ(static_cast<int>(openclip::detectFormat(p)),
             static_cast<int>(DocFormat::Psd));
    Canvas c;
    std::string err;
    CHECK(openclip::loadDocument(c, p, err));
    if (!err.empty()) std::printf("    (load err: %s)\n", err.c_str());
    CHECK_EQ(c.width(), 8);
    CHECK_EQ(c.height(), 6);
    Surface s;
    c.composite(s);
    CHECK_EQ((int)s.get(5, 0).r, 5);
    CHECK_EQ((int)s.get(0, 4).g, 4);
    CHECK_EQ((int)s.get(3, 3).b, 128);
    CHECK_EQ((int)s.get(2, 2).a, 255);
    std::remove(p.c_str());
}

TEST(psd_rejects_garbage_and_unsupported) {
    const std::string pj = testh::tmpPath("oc_junk.psd");
    std::FILE* f = std::fopen(pj.c_str(), "wb");
    std::fwrite("garbage!!", 1, 9, f);
    std::fclose(f);
    Canvas c(4, 4);
    Surface before;
    c.composite(before);
    std::string err;
    CHECK(!openclip::loadDocument(c, pj, err));
    Surface after;
    c.composite(after);
    CHECK_EQ(before.bytes(), after.bytes());

    std::vector<std::uint8_t> fx;
    buildPsdFixture(fx, 4, 4, 0, 8, 1);  // bitmap mode refused
    const std::string p2 = testh::tmpPath("oc_badmode.psd");
    std::FILE* g = std::fopen(p2.c_str(), "wb");
    std::fwrite(fx.data(), 1, fx.size(), g);
    std::fclose(g);
    CHECK(!openclip::loadDocument(c, p2, err));
    std::remove(pj.c_str());
    std::remove(p2.c_str());
}

TEST(document_unknown_format_and_missing_file) {
    Canvas c(6, 6);
    std::string err;
    CHECK(!openclip::saveDocument(c, testh::tmpPath("oc_x.fzz"), "tiffz", err));
    CHECK(!openclip::loadDocument(c, testh::tmpPath("oc_missing_file.ora"), err));
}

TEST(psd_save_png_save_roundtrip_smoke) {
    Canvas c = pattern(10, 8);
    std::string err;
    CHECK(openclip::savePsd(c, testh::tmpPath("oc_out.psd"), err));
    if (!err.empty()) std::printf("    (psd err: %s)\n", err.c_str());
    Canvas d;
    CHECK(openclip::loadDocument(d, testh::tmpPath("oc_out.psd"), err));
    CHECK_EQ(d.width(), 10);
    CHECK(compositeEquals(c, d));
    CHECK(openclip::saveDocument(c, testh::tmpPath("oc_out.png"), "png", err));
    Canvas e;
    CHECK(openclip::loadDocument(e, testh::tmpPath("oc_out.png"), err));
    CHECK_EQ(e.width(), 10);
    CHECK(compositeEquals(c, e));
    std::remove(testh::tmpPath("oc_out.psd").c_str());
    std::remove(testh::tmpPath("oc_out.png").c_str());
}

// V+2 layered PSD: two eligible raster layers + one masked layer.
// Records list bottom-to-top with the merged base first; the merged
// view still round trips through loadPsd; the masked layer counts 1
// approximation, unmapped blend (difference) counts as norm + 1.
TEST(psd_layered_roundtrip_names_pixels) {
    Canvas c(8, 8);
    c.addLayer("sky");
    for (int y = 0; y < 8; ++y)
        for (int x = 0; x < 8; ++x)
            c.layer(0).surface.set(x, y, Pixel{10, 20, 30, 255});
    c.addLayer("sun");
    c.layer(1).blend = openclip::BlendMode::Multiply;
    for (int y = 0; y < 8; ++y)
        for (int x = 0; x < 8; ++x)
            c.layer(1).surface.set(x, y, Pixel{200, 100, 50, 255});
    c.addLayer("hidden-detail");
    c.layer(2).addMask(8, 8);  // masked -> merged into the base record
    const std::string path = testh::tmpPath("oc_layers.psd");
    std::string err;
    int approx = -1;
    CHECK(openclip::savePsd(c, path, err, &approx));
    CHECK_EQ(approx, 1);
    std::vector<std::string> names;
    CHECK(openclip::psdLayerNames(path, names, err));
    CHECK_EQ(names.size(), 3u);
    CHECK_EQ(names[0], std::string("Merged approximations"));
    CHECK_EQ(names[1], std::string("sky"));
    CHECK_EQ(names[2], std::string("sun"));
    Canvas d;
    CHECK(openclip::loadPsd(d, path, err));
    CHECK(compositeEquals(c, d));
    std::remove(path.c_str());
}

// ---- raw layered-PSD walker (regression helper for issue #1) ----
// Bounded reader for exactly the layout savePsd (src/io/psd.cpp) writes.
// All integers are big-endian; offsets from byte 0:
//   26  header: "8BPS", u16 version, 6 reserved, u16 channels, u32 rows,
//       u32 cols, u16 depth, u16 mode
//   +4  color mode data length, then that many bytes
//   +4  image resources length, then that many bytes
//   +4  layer and mask section length; the section holds:
//         +4 layer info length; that holds:
//           i16 layer count, then `count` layer records, then ALL channel
//           image data (a flat run after the records, not interleaved):
//           per record, per channel: u16 compression + rows*cols bytes
//         +4 global layer mask length (0 in our writer)
// Every read is bounds-checked against the section it belongs to; the
// declared channel lengths must add up to the bytes actually present.
namespace {

std::uint16_t psdBe16(const std::vector<std::uint8_t>& d, std::size_t o) {
    return static_cast<std::uint16_t>((d[o] << 8) | d[o + 1]);
}

std::uint32_t psdBe32(const std::vector<std::uint8_t>& d, std::size_t o) {
    return (static_cast<std::uint32_t>(d[o]) << 24) |
           (static_cast<std::uint32_t>(d[o + 1]) << 16) |
           (static_cast<std::uint32_t>(d[o + 2]) << 8) |
           static_cast<std::uint32_t>(d[o + 3]);
}

struct PsdRawChannel {
    const std::uint8_t* data;  // raw plane bytes (compression tag skipped)
    std::size_t size;
};

struct PsdRawRecord {
    std::string name;
    std::vector<PsdRawChannel> channels;  // record order (R,G,B,A here)
};

bool psdReadLayerChannels(const std::vector<std::uint8_t>& d,
                          std::vector<PsdRawRecord>& out, std::string& why) {
    out.clear();
    const std::size_t n = d.size();
    auto fits = [&](std::size_t at, std::size_t len, const char* what) {
        if (len > n || at > n - len) {
            why = std::string("truncated ") + what;
            return false;
        }
        return true;
    };
    if (n < 26 || std::memcmp(d.data(), "8BPS", 4) != 0) {
        why = "not a PSD header";
        return false;
    }
    std::size_t o = 26;
    for (const char* what : {"color mode data", "image resources"}) {
        if (!fits(o, 4, what)) return false;
        const std::size_t len = psdBe32(d, o);
        o += 4;
        if (!fits(o, len, what)) return false;
        o += len;
    }
    if (!fits(o, 4, "layer section length")) return false;
    const std::size_t lmEnd = o + 4 + psdBe32(d, o);
    o += 4;
    if (lmEnd > n) {
        why = "layer section overruns file";
        return false;
    }
    if (!fits(o, 4, "layer info length")) return false;
    const std::size_t liEnd = o + 4 + psdBe32(d, o);
    o += 4;
    if (liEnd > lmEnd) {
        why = "layer info overruns layer section";
        return false;
    }
    if (!fits(o, 2, "layer count")) return false;
    const int count = psdBe16(d, o);
    o += 2;
    std::vector<std::vector<std::uint32_t>> chLens;  // per record channels
    for (int i = 0; i < count; ++i) {
        if (!fits(o, 16 + 2, "layer record")) return false;
        o += 16;  // top, left, bottom, right (whole canvas in our writer)
        const int nch = psdBe16(d, o);
        o += 2;
        if (nch < 1 || nch > 8) {
            why = "bad channel count";
            return false;
        }
        if (!fits(o, static_cast<std::size_t>(nch) * 6, "channel infos"))
            return false;
        std::vector<std::uint32_t> lens;
        lens.reserve(static_cast<std::size_t>(nch));
        for (int k = 0; k < nch; ++k) {
            o += 2;  // channel id: 0=R, 1=G, 2=B, -1=A in our writer
            lens.push_back(psdBe32(d, o));
            o += 4;
        }
        if (!fits(o, 12 + 4, "blend record")) return false;
        o += 12;  // '8BIM', 4-char key, opacity, clipping, flags, filler
        const std::size_t extraEnd = o + 4 + psdBe32(d, o);
        o += 4;
        if (extraEnd > liEnd) {
            why = "extra data overruns layer info";
            return false;
        }
        // Extra data: mask data (len + bytes), blend ranges (len + bytes),
        // Pascal name, then optional tagged blocks (none needed here).
        if (!fits(o, 4, "mask data length")) return false;
        const std::size_t maskLen = psdBe32(d, o);
        o += 4;
        if (!fits(o, maskLen, "mask data")) return false;
        o += maskLen;
        if (!fits(o, 4, "blend ranges length")) return false;
        const std::size_t rangesLen = psdBe32(d, o);
        o += 4;
        if (!fits(o, rangesLen, "blend ranges")) return false;
        o += rangesLen;
        if (o > extraEnd) {
            why = "extra data lengths overrun the extra block";
            return false;
        }
        if (o >= extraEnd) {
            why = "missing layer name";
            return false;
        }
        const std::size_t nl = d[o++];
        if (nl > extraEnd - o) {
            why = "layer name overruns extra data";
            return false;
        }
        PsdRawRecord rec;
        rec.name.assign(reinterpret_cast<const char*>(d.data() + o), nl);
        out.push_back(std::move(rec));
        o = extraEnd;
        chLens.push_back(std::move(lens));
    }
    // Channel image data: one flat run after the last record, inside the
    // layer info block. The record headers only *declare* the lengths.
    if (o > liEnd) {
        why = "layer records overrun layer info";
        return false;
    }
    const std::size_t payload = liEnd - o;
    std::size_t declared = 0;
    for (const auto& lens : chLens) {
        for (std::uint32_t len : lens) {
            if (len < 2 || len > payload - declared) {
                why = "channel block of " + std::to_string(len) +
                      " bytes does not fit the " +
                      std::to_string(payload - declared) + " of " +
                      std::to_string(payload) + " bytes left for channel data";
                return false;
            }
            declared += len;
        }
    }
    if (declared != payload) {
        why = "declared channel data (" + std::to_string(declared) +
              " bytes) != payload present (" + std::to_string(payload) +
              " bytes)";
        return false;
    }
    std::size_t p = o;
    for (std::size_t i = 0; i < chLens.size(); ++i) {
        for (std::uint32_t len : chLens[i]) {
            if (psdBe16(d, p) != 0) {  // compression 0 = raw
                why = "unexpected channel compression";
                return false;
            }
            out[i].channels.push_back({d.data() + p + 2, len - 2});
            p += len;
        }
    }
    return true;
}

}  // namespace

// Regression for issue #1: layer records must carry their own pixels.
// The pre-fix writer bound Rec::planes[] to Rec's own owned[] vectors and
// then moved the record into `recs`, so every channel block was emitted
// EMPTY while its declared length still said "2 + w*h": the layered PSD
// was structurally inconsistent, yet no test noticed because loadPsd
// only reads the merged (flattened) view, which stayed correct.
TEST(psd_layer_channel_payload_has_layer_pixels) {
    Canvas c(6, 5);
    c.addLayer("red");
    c.addLayer("green");
    const std::uint8_t want[2][4] = {{255, 0, 0, 255},  // opaque red
                                     {0, 255, 0, 255}};  // opaque green
    for (std::size_t li = 0; li < 2; ++li)
        for (int y = 0; y < 5; ++y)
            for (int x = 0; x < 6; ++x)
                c.layer(li).surface.set(
                    x, y,
                    Pixel{want[li][0], want[li][1], want[li][2], want[li][3]});
    const std::string path = testh::tmpPath("oc_channel.psd");
    std::string err;
    CHECK(openclip::savePsd(c, path, err));
    std::vector<std::uint8_t> d;
    std::FILE* f = std::fopen(path.c_str(), "rb");
    CHECK(f != nullptr);
    if (f) {
        std::fseek(f, 0, SEEK_END);
        const long n = std::ftell(f);
        std::fseek(f, 0, SEEK_SET);
        d.resize(static_cast<std::size_t>(n));
        CHECK(std::fread(d.data(), 1, d.size(), f) == d.size());
        std::fclose(f);
    }
    std::vector<PsdRawRecord> recs;
    std::string why;
    const bool walked = psdReadLayerChannels(d, recs, why);
    if (!walked) std::printf("    (psd walk: %s)\n", why.c_str());
    CHECK(walked);
    CHECK_EQ(recs.size(), 2u);
    if (walked && recs.size() == 2) {
        CHECK_EQ(recs[0].name, std::string("red"));
        CHECK_EQ(recs[1].name, std::string("green"));
        for (std::size_t i = 0; i < 2; ++i) {
            CHECK_EQ(recs[i].channels.size(), 4u);
            for (std::size_t k = 0; k < recs[i].channels.size(); ++k) {
                const PsdRawChannel& ch = recs[i].channels[k];
                CHECK_EQ(ch.size, static_cast<std::size_t>(30));
                bool uniform = ch.size == 30;
                const std::uint8_t w = want[i][k];
                for (std::size_t q = 0; uniform && q < ch.size; ++q)
                    uniform = ch.data[q] == w;
                if (!uniform)
                    std::printf("    (%s plane %lu is not its own pixels)\n",
                                recs[i].name.c_str(),
                                static_cast<unsigned long>(k));
                CHECK(uniform);
            }
        }
    }
    std::remove(path.c_str());
}

// V+2 PSD: unknown-for-PSD blend (difference) degrades to norm and is
// counted; all-eligible stacks report 0 approximations.
TEST(psd_unknown_blend_counts_norm) {
    Canvas c(4, 4);
    c.addLayer("a");
    c.layer(0).blend = openclip::BlendMode::Difference;
    for (int y = 0; y < 4; ++y)
        for (int x = 0; x < 4; ++x)
            c.layer(0).surface.set(x, y, Pixel{9, 9, 9, 255});
    const std::string path = testh::tmpPath("oc_diff.psd");
    std::string err;
    int approx = -1;
    CHECK(openclip::savePsd(c, path, err, &approx));
    CHECK_EQ(approx, 1);
    std::vector<std::string> names;
    CHECK(openclip::psdLayerNames(path, names, err));
    CHECK_EQ(names.size(), 1u);
    CHECK_EQ(names[0], std::string("a"));
    std::remove(path.c_str());
}

// V+2 PSD: groups flatten into the base record with a visible count.
TEST(psd_group_flattens_with_count) {
    Canvas c(6, 6);
    c.addLayer("solo");
    for (int y = 0; y < 6; ++y)
        for (int x = 0; x < 6; ++x)
            c.layer(0).surface.set(x, y, Pixel{1, 2, 3, 255});
    const std::size_t g = c.addGroup("grp");
    (void)g;
    Canvas d;
    const std::string path = testh::tmpPath("oc_grp.psd");
    std::string err;
    int approx = -1;
    CHECK(openclip::savePsd(c, path, err, &approx));
    CHECK_EQ(approx, 1);  // the group merged into the base record
    std::vector<std::string> names;
    CHECK(openclip::psdLayerNames(path, names, err));
    CHECK_EQ(names.size(), 2u);
    CHECK_EQ(names[0], std::string("Merged approximations"));
    CHECK_EQ(names[1], std::string("solo"));
    CHECK(openclip::loadPsd(d, path, err));
    CHECK(compositeEquals(c, d));
    std::remove(path.c_str());
}

// V+2 generic fiche (nSetLayerMeta): keys round trip verbatim through
// ORA, including third-party-style x-openclip-* attrs on load.
TEST(meta_ora_roundtrip_verbatim) {
    Canvas c(8, 8);
    c.addLayer("panel");
    for (int y = 0; y < 8; ++y)
        for (int x = 0; x < 8; ++x)
            c.layer(0).surface.set(x, y, Pixel{7, 7, 7, 255});
    c.layer(0).meta["x-openclip-panel"] = "rect:1,2,5,6";
    c.layer(0).meta["x-openclip-panel-order"] = "3";
    c.layer(0).meta["x-openclip-speed"] = "1,2,3,4,5,6";
    const std::string path = testh::tmpPath("oc_meta.ora");
    std::string err;
    CHECK(openclip::saveOra(c, path, err));
    Canvas d;
    CHECK(openclip::loadOra(d, path, err));
    std::remove(path.c_str());
    CHECK_EQ(d.layerCount(), 1u);
    CHECK_EQ(d.layer(0).meta["x-openclip-panel"], std::string("rect:1,2,5,6"));
    CHECK_EQ(d.layer(0).meta["x-openclip-panel-order"], std::string("3"));
    CHECK_EQ(d.layer(0).meta["x-openclip-speed"], std::string("1,2,3,4,5,6"));
    CHECK(compositeEquals(c, d));  // fiches never touch pixels
}

// ---- T2 §1: fiche texte etendue (ARCHITECTURE.md §T2 §1) ----

TEST(text_runs_round_trip) {
    Canvas c(64, 64);
    c.addLayer("t");
    Layer& l = c.layer(0);
    l.textContent = "Hello brave new world";
    l.textFont = "serif";
    l.textSizePx = 24;
    l.textStyle = "bold";
    l.textRuns =
        "[{\"start\":0,\"len\":5,\"font\":\"serif\",\"style\":\"bold\","
        "\"size\":24,\"color\":\"#FF0000\"},{\"start\":6,\"len\":5,"
        "\"font\":\"monospace\",\"style\":\"regular\",\"size\":20,"
        "\"color\":\"#0000FF\"}]";
    l.textOutline = "{\"w\":2,\"color\":\"#000000\"}";
    l.textFontFile = "LibreCaslon.ttf";
    l.textDir = "v";
    l.textHyphen = "1";
    const std::string path = testh::tmpPath("oc_textruns.ora");
    std::string err;
    CHECK(openclip::saveOra(c, path, err));
    Canvas d;
    CHECK(openclip::loadOra(d, path, err));
    std::remove(path.c_str());
    const Layer& q = d.layer(0);
    CHECK_EQ(q.textContent, l.textContent);
    CHECK_EQ(q.textRuns, l.textRuns);
    CHECK_EQ(q.textOutline, l.textOutline);
    CHECK_EQ(q.textFontFile, l.textFontFile);
    CHECK_EQ(q.textDir, std::string("v"));
    CHECK_EQ(q.textHyphen, std::string("1"));
    CHECK(compositeEquals(c, d));  // fiches never touch pixels
}

TEST(text_runs_malformed_ignored) {
    Canvas c(64, 64);
    c.addLayer("t");
    Layer& l = c.layer(0);
    l.textContent = "Hello world, this is long enough";
    l.textRuns = "[{\"start\":999,\"len\":5},not-json";
    l.textDir = "sideways";  // unknown direction refused
    const std::string path = testh::tmpPath("oc_textbad.ora");
    std::string err;
    CHECK(openclip::saveOra(c, path, err));
    Canvas d;
    CHECK(openclip::loadOra(d, path, err));  // never a broken layer
    std::remove(path.c_str());
    CHECK_EQ(d.layer(0).textContent, l.textContent);
    CHECK(d.layer(0).textDir.empty());  // bad dir falls back to default
    CHECK(compositeEquals(c, d));
}

TEST(text_fontfile_missing_fallback) {
    // The REPRO repli (ARCHITECTURE.md §T2): fontfile is a recorded
    // NAME only — reload without the file keeps a normal layer with
    // intact pixels (the app substitutes sans-serif + banner).
    Canvas c(32, 32);
    c.addLayer("t");
    Layer& l = c.layer(0);
    l.textContent = "Missing font";
    l.textFont = "SomeImportedFont";
    l.textFontFile = "SomeImportedFont.ttf";
    for (int y = 0; y < 32; ++y)
        for (int x = 0; x < 32; ++x)
            l.surface.set(x, y, Pixel{9, 9, 9, 255});
    const std::string path = testh::tmpPath("oc_textfont.ora");
    std::string err;
    CHECK(openclip::saveOra(c, path, err));
    Canvas d;
    CHECK(openclip::loadOra(d, path, err));
    std::remove(path.c_str());
    CHECK_EQ(d.layer(0).textFontFile, std::string("SomeImportedFont.ttf"));
    CHECK(compositeEquals(c, d));  // pixels always kept
}

TEST(text_outline_opaque) {
    // The contour is opaque fiche data: composite ignores it.
    Canvas c(32, 32);
    c.addLayer("t");
    c.layer(0).textContent = "Outline";
    c.layer(0).textOutline = "{\"w\":8,\"color\":\"#FF0000\"}";
    Canvas e(32, 32);
    e.addLayer("t");
    e.layer(0).textContent = "Outline";
    CHECK(compositeEquals(c, e));
}

// ---- T2 §5: PSD TySh + calque phylactere (ARCHITECTURE.md §T2 §5) ----

TEST(psd_type_tool_present) {
    Canvas c(32, 32);
    c.addLayer("title");
    Layer& l = c.layer(0);
    l.textContent = "Hi";
    l.textFont = "serif";
    l.textSizePx = 24;
    l.textColor = "#FF0000";
    l.textBox = "2,4,28";
    for (int y = 0; y < 32; ++y)
        for (int x = 0; x < 32; ++x)
            l.surface.set(x, y, Pixel{1, 2, 3, 255});
    const std::string path = testh::tmpPath("oc_tysh.psd");
    std::string err;
    int approx = -1;
    CHECK(openclip::savePsd(c, path, err, &approx));
    CHECK_EQ(approx, 0);  // single run = exact, not approximated
    // Raw scan: TySh tag + UTF-16BE text present in the file.
    std::FILE* f = std::fopen(path.c_str(), "rb");
    CHECK(f != nullptr);
    std::vector<std::uint8_t> d;
    if (f) {
        std::fseek(f, 0, SEEK_END);
        const long n = std::ftell(f);
        std::fseek(f, 0, SEEK_SET);
        d.resize(static_cast<std::size_t>(n));
        CHECK(std::fread(d.data(), 1, d.size(), f) == d.size());
        std::fclose(f);
    }
    const std::string raw(d.begin(), d.end());
    CHECK(raw.find("TySh") != std::string::npos);
    CHECK(raw.find("serif") != std::string::npos);
    bool found = false;  // "Hi" as UTF-16BE (00 48 00 69)
    for (std::size_t i = 0; i + 3 < d.size(); ++i)
        if (d[i] == 0 && d[i + 1] == 'H' && d[i + 2] == 0 &&
            d[i + 3] == 'i') {
            found = true;
            break;
        }
    CHECK(found);
    std::remove(path.c_str());
}

TEST(psd_runs_flatten_counted) {
    Canvas c(32, 32);
    c.addLayer("mixed");
    Layer& l = c.layer(0);
    l.textContent = "Hello brave";
    l.textRuns =
        "[{\"start\":0,\"len\":5,\"style\":\"bold\"},{\"start\":6,\"len\":5,"
        "\"style\":\"italic\"}]";
    const std::string path = testh::tmpPath("oc_tyruns.psd");
    std::string err;
    int approx = -1;
    CHECK(openclip::savePsd(c, path, err, &approx));
    CHECK_EQ(approx, 1);  // runs > 1 flatten onto the base style + count
    std::remove(path.c_str());
}

TEST(bubble_composite_paints) {
    Canvas c(64, 64);
    c.addLayer("bubble");
    Layer& l = c.layer(0);
    for (int y = 0; y < 64; ++y)
        for (int x = 0; x < 64; ++x)
            l.surface.set(x, y, Pixel{10, 20, 30, 255});
    l.bubbleShape = "ellipse";
    l.bubbleFrame = "8,8,48,32";
    l.bubbleFill = "#FFFFFF";
    l.bubbleStroke = "#000000";
    l.bubbleStrokeW = 4.0f;
    Surface out;
    c.composite(out);
    const Pixel mid = out.get(32, 24);  // deep inside the balloon
    CHECK(mid.a == 255 && mid.r > 200 && mid.g > 200 && mid.b > 200);
    const Pixel far = out.get(2, 60);  // outside: layer pixels intact
    CHECK_EQ(far.r, 10);
    CHECK_EQ(far.g, 20);
    CHECK_EQ(far.b, 30);
}

TEST(bubble_null_clears) {
    Canvas c(64, 64);
    c.addLayer("b");
    Layer& l = c.layer(0);
    for (int y = 0; y < 64; ++y)
        for (int x = 0; x < 64; ++x)
            l.surface.set(x, y, Pixel{10, 20, 30, 255});
    l.bubbleShape = "rounded";
    l.bubbleFrame = "8,8,48,32";
    Surface with;
    c.composite(with);
    CHECK(with.get(32, 24).r > 200);
    l.bubbleShape.clear();  // null/empty = normal layer again
    l.bubbleFrame.clear();
    Surface without;
    c.composite(without);
    CHECK_EQ(without.get(32, 24).r, 10);  // back to raw pixels
}

TEST(bubble_ora_round_trip) {
    Canvas c(64, 64);
    c.addLayer("b");
    Layer& l = c.layer(0);
    l.bubbleShape = "rounded";
    l.bubbleFrame = "8,8,48,32";
    l.bubbleTail = "32,40,50,60";
    l.bubbleFill = "#FFF8E0";
    l.bubbleStroke = "#201808";
    l.bubbleStrokeW = 3.0f;
    l.bubbleTextLayer = 2;
    const std::string path = testh::tmpPath("oc_bubble.ora");
    std::string err;
    CHECK(openclip::saveOra(c, path, err));
    Canvas d;
    CHECK(openclip::loadOra(d, path, err));
    std::remove(path.c_str());
    const Layer& q = d.layer(0);
    CHECK_EQ(q.bubbleShape, std::string("rounded"));
    CHECK_EQ(q.bubbleFrame, std::string("8,8,48,32"));
    CHECK_EQ(q.bubbleTail, std::string("32,40,50,60"));
    CHECK_EQ(q.bubbleFill, std::string("#FFF8E0"));
    CHECK_EQ(q.bubbleStroke, std::string("#201808"));
    CHECK(q.bubbleStrokeW == 3.0f);
    CHECK_EQ(q.bubbleTextLayer, 2);
    Surface a, b;
    c.composite(a);
    d.composite(b);
    CHECK(a.bytes() == b.bytes());  // balloon re-renders identically
}

// ---- A2: PSD mapping of the new blend modes + pass-through ---------

namespace {

std::vector<std::uint8_t> readAllBytes(const std::string& path) {
    std::vector<std::uint8_t> out;
    FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return out;
    std::fseek(f, 0, SEEK_END);
    const long n = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    if (n > 0) {
        out.resize(static_cast<std::size_t>(n));
        const std::size_t got = std::fread(out.data(), 1, out.size(), f);
        if (got != out.size()) out.clear();
    }
    std::fclose(f);
    return out;
}

bool containsBytes(const std::vector<std::uint8_t>& d, const char* key) {
    const std::size_t n = std::strlen(key);
    if (n == 0 || d.size() < n) return false;
    for (std::size_t i = 0; i + n <= d.size(); ++i)
        if (std::memcmp(d.data() + i, key, n) == 0) return true;
    return false;
}

}  // namespace

// A2: color burn/dodge, max, darker/lighter color, hue and saturation
// map to real Photoshop keys and stay pixel-faithful (approx 0).
TEST(psd_a2_blend_keys) {
    Canvas c(8, 8);
    c.addLayer("base");
    for (int y = 0; y < 8; ++y)
        for (int x = 0; x < 8; ++x)
            c.layer(0).surface.set(x, y, Pixel{10, 20, 30, 255});
    struct Case {
        openclip::BlendMode m;
        const char* key;
    };
    const Case cases[] = {
        {openclip::BlendMode::ColorBurn, "idiv"},
        {openclip::BlendMode::ColorDodge, "div "},
        {openclip::BlendMode::Max, "lite"},
        {openclip::BlendMode::DarkerColor, "dkCl"},
        {openclip::BlendMode::LighterColor, "lCCl"},
        {openclip::BlendMode::Hue, "hue "},
        {openclip::BlendMode::Saturation, "sat "},
    };
    for (const auto& k : cases) {
        const std::size_t li = c.addLayer(k.key);
        c.layer(li).blend = k.m;
        for (int y = 0; y < 8; ++y)
            for (int x = 0; x < 8; ++x)
                c.layer(li).surface.set(x, y, Pixel{200, 100, 50, 255});
    }
    const std::string path = testh::tmpPath("oc_a2_keys.psd");
    std::string err;
    int approx = -1;
    CHECK(openclip::savePsd(c, path, err, &approx));
    CHECK_EQ(approx, 0);
    const std::vector<std::uint8_t> bytes = readAllBytes(path);
    CHECK(!bytes.empty());
    for (const auto& k : cases)
        CHECK(containsBytes(bytes, k.key));
    std::remove(path.c_str());
}

// A2: add-glow has no PSD key -> normal + counted (documented
// approximation, honest degrade).
TEST(psd_a2_add_glow_unmapped) {
    Canvas c(8, 8);
    const std::size_t li = c.addLayer("glow");
    c.layer(li).blend = openclip::BlendMode::AddGlow;
    c.layer(li).surface.set(0, 0, Pixel{255, 255, 255, 255});
    const std::string path = testh::tmpPath("oc_a2_glow.psd");
    std::string err;
    int approx = -1;
    CHECK(openclip::savePsd(c, path, err, &approx));
    CHECK_EQ(approx, 1);
    const std::vector<std::uint8_t> bytes = readAllBytes(path);
    CHECK(containsBytes(bytes, "norm"));
    CHECK(!containsBytes(bytes, "add-"));
    std::remove(path.c_str());
}

// A2: groups (pass-through included) flatten into the base record and
// are counted; the group name never leaks as a fake layer.
TEST(psd_a2_passthrough_group_flattened) {
    Canvas c(8, 8);
    c.addLayer("ok");
    for (int y = 0; y < 8; ++y)
        for (int x = 0; x < 8; ++x)
            c.layer(0).surface.set(x, y, Pixel{42, 42, 42, 255});
    const std::size_t gi = c.addGroup("passgroup");
    CHECK(c.layer(gi).passThrough);
    c.layer(gi).children.push_back(Layer());
    c.layer(gi).children.back().surface.resize(8, 8);
    for (int y = 0; y < 8; ++y)
        for (int x = 0; x < 8; ++x)
            c.layer(gi).children.back().surface.set(x, y,
                                                    Pixel{200, 10, 10, 255});
    const std::string path = testh::tmpPath("oc_a2_pass.psd");
    std::string err;
    int approx = -1;
    CHECK(openclip::savePsd(c, path, err, &approx));
    CHECK(approx >= 1);
    std::vector<std::string> names;
    CHECK(openclip::psdLayerNames(path, names, err));
    CHECK_EQ(names[0], std::string("Merged approximations"));
    for (const auto& n : names) CHECK(n != std::string("passgroup"));
    // The merged record keeps the group's pixels (red kid visible).
    Canvas d;
    CHECK(openclip::loadPsd(d, path, err));
    Surface out;
    d.composite(out);
    CHECK_EQ(out.get(4, 4).r, 200);
    std::remove(path.c_str());
}
