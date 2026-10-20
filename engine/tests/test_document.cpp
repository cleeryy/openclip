// SPDX-License-Identifier: GPL-3.0-or-later
// M4 document I/O tests: ORA nested round trip, PSD fixture, PNG flatten.
#include "test.h"

#include <cstdio>
#include <cstdint>
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
    const std::string p = "/tmp/oc_ora_nested.ora";
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
}

TEST(document_detect_and_ora_save_load) {
    Canvas c = pattern(20, 14);
    const std::string p = "/tmp/oc_doc.ora";
    std::string err;
    CHECK(openclip::saveDocument(c, p, "ora", err));
    CHECK_EQ(static_cast<int>(openclip::detectFormat(p)),
             static_cast<int>(DocFormat::Ora));
    Canvas d;
    CHECK(openclip::loadDocument(d, p, err));
    CHECK(compositeEquals(c, d));
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
    const std::string p = "/tmp/oc_fixture.psd";
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
}

TEST(psd_rejects_garbage_and_unsupported) {
    const std::string pj = "/tmp/oc_junk.psd";
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
    const std::string p2 = "/tmp/oc_badmode.psd";
    std::FILE* g = std::fopen(p2.c_str(), "wb");
    std::fwrite(fx.data(), 1, fx.size(), g);
    std::fclose(g);
    CHECK(!openclip::loadDocument(c, p2, err));
}

TEST(document_unknown_format_and_missing_file) {
    Canvas c(6, 6);
    std::string err;
    CHECK(!openclip::saveDocument(c, "/tmp/oc_x.fzz", "tiffz", err));
    CHECK(!openclip::loadDocument(c, "/tmp/oc_missing_file.ora", err));
}

TEST(psd_save_png_save_roundtrip_smoke) {
    Canvas c = pattern(10, 8);
    std::string err;
    CHECK(openclip::savePsd(c, "/tmp/oc_out.psd", err));
    if (!err.empty()) std::printf("    (psd err: %s)\n", err.c_str());
    Canvas d;
    CHECK(openclip::loadDocument(d, "/tmp/oc_out.psd", err));
    CHECK_EQ(d.width(), 10);
    CHECK(compositeEquals(c, d));
    CHECK(openclip::saveDocument(c, "/tmp/oc_out.png", "png", err));
    Canvas e;
    CHECK(openclip::loadDocument(e, "/tmp/oc_out.png", err));
    CHECK_EQ(e.width(), 10);
    CHECK(compositeEquals(c, e));
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
    const std::string path = "/tmp/oc_layers.psd";
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

// V+2 PSD: unknown-for-PSD blend (difference) degrades to norm and is
// counted; all-eligible stacks report 0 approximations.
TEST(psd_unknown_blend_counts_norm) {
    Canvas c(4, 4);
    c.addLayer("a");
    c.layer(0).blend = openclip::BlendMode::Difference;
    for (int y = 0; y < 4; ++y)
        for (int x = 0; x < 4; ++x)
            c.layer(0).surface.set(x, y, Pixel{9, 9, 9, 255});
    const std::string path = "/tmp/oc_diff.psd";
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
    const std::string path = "/tmp/oc_grp.psd";
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
    c.layer(0).meta["x-openclip-bubble"] = "ellipse";
    const std::string path = "/tmp/oc_meta.ora";
    std::string err;
    CHECK(openclip::saveOra(c, path, err));
    Canvas d;
    CHECK(openclip::loadOra(d, path, err));
    std::remove(path.c_str());
    CHECK_EQ(d.layerCount(), 1u);
    CHECK_EQ(d.layer(0).meta["x-openclip-panel"], std::string("rect:1,2,5,6"));
    CHECK_EQ(d.layer(0).meta["x-openclip-panel-order"], std::string("3"));
    CHECK_EQ(d.layer(0).meta["x-openclip-bubble"], std::string("ellipse"));
    CHECK(compositeEquals(c, d));  // fiches never touch pixels
}
