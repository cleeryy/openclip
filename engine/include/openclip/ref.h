// SPDX-License-Identifier: GPL-3.0-or-later
// M9 glTF reference model (ARCHITECTURE.md "M9 additions"): load .glb
// meshes (positions + triangle edges only) via vendored cgltf, project
// with a perspective camera (+ optional fisheye) and rasterize edges as
// a tracing overlay. Clean-room: Khronos glTF 2.0 only.
#ifndef OPENCLIP_REF_H
#define OPENCLIP_REF_H

#include <array>
#include <cstddef>
#include <cstdint>
#include <map>
#include <string>
#include <utility>
#include <vector>

namespace openclip {

// V+3 pose rig (ARCHITECTURE.md "V+3 additions"): named glTF scene
// nodes are addressable bones.
struct RefJoint {
    std::string name;
    int parent = -1;        // index into RefModel::joints, -1 = root
    float pos[3] = {0, 0, 0};  // rest translation
};

// Triangle-edge wireframe of the first scene's meshes.
struct RefModel {
    // V+3: verts are NODE-LOCAL (pre-transform); render composes the
    // (possibly posed) world matrices on the fly. vertNode[i] is the
    // owning node id, or UINT32_MAX when unassigned.
    std::vector<float> verts;  // x,y,z per vertex (node-local space)
    std::vector<std::pair<std::uint32_t, std::uint32_t>> edges;  // unique
    // V+3 node table (parallel arrays; index = node id). Names are the
    // glTF node names (empty when unnamed); nodeLocal holds the REST
    // local matrix (row-major); nodePos the rest translation.
    std::vector<std::string> nodeNames;
    std::vector<int> nodeParents;  // -1 = scene root
    std::vector<std::array<double, 16>> nodeLocal;
    std::vector<float> nodePos;  // xyz per node
    std::vector<std::uint32_t> vertNode;
    // V+3 joints: NAMED nodes only (unnamed helpers and the M9 cube
    // fixture are excluded — a model with no named nodes reports no
    // joints). parent indexes into `joints`, -1 = root.
    std::vector<RefJoint> joints;
    // V+3 pose: bone name -> Euler XYZ degrees (clamped ±180).
    std::map<std::string, std::array<float, 3>> pose;
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

// Load positions + triangle edges from a .glb/.gltf. Verts stay
// node-local (V+3: world matrices compose at render so poses apply);
// node names feed the joints table. False on missing/malformed input;
// err carries a human-readable reason.
bool loadRefModel(const std::string& path, RefModel& out, std::string& err);

// Merge the keys present in json into v (unknown keys ignored, M3
// rule). fov and fisheye are clamped to their contract ranges.
void parseRefViewJson(const std::string& json, RefView& v);

// Rasterize the model edges over the premultiplied RGBA8 buffer (w*h,
// row-major) as 1px dark-gray 50%-alpha lines (straight 96,96,96,128
// source-over). drawnEdges counts edges with at least one canvas pixel.
// V+3: each vertex renders through its node's (possibly posed) world
// matrix — rigid per-node skinning, pose stored on the model.
void renderRefEdges(const RefModel& m, const RefView& v, int w, int h,
                    std::vector<std::uint8_t>& rgba, int& drawnEdges);

// V+3 joints introspection (ARCHITECTURE.md "V+3 additions"): named
// nodes as {"joints":[{"name","parent","pos":[x,y,z]}]}; unnamed models
// (M9 cube fixture) report {"joints":[]}.
std::string refJointsJson(const RefModel& m);

// V+3 pose merge (ARCHITECTURE.md "V+3 additions"): {"bone":deg|[x,y,z]}
// (single number = yaw about Y). Unknown bones ignored (M3 rule), each
// axis clamped to ±180, unparseable entries ignored. Always succeeds.
bool applyRefPoseJson(RefModel& m, const std::string& json);

}  // namespace openclip

#endif
