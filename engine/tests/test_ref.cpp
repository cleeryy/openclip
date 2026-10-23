// SPDX-License-Identifier: GPL-3.0-or-later
// M9 reference-model tests (ARCHITECTURE.md "M9 additions"): a minimal
// cube .glb is GENERATED in-code (clean-room, no binary assets in git),
// loaded through loadRefModel and rendered with the perspective camera.
#include "test.h"

#include <cmath>
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

// V+3 pose rig (ARCHITECTURE.md "V+3 additions"): two NAMED nodes
// (hips root + head child translated +1y), both meshed with the unit
// cube. Unnamed-cube fixtures report no joints; poses rotate subtrees
// about the node rest translation.
bool writeRigGlb(const std::string& path) {
    const float verts[8][3] = {
        {-0.5f, -0.5f, -0.5f}, {0.5f, -0.5f, -0.5f}, {0.5f, 0.5f, -0.5f},
        {-0.5f, 0.5f, -0.5f},  {-0.5f, -0.5f, 0.5f}, {0.5f, -0.5f, 0.5f},
        {0.5f, 0.5f, 0.5f},    {-0.5f, 0.5f, 0.5f},
    };
    const std::uint16_t idx[36] = {
        0, 1, 2, 0, 2, 3, 4, 6, 5, 4, 7, 6, 0, 4, 5, 0, 5, 1,
        3, 2, 6, 3, 6, 7, 0, 3, 7, 0, 7, 4, 1, 5, 6, 1, 6, 2,
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
        "\"meshes\":["
        "{\"primitives\":[{\"attributes\":{\"POSITION\":0},\"indices\":1}]},"
        "{\"primitives\":[{\"attributes\":{\"POSITION\":0},\"indices\":1}]}],"
        "\"nodes\":["
        "{\"name\":\"hips\",\"mesh\":0,\"children\":[1]},"
        "{\"name\":\"head\",\"mesh\":1,\"translation\":[0.0,1.0,0.0]}],"
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

TEST(ref_joints_empty_on_cube) {
    const std::string path = "/tmp/openclip_cube.glb";
    CHECK(writeCubeGlb(path));
    RefModel m;
    std::string err;
    CHECK(loadRefModel(path, m, err));
    CHECK_EQ(openclip::refJointsJson(m), "{\"joints\":[]}");
    std::remove(path.c_str());
}

TEST(ref_pose_unknown_ignored_and_clamped) {
    const std::string cube = "/tmp/openclip_cube.glb";
    CHECK(writeCubeGlb(cube));
    RefModel m;
    std::string err;
    CHECK(loadRefModel(cube, m, err));
    // Unknown bones are ignored (M3 rule), never a failure.
    CHECK(openclip::applyRefPoseJson(m, "{\"nope\":[1,2,3]}"));
    CHECK(m.pose.empty());
    CHECK(openclip::applyRefPoseJson(m, "{\"hips\":45}"));
    CHECK(m.pose.empty());  // cube has no named bones at all
    std::remove(cube.c_str());

    const std::string rig = "/tmp/openclip_rig.glb";
    CHECK(writeRigGlb(rig));
    RefModel r;
    CHECK(loadRefModel(rig, r, err));
    CHECK_EQ(r.joints.size(), (std::size_t)2);
    // Out-of-range clamps to ±180 per axis.
    CHECK(openclip::applyRefPoseJson(r, "{\"head\":[0,0,500]}"));
    CHECK_EQ(r.pose["head"][2], 180.0f);
    // Single number = yaw about Y.
    CHECK(openclip::applyRefPoseJson(r, "{\"hips\":45}"));
    CHECK_EQ(r.pose["hips"][0], 0.0f);
    CHECK_EQ(r.pose["hips"][1], 45.0f);
    CHECK_EQ(r.pose["hips"][2], 0.0f);
    std::remove(rig.c_str());
}

TEST(ref_pose_moves_overlay) {
    const std::string rig = "/tmp/openclip_rig.glb";
    CHECK(writeRigGlb(rig));
    RefModel m;
    std::string err;
    CHECK(loadRefModel(rig, m, err));
    const std::string joints = openclip::refJointsJson(m);
    CHECK(joints.find("\"name\":\"hips\"") != std::string::npos);
    CHECK(joints.find("\"name\":\"head\",\"parent\":0") != std::string::npos);
    const int w = 256, h = 256;
    RefView v;
    v.dist = 3;
    v.fov = 45;
    std::vector<std::uint8_t> rest(static_cast<std::size_t>(w) * h * 4, 0);
    int d0 = 0;
    renderRefEdges(m, v, w, h, rest, d0);
    CHECK(d0 > 0);
    CHECK(openclip::applyRefPoseJson(m, "{\"head\":[0,45,0]}"));
    std::vector<std::uint8_t> posed(static_cast<std::size_t>(w) * h * 4, 0);
    int d1 = 0;
    renderRefEdges(m, v, w, h, posed, d1);
    CHECK(d1 > 0);
    CHECK(rest != posed);  // yaw 45 on the head subtree moves the overlay
    // Identity pose renders exactly like rest (no-op merge).
    CHECK(openclip::applyRefPoseJson(m, "{\"head\":[0,0,0]}"));
    std::vector<std::uint8_t> ident(static_cast<std::size_t>(w) * h * 4, 0);
    int d2 = 0;
    renderRefEdges(m, v, w, h, ident, d2);
    CHECK(ident == rest);
    std::remove(rig.c_str());
}

// --- T4 decor scene (ARCHITECTURE.md "T4 additions" §4) ---

TEST(ref_primitive_cube_shell) {
    openclip::RefSceneItem item;
    std::string err;
    CHECK(openclip::buildPrimitive("cube", item, err));
    CHECK(item.loaded);
    CHECK(!item.hasUV);  // planar painting, announced
    CHECK_EQ(item.model.edges.size(), (std::size_t)18);  // 12 sides + 6 face diagonals (same as the M9 fixture)
    CHECK_EQ(item.model.tris.size(), (std::size_t)12);
    CHECK(!openclip::buildPrimitive("torus", item, err));
    CHECK(!err.empty());
    for (const char* k : {"sphere", "plane", "cylinder"}) {
        openclip::RefSceneItem p;
        std::string e2;
        CHECK(openclip::buildPrimitive(k, p, e2));
        CHECK(p.loaded && !p.model.tris.empty() && !p.model.edges.empty());
    }
}

TEST(ref_scene_cube_renders_edges) {
    openclip::RefScene scene;
    openclip::RefSceneItem item;
    std::string err;
    CHECK(openclip::buildPrimitive("cube", item, err));
    scene.items.push_back(item);
    openclip::RefView v;
    v.dist = 5;
    v.fov = 45;
    const int w = 256, h = 256;
    std::vector<std::uint8_t> buf(static_cast<std::size_t>(w) * h * 4, 0);
    int drawn = 0;
    openclip::renderRefScene(scene, v, w, h, buf, drawn);
    CHECK(drawn > 0);
    bool anyAlpha = false;
    for (std::size_t i = 3; i < buf.size(); i += 4)
        if (buf[i] != 0) anyAlpha = true;
    CHECK(anyAlpha);
}

TEST(ref_scene_invisible_absent_empty_scene_clean) {
    openclip::RefScene scene;
    openclip::RefSceneItem item;
    std::string err;
    CHECK(openclip::buildPrimitive("cube", item, err));
    item.visible = false;
    scene.items.push_back(item);
    openclip::RefView v;
    v.dist = 5;
    v.fov = 45;
    const int w = 128, h = 128;
    std::vector<std::uint8_t> buf(static_cast<std::size_t>(w) * h * 4, 0);
    int drawn = 0;
    openclip::renderRefScene(scene, v, w, h, buf, drawn);
    CHECK_EQ(drawn, 0);
    for (auto b : buf) CHECK_EQ(b, 0);
    // Empty scene = composite inchangé.
    openclip::RefScene empty;
    int drawn2 = -1;
    openclip::renderRefScene(empty, v, w, h, buf, drawn2);
    CHECK_EQ(drawn2, 0);
}

TEST(ref_paint_planar_touches_texels_and_tints) {
    openclip::RefScene scene;
    openclip::RefSceneItem item;
    std::string err;
    CHECK(openclip::buildPrimitive("cube", item, err));
    scene.items.push_back(item);
    openclip::RefView v;
    v.dist = 5;
    v.fov = 45;
    const int w = 256, h = 256;
    // Dab at the canvas center (the cube projects there): radius 8px.
    openclip::RefDab d;
    d.x = w / 2.0;
    d.y = h / 2.0;
    d.r = 8;
    d.color[0] = 255;
    d.color[1] = 0;
    d.color[2] = 0;
    d.opacity = 1.0;
    CHECK(openclip::paintRefMap(scene, v, w, h, 0, {d}));
    const auto& tex = scene.items[0].tex;
    CHECK_EQ(tex.size(), (std::size_t)(1024 * 1024 * 4));
    long alphaSum = 0;
    for (std::size_t i = 3; i < tex.size(); i += 4) alphaSum += tex[i];
    CHECK(alphaSum > 0);  // texels touched
    // The painted texture tints the overlay render.
    std::vector<std::uint8_t> buf(static_cast<std::size_t>(w) * h * 4, 0);
    int drawn = 0;
    openclip::renderRefScene(scene, v, w, h, buf, drawn);
    bool anyRed = false;
    for (std::size_t i = 0; i + 3 < buf.size(); i += 4)
        if (buf[i] > 100 && buf[i + 3] > 0) anyRed = true;
    CHECK(anyRed);
    // Empty dabs = true no-op; bad item = false; NaN = false + intact.
    CHECK(openclip::paintRefMap(scene, v, w, h, 0, {}));
    CHECK(!openclip::paintRefMap(scene, v, w, h, 3, {d}));
    openclip::RefDab bad = d;
    bad.x = std::nan("");
    const auto before = tex;
    CHECK(!openclip::paintRefMap(scene, v, w, h, 0, {bad}));
    CHECK(scene.items[0].tex == before);
    // Dab far from the mesh misses but still succeeds.
    openclip::RefDab miss = d;
    miss.x = 2;
    miss.y = 2;
    miss.r = 1;
    CHECK(openclip::paintRefMap(scene, v, w, h, 0, {miss}));
}
