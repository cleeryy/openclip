// SPDX-License-Identifier: GPL-3.0-or-later
// M9 reference-model tests (ARCHITECTURE.md "M9 additions"): a minimal
// cube .glb is GENERATED in-code (clean-room, no binary assets in git),
// loaded through loadRefModel and rendered with the perspective camera.
#include "test.h"

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "openclip/ref.h"

using openclip::RefModel;
using openclip::RefView;
using openclip::loadRefModel;
using openclip::renderRefEdges;

namespace {

void putU32(std::vector<std::uint8_t>& b, std::uint32_t v) {
    b.push_back(static_cast<std::uint8_t>(v & 0xFF));
    b.push_back(static_cast<std::uint8_t>((v >> 8) & 0xFF));
    b.push_back(static_cast<std::uint8_t>((v >> 16) & 0xFF));
    b.push_back(static_cast<std::uint8_t>((v >> 24) & 0xFF));
}

void putF32(std::vector<std::uint8_t>& b, float v) {
    std::uint32_t u;
    std::memcpy(&u, &v, 4);
    putU32(b, u);
}

void putU16(std::vector<std::uint8_t>& b, std::uint16_t v) {
    b.push_back(static_cast<std::uint8_t>(v & 0xFF));
    b.push_back(static_cast<std::uint8_t>((v >> 8) & 0xFF));
}

// Minimal valid glTF 2.0 binary: unit cube (8 verts, 12 triangles,
// 18 unique edges) at the origin.
bool writeCubeGlb(const std::string& path) {
    const float verts[8][3] = {
        {-0.5f, -0.5f, -0.5f}, {0.5f, -0.5f, -0.5f}, {0.5f, 0.5f, -0.5f},
        {-0.5f, 0.5f, -0.5f},  {-0.5f, -0.5f, 0.5f}, {0.5f, -0.5f, 0.5f},
        {0.5f, 0.5f, 0.5f},    {-0.5f, 0.5f, 0.5f},
    };
    const std::uint16_t idx[36] = {
        0, 1, 2, 0, 2, 3,  // back
        4, 6, 5, 4, 7, 6,  // front
        0, 4, 5, 0, 5, 1,  // bottom
        3, 2, 6, 3, 6, 7,  // top
        0, 3, 7, 0, 7, 4,  // left
        1, 5, 6, 1, 6, 2,  // right
    };
    std::vector<std::uint8_t> bin;
    for (int i = 0; i < 8; ++i)
        for (int c = 0; c < 3; ++c) putF32(bin, verts[i][c]);
    for (int i = 0; i < 36; ++i) putU16(bin, idx[i]);

    const std::string json =
        "{\"asset\":{\"version\":\"2.0\"},"
        "\"buffers\":[{\"byteLength\":" + std::to_string(bin.size()) + "}],"
        "\"bufferViews\":["
        "{\"buffer\":0,\"byteOffset\":0,\"byteLength\":96,\"target\":34962},"
        "{\"buffer\":0,\"byteOffset\":96,\"byteLength\":72,\"target\":34963}],"
        "\"accessors\":["
        "{\"bufferView\":0,\"componentType\":5126,\"count\":8,\"type\":\"VEC3\"},"
        "{\"bufferView\":1,\"componentType\":5123,\"count\":36,\"type\":\"SCALAR\"}],"
        "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0},"
        "\"indices\":1}]}],"
        "\"nodes\":[{\"mesh\":0}],"
        "\"scenes\":[{\"nodes\":[0]}],\"scene\":0}";
    std::vector<std::uint8_t> jchunk(json.begin(), json.end());
    while (jchunk.size() % 4 != 0) jchunk.push_back(' ');

    std::vector<std::uint8_t> out;
    putU32(out, 0x46546C67);  // "glTF"
    putU32(out, 2);
    putU32(out, static_cast<std::uint32_t>(12 + 8 + jchunk.size() + 8 +
                                           bin.size()));
    putU32(out, static_cast<std::uint32_t>(jchunk.size()));
    putU32(out, 0x4E4F534A);  // "JSON"
    out.insert(out.end(), jchunk.begin(), jchunk.end());
    putU32(out, static_cast<std::uint32_t>(bin.size()));
    putU32(out, 0x004E4942);  // "BIN\0"
    out.insert(out.end(), bin.begin(), bin.end());

    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) return false;
    const bool ok = std::fwrite(out.data(), 1, out.size(), f) == out.size();
    std::fclose(f);
    return ok;
}

}  // namespace

TEST(ref_cube_loads_18_edges) {
    const std::string path = "/tmp/openclip_cube.glb";
    CHECK(writeCubeGlb(path));
    RefModel m;
    std::string err;
    CHECK(loadRefModel(path, m, err));
    CHECK_EQ(m.verts.size(), (std::size_t)24);
    CHECK_EQ(m.edges.size(), (std::size_t)18);
    std::remove(path.c_str());
}

TEST(ref_bad_path_fails) {
    RefModel m;
    std::string err;
    CHECK(!loadRefModel("/tmp/openclip_missing_xyz.glb", m, err));
}

TEST(ref_renders_pixels) {
    const std::string path = "/tmp/openclip_cube.glb";
    CHECK(writeCubeGlb(path));
    RefModel m;
    std::string err;
    CHECK(loadRefModel(path, m, err));
    RefView v;
    v.dist = 3;
    v.fov = 45;
    const int w = 256, h = 256;
    std::vector<std::uint8_t> buf(static_cast<std::size_t>(w) * h * 4, 0);
    int drawn = 0;
    renderRefEdges(m, v, w, h, buf, drawn);
    CHECK(drawn > 0);
    bool anyAlpha = false;
    for (std::size_t i = 3; i < buf.size(); i += 4)
        if (buf[i] != 0) anyAlpha = true;
    CHECK(anyAlpha);
    std::remove(path.c_str());
}

TEST(ref_fisheye_displaces_pixels) {
    const std::string path = "/tmp/openclip_cube.glb";
    CHECK(writeCubeGlb(path));
    RefModel m;
    std::string err;
    CHECK(loadRefModel(path, m, err));
    const int w = 256, h = 256;
    RefView v0;
    v0.dist = 3;
    v0.fov = 45;
    std::vector<std::uint8_t> a(static_cast<std::size_t>(w) * h * 4, 0);
    int d0 = 0;
    renderRefEdges(m, v0, w, h, a, d0);
    RefView v1 = v0;
    v1.fisheye = 0.8;
    std::vector<std::uint8_t> b(static_cast<std::size_t>(w) * h * 4, 0);
    int d1 = 0;
    renderRefEdges(m, v1, w, h, b, d1);
    CHECK(d0 > 0);
    CHECK(d1 > 0);
    CHECK(a != b);
    std::remove(path.c_str());
}

TEST(ref_empty_model_draws_nothing) {
    RefModel m;
    RefView v;
    const int w = 64, h = 64;
    std::vector<std::uint8_t> buf(static_cast<std::size_t>(w) * h * 4, 0);
    int drawn = 0;
    renderRefEdges(m, v, w, h, buf, drawn);
    CHECK_EQ(drawn, 0);
}
