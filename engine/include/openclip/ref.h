// SPDX-License-Identifier: GPL-3.0-or-later
// M9 glTF reference model (ARCHITECTURE.md "M9 additions"): load .glb
// meshes (positions + triangle edges only) via vendored cgltf, project
// with a perspective camera (+ optional fisheye) and rasterize edges as
// a tracing overlay. Clean-room: Khronos glTF 2.0 only.
#ifndef OPENCLIP_REF_H
#define OPENCLIP_REF_H

#include <cstddef>
#include <cstdint>
#include <string>
#include <utility>
#include <vector>

namespace openclip {

// Triangle-edge wireframe of the first scene's meshes.
struct RefModel {
    std::vector<float> verts;  // x,y,z per vertex (world space, baked)
    std::vector<std::pair<std::uint32_t, std::uint32_t>> edges;  // unique
};

// Camera/view state. parseRefViewJson updates only keys present.
struct RefView {
    double yaw = 0.0;
    double pitch = 0.0;
    double roll = 0.0;
    double dist = 3.0;
    double fov = 45.0;      // clamped 10..120
    double fisheye = 0.0;   // clamped 0..1 (0 = rectilinear)
    double offX = 0.0;
    double offY = 0.0;
    bool visible = false;
};

// Load positions + triangle edges from a .glb/.gltf. Bake node
// transforms (matrix or TRS, parent chain) into world space. False on
// missing/malformed input; err carries a human-readable reason.
bool loadRefModel(const std::string& path, RefModel& out, std::string& err);

// Merge the keys present in json into v (unknown keys ignored, M3
// rule). fov and fisheye are clamped to their contract ranges.
void parseRefViewJson(const std::string& json, RefView& v);

// Rasterize the model edges over the premultiplied RGBA8 buffer (w*h,
// row-major) as 1px dark-gray 50%-alpha lines (straight 96,96,96,128
// source-over). drawnEdges counts edges with at least one canvas pixel.
void renderRefEdges(const RefModel& m, const RefView& v, int w, int h,
                    std::vector<std::uint8_t>& rgba, int& drawnEdges);

}  // namespace openclip

#endif
