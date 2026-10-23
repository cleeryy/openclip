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
    // T4: triangle faces (triples of vert indices) for paint picking
    // + texture fill. Built alongside edges at load; primitives
    // generate their own. Parallel to `edges` (same source faces).
    std::vector<std::array<std::uint32_t, 3>> tris;
    // T4: per-vert UV (u,v); NaN pair when the mesh has no UV
    // (planar painting then). Same length as verts/3 when present.
    std::vector<float> uvs;
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

// T4 decor scene (ARCHITECTURE.md "T4 additions" §4): multi-item 3D
// reference. Each item is a loaded .glb/.gltf ("file:<path>") or a
// procedural primitive ("prim:cube|sphere|plane|cylinder", from
// scratch — no asset, no UV: planar painting, announced) with its
// own {pos,rot,scale,visible} transform. Painted textures are
// per-item 1024² RGBA surfaces (session-only, hors undo — created on
// the first dab). The overlay renders the tinted texture UNDER the
// wireframe (still an aid, never layer pixels — commit stays an
// explicit nBlitPngIntoLayer bridge).
struct RefSceneItem {
    std::string src;  // "file:<path>" | "prim:<kind>"
    double pos[3] = {0, 0, 0};
    double rot[3] = {0, 0, 0};  // Euler XYZ degrees
    double scale = 1.0;
    bool visible = true;
    bool wire = true;   // commit pipeline: wire:0 hides edges
    bool flat = false;  // commit pipeline: flat:1 renders texture opaque
    RefModel model;
    bool hasUV = false;  // true when the file mesh carries TEXCOORD_0
    bool loaded = false;
    std::vector<std::uint8_t> tex;  // 1024*1024 RGBA, empty = no texture
};

struct RefScene {
    std::vector<RefSceneItem> items;  // 0..8
};

// Build a procedural primitive into `item` (model + edges + tris,
// hasUV = false → planar painting). False on unknown kind.
bool buildPrimitive(const std::string& kind, RefSceneItem& item,
                    std::string& err);

// Stamp dabs {x,y,r,color,opacity}[] (ENGINE-canvas coords, carried
// by the app-side stroke) into the item's 1024² texture (created on
// the first dab). Screen-space triangle picking over the posed mesh
// in the CURRENT view: UV interpolation when the mesh has UV, planar
// bbox map otherwise (projection frozen at stroke time, announced).
// Dabs missing the mesh are ignored. Meta-texture hors undo. True on
// success (even when every dab misses); false on bad item index or
// malformed dabs.
struct RefDab {
    double x = 0, y = 0, r = 1;
    std::uint8_t color[3] = {0, 0, 0};
    double opacity = 1.0;
};
bool paintRefMap(RefScene& scene, const RefView& v, int w, int h,
                 int item, const std::vector<RefDab>& dabs);

// Rasterize the scene over the premultiplied RGBA8 buffer (w*h,
// row-major): tinted texture fill first (flat:1 = opaque, else 50%),
// then 1px dark-gray wireframe edges (same style as M9). drawnEdges
// counts edges with at least one canvas pixel. Texture-less items
// draw wireframe only. Session view shared with the M9 single model.
void renderRefScene(RefScene& scene, const RefView& v, int w, int h,
                    std::vector<std::uint8_t>& rgba, int& drawnEdges);

// Model triangles for paint picking + texture fill: triples of vert
// indices into RefModel::verts (built alongside edges at load;
// primitives generate their own). Parallel to edges (same faces).
// NOTE: RefModel gains `tris` + `uvs` (per-vert u,v; NaN pair when
// the mesh has no UV) — filled by loadRefModel and buildPrimitive.

}  // namespace openclip

#endif
