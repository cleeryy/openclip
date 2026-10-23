// SPDX-License-Identifier: GPL-3.0-or-later
#include "openclip/ref.h"

#include <cmath>
#include <cstdlib>
#include <cstring>
#include <algorithm>
#include <set>
#include <string>
#include <utility>

#define CGLTF_IMPLEMENTATION
#include "cgltf.h"

namespace openclip {

namespace {

constexpr double kPi = 3.14159265358979323846;

// Node LOCAL transform: matrix or TRS. Row-major Mat4; parent
// composition happens in posedWorld below (V+3: poses apply).
struct Mat4 {
    double m[16];
    Mat4() { identity(); }
    void identity() {
        std::memset(m, 0, sizeof m);
        m[0] = m[5] = m[10] = m[15] = 1.0;
    }
};

void mulMat(const Mat4& a, const Mat4& b, Mat4& out) {
    Mat4 r;
    for (int i = 0; i < 4; ++i)
        for (int j = 0; j < 4; ++j) {
            double s = 0;
            for (int k = 0; k < 4; ++k)
                s += a.m[i * 4 + k] * b.m[k * 4 + j];
            r.m[i * 4 + j] = s;
        }
    out = r;
}

void applyMat(const Mat4& m, double x, double y, double z, double& ox,
              double& oy, double& oz) {
    ox = m.m[0] * x + m.m[1] * y + m.m[2] * z + m.m[3];
    oy = m.m[4] * x + m.m[5] * y + m.m[6] * z + m.m[7];
    oz = m.m[8] * x + m.m[9] * y + m.m[10] * z + m.m[11];
}

void nodeLocal(const cgltf_node* n, Mat4& out) {
    if (n->has_matrix) {
        // glTF matrices are column-major; transpose into row-major.
        for (int c = 0; c < 4; ++c)
            for (int r = 0; r < 4; ++r)
                out.m[r * 4 + c] = n->matrix[c * 4 + r];
    } else {
        // TRS: T * R * S, column-major assembly transposed below.
        double tx = n->has_translation ? n->translation[0] : 0;
        double ty = n->has_translation ? n->translation[1] : 0;
        double tz = n->has_translation ? n->translation[2] : 0;
        double q[4] = {0, 0, 0, 1};
        if (n->has_rotation)
            for (int i = 0; i < 4; ++i) q[i] = n->rotation[i];
        double sx = n->has_scale ? n->scale[0] : 1;
        double sy = n->has_scale ? n->scale[1] : 1;
        double sz = n->has_scale ? n->scale[2] : 1;
        const double x = q[0], y = q[1], z = q[2], w = q[3];
        // Row-major rotation from quaternion.
        Mat4 r;
        r.m[0] = 1 - 2 * (y * y + z * z);
        r.m[1] = 2 * (x * y - z * w);
        r.m[2] = 2 * (x * z + y * w);
        r.m[3] = tx;
        r.m[4] = 2 * (x * y + z * w);
        r.m[5] = 1 - 2 * (x * x + z * z);
        r.m[6] = 2 * (y * z - x * w);
        r.m[7] = ty;
        r.m[8] = 2 * (x * z - y * w);
        r.m[9] = 2 * (y * z + x * w);
        r.m[10] = 1 - 2 * (x * x + y * y);
        r.m[11] = tz;
        r.m[12] = 0;
        r.m[13] = 0;
        r.m[14] = 0;
        r.m[15] = 1;
        // Scale columns 0..2.
        r.m[0] *= sx; r.m[4] *= sx; r.m[8] *= sx;
        r.m[1] *= sy; r.m[5] *= sy; r.m[9] *= sy;
        r.m[2] *= sz; r.m[6] *= sz; r.m[10] *= sz;
        out = r;
    }
}

// V+3: Euler XYZ (degrees) matrix, R = Rz*Ry*Rx.
void eulerMat(float xDeg, float yDeg, float zDeg, Mat4& out) {
    const double x = xDeg * kPi / 180.0, y = yDeg * kPi / 180.0,
                 z = zDeg * kPi / 180.0;
    const double cx = std::cos(x), sx = std::sin(x);
    const double cy = std::cos(y), sy = std::sin(y);
    const double cz = std::cos(z), sz = std::sin(z);
    // Rx, Ry, Rz row-major, then R = Rz*Ry*Rx.
    Mat4 rx, ry, rz, t;
    rx.m[0] = 1; rx.m[1] = 0; rx.m[2] = 0; rx.m[3] = 0;
    rx.m[4] = 0; rx.m[5] = cx; rx.m[6] = -sx; rx.m[7] = 0;
    rx.m[8] = 0; rx.m[9] = sx; rx.m[10] = cx; rx.m[11] = 0;
    ry.m[0] = cy; ry.m[1] = 0; ry.m[2] = sy; ry.m[3] = 0;
    ry.m[4] = 0; ry.m[5] = 1; ry.m[6] = 0; ry.m[7] = 0;
    ry.m[8] = -sy; ry.m[9] = 0; ry.m[10] = cy; ry.m[11] = 0;
    rz.m[0] = cz; rz.m[1] = -sz; rz.m[2] = 0; rz.m[3] = 0;
    rz.m[4] = sz; rz.m[5] = cz; rz.m[6] = 0; rz.m[7] = 0;
    rz.m[8] = 0; rz.m[9] = 0; rz.m[10] = 1; rz.m[11] = 0;
    mulMat(ry, rx, t);
    mulMat(rz, t, out);
}

void rotatePoint(const RefView& v, double x, double y, double z, double& ox,
              double& oy, double& oz);

// T4 shared projection core (M9 path unchanged, scene reuses it):
// posed world matrices, view projection params, per-vert project.
void computeWorlds(const RefModel& m, std::vector<Mat4>& worlds) {
    const std::size_t nn = m.nodeLocal.size();
    worlds.assign(nn, Mat4());
    std::vector<char> done(nn, 0);
    for (std::size_t ni = 0; ni < nn; ++ni) {
        // Preorder walk ids: parents resolve before children, so a
        // single forward pass composes every parent.
        Mat4 local;
        for (int k = 0; k < 16; ++k) local.m[k] = m.nodeLocal[ni][k];
        const std::string& nm =
            ni < m.nodeNames.size() ? m.nodeNames[ni] : std::string();
        const auto pit = nm.empty() ? m.pose.end() : m.pose.find(nm);
        if (pit != m.pose.end()) {
            Mat4 e, tinv, t1, t2;
            eulerMat(pit->second[0], pit->second[1], pit->second[2], e);
            const double tx = (ni * 3 + 2 < m.nodePos.size())
                                  ? m.nodePos[ni * 3]
                                  : 0;
            const double ty = (ni * 3 + 2 < m.nodePos.size())
                                  ? m.nodePos[ni * 3 + 1]
                                  : 0;
            const double tz = (ni * 3 + 2 < m.nodePos.size())
                                  ? m.nodePos[ni * 3 + 2]
                                  : 0;
            tinv.m[3] = -tx;
            tinv.m[7] = -ty;
            tinv.m[11] = -tz;
            mulMat(tinv, local, t1);
            mulMat(e, t1, t2);
            Mat4 t;
            t.m[3] = tx;
            t.m[7] = ty;
            t.m[11] = tz;
            mulMat(t, t2, local);
        }
        const int p = (ni < m.nodeParents.size()) ? m.nodeParents[ni] : -1;
        if (p >= 0 && static_cast<std::size_t>(p) < nn && done[p]) {
            Mat4 c;
            mulMat(worlds[p], local, c);
            worlds[ni] = c;
        } else {
            worlds[ni] = local;
        }
        done[ni] = 1;
    }
}

struct Proj {
    const RefView* v = nullptr;
    double f = 1.0, k = 0.0, cx = 0.0, cy = 0.0;
};

Proj makeProj(const RefView& v, int w, int h) {
    Proj p;
    p.v = &v;
    p.f = (h / 2.0) / std::tan(v.fov * kPi / 360.0);
    p.k = v.fisheye * 0.001;
    p.cx = w / 2.0 + v.offX;
    p.cy = h / 2.0 + v.offY;
    return p;
}

// Project vert `vi` through (posed world, then optional item matrix)
// into screen px. Returns false when behind the camera. `depth` (when
// non-null) receives the view-space distance (for paint picking).
bool projectVert(const RefModel& m, const std::vector<Mat4>& worlds,
                 const Mat4* item, std::size_t vi, const Proj& pr,
                 double& px, double& py, double* depth) {
    const std::size_t nn = worlds.size();
    const std::uint32_t nid =
        (vi < m.vertNode.size()) ? m.vertNode[vi] : 0xFFFFFFFFu;
    double wx = m.verts[vi * 3], wy = m.verts[vi * 3 + 1],
           wz = m.verts[vi * 3 + 2];
    if (nid < nn && nid < m.nodeLocal.size()) {
        double ox, oy, oz;
        applyMat(worlds[nid], wx, wy, wz, ox, oy, oz);
        wx = ox;
        wy = oy;
        wz = oz;
    }
    if (item) {
        double ox, oy, oz;
        applyMat(*item, wx, wy, wz, ox, oy, oz);
        wx = ox;
        wy = oy;
        wz = oz;
    }
    double rx, ry, rz;
    rotatePoint(*pr.v, wx, wy, wz, rx, ry, rz);
    const double z = rz - pr.v->dist;
    if (z > -1e-6) return false;
    if (depth) *depth = -z;
    double x = rx * pr.f / -z;
    double y = -ry * pr.f / -z;
    const double r = std::sqrt(x * x + y * y);
    if (pr.k > 0 && r > 1e-6) {
        const double rp = r * (1.0 + pr.k * r * r);
        x *= rp / r;
        y *= rp / r;
    }
    px = x + pr.cx;
    py = y + pr.cy;
    return true;
}

// Item transform: T(pos) * E(rot XYZ) * S(scale).
Mat4 itemMatrix(const RefSceneItem& it) {
    Mat4 t, r, s, rs, out;
    t.m[3] = it.pos[0];
    t.m[7] = it.pos[1];
    t.m[11] = it.pos[2];
    eulerMat(static_cast<float>(it.rot[0]), static_cast<float>(it.rot[1]),
             static_cast<float>(it.rot[2]), r);
    const double sc = (it.scale > 0) ? it.scale : 1.0;
    s.m[0] = sc;
    s.m[5] = sc;
    s.m[10] = sc;
    mulMat(r, s, rs);
    mulMat(t, rs, out);
    return out;
}

void rotatePoint(const RefView& v, double x, double y, double z, double& ox,
                 double& oy, double& oz) {
    const double yr = v.yaw * kPi / 180.0;
    const double pr = v.pitch * kPi / 180.0;
    const double rr = v.roll * kPi / 180.0;
    // Apply pitch (X), yaw (Y), roll (Z).
    const double cp = std::cos(pr), sp = std::sin(pr);
    double y1 = y * cp - z * sp, z1 = y * sp + z * cp;
    const double cy = std::cos(yr), sy = std::sin(yr);
    double x1 = x * cy + z1 * sy, z2 = -x * sy + z1 * cy;
    const double cr = std::cos(rr), sr = std::sin(rr);
    ox = x1 * cr - y1 * sr;
    oy = x1 * sr + y1 * cr;
    oz = z2;
}

}  // namespace

bool loadRefModel(const std::string& path, RefModel& out,
                  std::string& err) {
    out.verts.clear();
    out.edges.clear();
    out.tris.clear();
    out.uvs.clear();
    cgltf_options options = {};
    std::memset(&options, 0, sizeof options);
    cgltf_data* data = nullptr;
    const cgltf_result pr = cgltf_parse_file(&options, path.c_str(), &data);
    if (pr != cgltf_result_success || !data) {
        err = "parse failed";
        return false;
    }
    if (cgltf_load_buffers(&options, data, path.c_str()) !=
        cgltf_result_success) {
        err = "buffer load failed";
        cgltf_free(data);
        return false;
    }
    bool ok = false;
    if (data->scenes_count > 0) {
        // cgltf_data::scene is the DEFAULT SCENE POINTER (not an index).
        const cgltf_scene* scene =
            data->scene ? data->scene : &data->scenes[0];
        std::vector<std::pair<std::uint32_t, std::uint32_t>> tris;
        std::vector<float> verts;
        std::vector<std::uint32_t> vertNode;
        // T4: faces (vert triples) + per-vert UV (NaN when absent).
        std::vector<std::array<std::uint32_t, 3>> faces;
        std::vector<float> uvs;
        // V+3: full node-tree walk (roots = scene nodes, then children).
        // Node ids are walk order; unnamed nodes still own geometry but
        // never appear in the joints table.
        std::vector<const cgltf_node*> order;
        std::vector<int> orderParent;
        std::vector<const cgltf_node*> stack;
        std::vector<int> stackParent;
        for (std::size_t ni = 0; ni < scene->nodes_count; ++ni) {
            stack.push_back(scene->nodes[ni]);
            stackParent.push_back(-1);
        }
        while (!stack.empty()) {
            const cgltf_node* node = stack.back();
            const int par = stackParent.back();
            stack.pop_back();
            stackParent.pop_back();
            if (!node) continue;
            const int id = static_cast<int>(order.size());
            order.push_back(node);
            orderParent.push_back(par);
            for (std::size_t ci = 0; ci < node->children_count; ++ci) {
                stack.push_back(node->children[ci]);
                stackParent.push_back(id);
            }
        }
        out.nodeNames.clear();
        out.nodeParents = orderParent;
        out.nodeLocal.clear();
        out.nodePos.clear();
        for (const cgltf_node* node : order) {
            out.nodeNames.push_back(node->name ? node->name : "");
            Mat4 local;
            nodeLocal(node, local);
            std::array<double, 16> lm;
            for (int k = 0; k < 16; ++k) lm[k] = local.m[k];
            out.nodeLocal.push_back(lm);
            const double tx = node->has_translation ? node->translation[0] : 0;
            const double ty = node->has_translation ? node->translation[1] : 0;
            const double tz = node->has_translation ? node->translation[2] : 0;
            out.nodePos.push_back(static_cast<float>(tx));
            out.nodePos.push_back(static_cast<float>(ty));
            out.nodePos.push_back(static_cast<float>(tz));
        }
        // V+3 joints: named nodes; parent = nearest named ancestor.
        out.joints.clear();
        std::vector<int> nodeToJoint(order.size(), -1);
        for (std::size_t ni = 0; ni < order.size(); ++ni) {
            if (out.nodeNames[ni].empty()) continue;
            int jp = -1;
            int p = orderParent[ni];
            while (p >= 0) {
                if (nodeToJoint[static_cast<std::size_t>(p)] >= 0) {
                    jp = nodeToJoint[static_cast<std::size_t>(p)];
                    break;
                }
                p = orderParent[static_cast<std::size_t>(p)];
            }
            RefJoint j;
            j.name = out.nodeNames[ni];
            j.parent = jp;
            j.pos[0] = out.nodePos[ni * 3];
            j.pos[1] = out.nodePos[ni * 3 + 1];
            j.pos[2] = out.nodePos[ni * 3 + 2];
            nodeToJoint[ni] = static_cast<int>(out.joints.size());
            out.joints.push_back(j);
        }
        for (std::size_t ni = 0; ni < order.size(); ++ni) {
            const cgltf_node* node = order[ni];
            if (!node || !node->mesh) continue;
            const std::uint32_t nid = static_cast<std::uint32_t>(ni);
            for (std::size_t pi = 0; pi < node->mesh->primitives_count;
                 ++pi) {
                const cgltf_primitive& prim = node->mesh->primitives[pi];
                const cgltf_accessor* pos = nullptr;
                const cgltf_accessor* idx = prim.indices;
                // T4: TEXCOORD_0 feeds per-vert UV (planar fallback
                // when absent — primitives never carry UV).
                const cgltf_accessor* tex = nullptr;
                for (std::size_t ai = 0; ai < prim.attributes_count; ++ai) {
                    if (prim.attributes[ai].type == cgltf_attribute_type_position)
                        pos = prim.attributes[ai].data;
                    if (prim.attributes[ai].type == cgltf_attribute_type_texcoord)
                        tex = prim.attributes[ai].data;
                }
                if (!pos || pos->component_type != cgltf_component_type_r_32f ||
                    pos->type != cgltf_type_vec3 || pos->is_sparse)
                    continue;
                const bool texOk =
                    tex && tex->component_type == cgltf_component_type_r_32f &&
                    tex->type == cgltf_type_vec2 && !tex->is_sparse &&
                    tex->buffer_view && tex->buffer_view->buffer &&
                    tex->buffer_view->buffer->data &&
                    tex->count == pos->count;
                const std::uint8_t* tbase = nullptr;
                std::size_t tstride = sizeof(float) * 2;
                if (texOk) {
                    tbase = static_cast<const std::uint8_t*>(
                                tex->buffer_view->buffer->data) +
                            tex->offset + tex->buffer_view->offset;
                    tstride = tex->buffer_view->stride ? tex->buffer_view->stride
                                                      : sizeof(float) * 2;
                }
                const std::uint8_t* base = static_cast<const std::uint8_t*>(
                    pos->buffer_view->buffer->data) +
                    pos->offset + pos->buffer_view->offset;
                const std::size_t stride =
                    pos->buffer_view->stride ? pos->buffer_view->stride
                                             : sizeof(float) * 3;
                const std::uint32_t first = static_cast<std::uint32_t>(
                    verts.size() / 3);
                for (std::size_t i = 0; i < pos->count; ++i) {
                    float lx, ly, lz;
                    std::memcpy(&lx, base + i * stride, sizeof(float));
                    std::memcpy(&ly, base + i * stride + 4, sizeof(float));
                    std::memcpy(&lz, base + i * stride + 8, sizeof(float));
                    // V+3: verts stay node-local; the (posed) world
                    // matrix composes at render time.
                    verts.push_back(lx);
                    verts.push_back(ly);
                    verts.push_back(lz);
                    vertNode.push_back(nid);
                    if (texOk) {
                        float tu, tv;
                        std::memcpy(&tu, tbase + i * tstride, sizeof(float));
                        std::memcpy(&tv, tbase + i * tstride + 4,
                                    sizeof(float));
                        uvs.push_back(tu);
                        uvs.push_back(tv);
                    } else {
                        const float nan = std::nanf("");
                        uvs.push_back(nan);
                        uvs.push_back(nan);
                    }
                }
                // T4 faces alongside the edge pairs below.
                auto pushFace = [&](std::uint32_t a, std::uint32_t b,
                                    std::uint32_t c) {
                    faces.push_back({a, b, c});
                };
                // Indices: unsigned short or unsigned int only.
                if (idx && idx->count >= 3 &&
                    (idx->component_type == cgltf_component_type_r_16u ||
                     idx->component_type == cgltf_component_type_r_32u)) {
                    const std::uint8_t* ibase =
                        static_cast<const std::uint8_t*>(
                            idx->buffer_view->buffer->data) +
                        idx->offset + idx->buffer_view->offset;
                    for (std::size_t i = 0; i + 2 < idx->count; i += 3) {
                        std::uint32_t a, b, c;
                        if (idx->component_type == cgltf_component_type_r_16u) {
                            std::uint16_t ua, ub, uc;
                            std::memcpy(&ua, ibase + (i) * 2, 2);
                            std::memcpy(&ub, ibase + (i + 1) * 2, 2);
                            std::memcpy(&uc, ibase + (i + 2) * 2, 2);
                            a = ua; b = ub; c = uc;
                        } else {
                            std::memcpy(&a, ibase + i * 4, 4);
                            std::memcpy(&b, ibase + (i + 1) * 4, 4);
                            std::memcpy(&c, ibase + (i + 2) * 4, 4);
                        }
                        tris.emplace_back(first + a, first + b);
                        tris.emplace_back(first + b, first + c);
                        tris.emplace_back(first + c, first + a);
                        pushFace(first + a, first + b, first + c);
                    }
                } else {
                    // Non-indexed: consecutive triples.
                    for (std::size_t i = 0; i + 2 < pos->count; i += 3) {
                        tris.emplace_back(first + static_cast<std::uint32_t>(i),
                                          first + static_cast<std::uint32_t>(i + 1));
                        tris.emplace_back(first + static_cast<std::uint32_t>(i + 1),
                                          first + static_cast<std::uint32_t>(i + 2));
                        tris.emplace_back(first + static_cast<std::uint32_t>(i + 2),
                                          first + static_cast<std::uint32_t>(i));
                        pushFace(first + static_cast<std::uint32_t>(i),
                                 first + static_cast<std::uint32_t>(i + 1),
                                 first + static_cast<std::uint32_t>(i + 2));
                    }
                }
            }
        }
        // Dedupe edges (sorted pair).
        std::set<std::pair<std::uint32_t, std::uint32_t>> unique;
        for (const auto& e : tris) {
            const std::uint32_t a = std::min(e.first, e.second);
            const std::uint32_t b = std::max(e.first, e.second);
            if (a != b) unique.insert({a, b});
        }
        for (const auto& e : unique) out.edges.push_back(e);
        out.verts.swap(verts);
        out.vertNode.swap(vertNode);
        out.tris.swap(faces);
        out.uvs.swap(uvs);
        out.pose.clear();
        ok = !out.verts.empty();
    }
    cgltf_free(data);
    if (!ok) err = "no mesh geometry";
    return ok;
}

void parseRefViewJson(const std::string& json, RefView& v) {
    auto has = [&](const char* k) {
        return json.find(std::string("\"") + k + "\"") != std::string::npos;
    };
    auto num = [&](const char* k, double def) -> double {
        const std::size_t p = json.find(std::string("\"") + k + "\"");
        if (p == std::string::npos) return def;
        const std::size_t c = json.find(':', p);
        if (c == std::string::npos) return def;
        try {
            return std::stod(json.substr(c + 1));
        } catch (...) {
            return def;
        }
    };
    if (has("yaw")) v.yaw = num("yaw", v.yaw);
    if (has("pitch")) v.pitch = num("pitch", v.pitch);
    if (has("roll")) v.roll = num("roll", v.roll);
    if (has("dist")) v.dist = num("dist", v.dist);
    if (has("fov")) v.fov = num("fov", v.fov);
    if (has("fisheye")) v.fisheye = num("fisheye", v.fisheye);
    if (has("offX")) v.offX = num("offX", v.offX);
    if (has("offY")) v.offY = num("offY", v.offY);
    if (v.fov < 10) v.fov = 10;
    if (v.fov > 120) v.fov = 120;
    if (v.fisheye < 0) v.fisheye = 0;
    if (v.fisheye > 1) v.fisheye = 1;
}

void renderRefEdges(const RefModel& m, const RefView& v, int w, int h,
                    std::vector<std::uint8_t>& rgba, int& drawnEdges) {
    drawnEdges = 0;
    if (w <= 0 || h <= 0 || rgba.size() != static_cast<std::size_t>(w) * h * 4)
        return;
    // V+3 posed world matrices: world(n) = parent * T * E * T^-1 * local,
    // where E is the stored Euler pose (identity when no pose entry).
    // Rotation happens about the node's rest translation (the joint).
    const std::size_t nn = m.nodeLocal.size();
    std::vector<Mat4> worlds(nn);
    computeWorlds(m, worlds);
    Proj pr = makeProj(v, w, h);
    auto project = [&](std::size_t vi, double& px, double& py) {
        return projectVert(m, worlds, nullptr, vi, pr, px, py, nullptr);
    };
    for (const auto& e : m.edges) {
        double ax, ay, bx, by;
        if (!project(e.first, ax, ay) || !project(e.second, bx, by)) continue;
        const double dx = bx - ax, dy = by - ay;
        const int steps =
            static_cast<int>(std::max(std::fabs(dx), std::fabs(dy))) + 1;
        bool any = false;
        for (int i = 0; i <= steps; ++i) {
            const double t = steps > 0 ? static_cast<double>(i) / steps : 0;
            const int px = static_cast<int>(ax + dx * t);
            const int py = static_cast<int>(ay + dy * t);
            if (px < 0 || px >= w || py < 0 || py >= h) continue;
            std::uint8_t* d = &rgba[static_cast<std::size_t>(py) * w * 4 +
                                    px * 4];
            // Straight (96,96,96,128) source-over premultiplied.
            d[0] = static_cast<std::uint8_t>(48 + d[0] * 127 / 255);
            d[1] = static_cast<std::uint8_t>(48 + d[1] * 127 / 255);
            d[2] = static_cast<std::uint8_t>(48 + d[2] * 127 / 255);
            d[3] = static_cast<std::uint8_t>(128 + d[3] * 127 / 255);
            any = true;
        }
        if (any) ++drawnEdges;
    }
}

std::string refJointsJson(const RefModel& m) {
    std::string out = "{\"joints\":[";
    for (std::size_t i = 0; i < m.joints.size(); ++i) {
        const RefJoint& j = m.joints[i];
        if (i > 0) out += ',';
        // Names are glTF-controlled; escape quotes/backslashes only.
        std::string safe;
        for (char ch : j.name) {
            if (ch == '"' || ch == '\\') safe += '\\';
            safe += ch;
        }
        out += "{\"name\":\"" + safe + "\",\"parent\":" +
               std::to_string(j.parent) + ",\"pos\":[" +
               std::to_string(j.pos[0]) + "," + std::to_string(j.pos[1]) +
               "," + std::to_string(j.pos[2]) + "]}";
    }
    out += "]}";
    return out;
}

bool applyRefPoseJson(RefModel& m, const std::string& json) {
    // Flat top-level scan: "bone": deg | [x,y,z]. Unknown bones (no
    // named node) and unparseable entries are ignored; each axis is
    // clamped to ±180. Always succeeds (never fails, like clamps).
    auto isBone = [&](const std::string& name) {
        for (const auto& j : m.joints)
            if (j.name == name) return true;
        return false;
    };
    std::size_t p = 0;
    while (p < json.size()) {
        const std::size_t q1 = json.find('"', p);
        if (q1 == std::string::npos) break;
        const std::size_t q2 = json.find('"', q1 + 1);
        if (q2 == std::string::npos) break;
        const std::string key = json.substr(q1 + 1, q2 - q1 - 1);
        const std::size_t colon = json.find(':', q2 + 1);
        if (colon == std::string::npos) break;
        std::size_t v = colon + 1;
        while (v < json.size() &&
               (json[v] == ' ' || json[v] == '\t' || json[v] == '\n' ||
                json[v] == '\r'))
            ++v;
        float vals[3] = {0, 0, 0};
        bool have = false;
        if (v < json.size() && json[v] == '[') {
            ++v;
            for (int k = 0; k < 3; ++k) {
                while (v < json.size() &&
                       (json[v] == ' ' || json[v] == '\t' ||
                        json[v] == ',' || json[v] == '\n' ||
                        json[v] == '\r'))
                    ++v;
                if (v < json.size() && json[v] == ']') break;
                char* e = nullptr;
                const double d = std::strtod(json.c_str() + v, &e);
                if (e == json.c_str() + v) break;
                vals[k] = static_cast<float>(d);
                have = true;
                v = static_cast<std::size_t>(e - json.c_str());
            }
            const std::size_t close = json.find(']', v);
            p = (close == std::string::npos) ? json.size() : close + 1;
        } else {
            char* e = nullptr;
            const double d = std::strtod(json.c_str() + v, &e);
            if (e != json.c_str() + v) {
                vals[1] = static_cast<float>(d);  // single number = yaw
                have = true;
                v = static_cast<std::size_t>(e - json.c_str());
            }
            p = v;
        }
        if (have && !key.empty() && isBone(key)) {
            std::array<float, 3> eul;
            for (int k = 0; k < 3; ++k) {
                float a = vals[k];
                if (!(a == a)) a = 0;  // NaN -> 0
                if (a < -180.0f) a = -180.0f;
                if (a > 180.0f) a = 180.0f;
                eul[k] = a;
            }
            m.pose[key] = eul;
        }
    }
    return true;
}

// ---- T4 decor scene (ARCHITECTURE.md "T4 additions" §4) ----

namespace {

// Single-identity-node shell for a procedural primitive.
void primShell(RefSceneItem& item, std::vector<float>& verts,
               const std::vector<std::array<std::uint32_t, 3>>& faces) {
    item.model.verts.swap(verts);
    item.model.tris = faces;
    std::set<std::pair<std::uint32_t, std::uint32_t>> unique;
    for (const auto& f : faces) {
        const std::uint32_t v[3] = {f[0], f[1], f[2]};
        for (int k = 0; k < 3; ++k) {
            const std::uint32_t a = v[k], b = v[(k + 1) % 3];
            if (a != b)
                unique.insert({std::min(a, b), std::max(a, b)});
        }
    }
    item.model.edges.clear();
    for (const auto& e : unique) item.model.edges.push_back(e);
    item.model.vertNode.assign(item.model.verts.size() / 3, 0);
    const float nan = std::nanf("");
    item.model.uvs.assign(item.model.verts.size() / 3 * 2, nan);
    item.model.nodeNames = {""};
    item.model.nodeParents = {-1};
    std::array<double, 16> ident = {1, 0, 0, 0,  0, 1, 0, 0,
                                    0, 0, 1, 0,  0, 0, 0, 1};
    item.model.nodeLocal = {ident};
    item.model.nodePos = {0, 0, 0};
    item.model.joints.clear();
    item.model.pose.clear();
    item.hasUV = false;
    item.loaded = true;
}

}  // namespace

bool buildPrimitive(const std::string& kind, RefSceneItem& item,
                    std::string& err) {
    item = RefSceneItem();
    item.src = "prim:" + kind;
    std::vector<float> verts;
    std::vector<std::array<std::uint32_t, 3>> faces;
    if (kind == "cube") {
        const float v[8][3] = {{-1, -1, -1}, {1, -1, -1}, {1, 1, -1},
                               {-1, 1, -1},  {-1, -1, 1}, {1, -1, 1},
                               {1, 1, 1},    {-1, 1, 1}};
        for (auto& p : v) {
            verts.push_back(p[0]);
            verts.push_back(p[1]);
            verts.push_back(p[2]);
        }
        const std::uint32_t f[12][3] = {
            {0, 1, 2}, {0, 2, 3},  // -z
            {4, 6, 5}, {4, 7, 6},  // +z
            {0, 4, 5}, {0, 5, 1},  // -y
            {3, 2, 6}, {3, 6, 7},  // +y
            {0, 3, 7}, {0, 7, 4},  // -x
            {1, 5, 6}, {1, 6, 2}   // +x
        };
        for (auto& t : f) faces.push_back({t[0], t[1], t[2]});
    } else if (kind == "plane") {
        const float v[4][3] = {{-1, -1, 0}, {1, -1, 0}, {1, 1, 0}, {-1, 1, 0}};
        for (auto& p : v) {
            verts.push_back(p[0]);
            verts.push_back(p[1]);
            verts.push_back(p[2]);
        }
        faces.push_back({0, 1, 2});
        faces.push_back({0, 2, 3});
    } else if (kind == "sphere" || kind == "cylinder") {
        const int segs = 12;
        if (kind == "sphere") {
            const int rings = 6;
            for (int r = 0; r <= rings; ++r) {
                const double th = 3.141592653589793 * r / rings;
                for (int s = 0; s <= segs; ++s) {
                    const double ph = 2 * 3.141592653589793 * s / segs;
                    verts.push_back(
                        static_cast<float>(std::sin(th) * std::cos(ph)));
                    verts.push_back(
                        static_cast<float>(std::cos(th)));
                    verts.push_back(
                        static_cast<float>(std::sin(th) * std::sin(ph)));
                }
            }
            for (int r = 0; r < rings; ++r)
                for (int s = 0; s < segs; ++s) {
                    const std::uint32_t a = r * (segs + 1) + s;
                    const std::uint32_t b = a + 1;
                    const std::uint32_t c = a + segs + 1;
                    const std::uint32_t d = c + 1;
                    faces.push_back({a, c, b});
                    faces.push_back({b, c, d});
                }
        } else {
            for (int s = 0; s <= segs; ++s) {
                const double ph = 2 * 3.141592653589793 * s / segs;
                const float cx = static_cast<float>(std::cos(ph));
                const float sz = static_cast<float>(std::sin(ph));
                verts.push_back(cx);
                verts.push_back(-1);
                verts.push_back(sz);
                verts.push_back(cx);
                verts.push_back(1);
                verts.push_back(sz);
            }
            const std::uint32_t top = static_cast<std::uint32_t>(
                verts.size() / 3);
            verts.push_back(0);
            verts.push_back(1);
            verts.push_back(0);
            const std::uint32_t bot = static_cast<std::uint32_t>(
                verts.size() / 3);
            verts.push_back(0);
            verts.push_back(-1);
            verts.push_back(0);
            for (int s = 0; s < segs; ++s) {
                const std::uint32_t b0 = s * 2, t0 = s * 2 + 1;
                const std::uint32_t b1 = s * 2 + 2, t1 = s * 2 + 3;
                faces.push_back({b0, b1, t1});
                faces.push_back({b0, t1, t0});
                faces.push_back({top, t0, t1});
                faces.push_back({bot, b1, b0});
            }
        }
    } else {
        err = "unknown primitive '" + kind + "'";
        return false;
    }
    primShell(item, verts, faces);
    return true;
}

namespace {

// Projected triangle cache for one paint/render pass.
struct ProjTri {
    double x[3], y[3], d[3];  // screen px + view depth
    std::size_t face = 0;     // index into RefModel::tris
    bool ok = false;
};

void projectItemTris(const RefSceneItem& item, const Proj& pr,
                     std::vector<ProjTri>& out, double& minx, double& miny,
                     double& maxx, double& maxy) {
    out.clear();
    minx = miny = 1e30;
    maxx = maxy = -1e30;
    if (!item.loaded) return;
    const RefModel& m = item.model;
    std::vector<Mat4> worlds;
    computeWorlds(m, worlds);
    const Mat4 im = itemMatrix(item);
    for (std::size_t fi = 0; fi < m.tris.size(); ++fi) {
        const auto& f = m.tris[fi];
        ProjTri t;
        t.ok = true;
        t.face = fi;
        for (int k = 0; k < 3; ++k) {
            if (f[k] * 3 + 2 >= m.verts.size()) {
                t.ok = false;
                break;
            }
            double sx, sy, dep = 0;
            if (!projectVert(m, worlds, &im, f[k], pr, sx, sy, &dep)) {
                t.ok = false;
                break;
            }
            t.x[k] = sx;
            t.y[k] = sy;
            t.d[k] = dep;
        }
        if (!t.ok) continue;
        out.push_back(t);
        for (int k = 0; k < 3; ++k) {
            if (t.x[k] < minx) minx = t.x[k];
            if (t.y[k] < miny) miny = t.y[k];
            if (t.x[k] > maxx) maxx = t.x[k];
            if (t.y[k] > maxy) maxy = t.y[k];
        }
    }
}

bool pointInTri(double px, double py, const ProjTri& t, double& w0,
                double& w1, double& w2) {
    const double d =
        (t.y[1] - t.y[2]) * (t.x[0] - t.x[2]) + (t.x[2] - t.x[1]) * (t.y[0] - t.y[2]);
    if (!(std::fabs(d) > 1e-12)) return false;
    w0 = ((t.y[1] - t.y[2]) * (px - t.x[2]) +
          (t.x[2] - t.x[1]) * (py - t.y[2])) / d;
    w1 = ((t.y[2] - t.y[0]) * (px - t.x[2]) +
          (t.x[0] - t.x[2]) * (py - t.y[2])) / d;
    w2 = 1.0 - w0 - w1;
    const double eps = -1e-6;
    return w0 >= eps && w1 >= eps && w2 >= eps;
}

}  // namespace

bool paintRefMap(RefScene& scene, const RefView& v, int w, int h,
                 int item, const std::vector<RefDab>& dabs) {
    if (w <= 0 || h <= 0) return false;
    if (item < 0 || item >= static_cast<int>(scene.items.size()))
        return false;
    RefSceneItem& it = scene.items[static_cast<std::size_t>(item)];
    if (!it.loaded) return false;
    if (dabs.empty()) return true;
    // Validate dabs first: malformed = false, texture untouched.
    for (const auto& d : dabs) {
        if (!(std::isfinite(d.x) && std::isfinite(d.y) &&
              std::isfinite(d.r) && std::isfinite(d.opacity)))
            return false;
        if (d.r < 0 || d.r > 512 || d.opacity < 0 || d.opacity > 1)
            return false;
    }
    // Project the posed mesh in the CURRENT view (projection frozen
    // at stroke time, announced): dabs are engine-canvas coords, i.e.
    // the live canvas frame (w,h) — the projection centre matches the
    // composite path exactly.
    const Proj pr = makeProj(v, w, h);
    std::vector<ProjTri> tris;
    double minx, miny, maxx, maxy;
    projectItemTris(it, pr, tris, minx, miny, maxx, maxy);
    if (tris.empty()) return true;  // nothing visible: all dabs miss
    constexpr int kTex = 1024;
    if (it.tex.empty()) it.tex.assign(kTex * kTex * 4, 0);
    const double bw = maxx - minx, bh = maxy - miny;
    const double texScale = 1024.0 / ((bw > bh ? bw : bh) > 1e-9
                                         ? (bw > bh ? bw : bh)
                                         : 1.0);
    for (const auto& d : dabs) {
        // Front-most containing triangle (nearest view depth wins).
        const ProjTri* best = nullptr;
        double bu = 0, bv = 0, bw2 = 0, bdep = 0;
        bool have = false;
        for (const auto& t : tris) {
            double w0, w1, w2;
            if (!pointInTri(d.x, d.y, t, w0, w1, w2)) continue;
            const double dep = w0 * t.d[0] + w1 * t.d[1] + w2 * t.d[2];
            if (!have || dep < bdep) {
                have = true;
                best = &t;
                bu = w0;
                bv = w1;
                bw2 = w2;
                bdep = dep;
            }
        }
        if (!have || !best) continue;  // dab missed the mesh
        // UV when carried, else planar over the projected screen bbox.
        double u = 0, vv = 0;
        if (it.hasUV) {
            const auto& f = it.model.tris[best->face];
            const double ws[3] = {bu, bv, bw2};
            for (int k = 0; k < 3; ++k) {
                const std::size_t vi = f[k];
                if (vi * 2 + 1 < it.model.uvs.size()) {
                    u += ws[k] * it.model.uvs[vi * 2];
                    vv += ws[k] * it.model.uvs[vi * 2 + 1];
                }
            }
        } else {
            u = (bw > 1e-9) ? (d.x - minx) / bw : 0;
            vv = (bh > 1e-9) ? (d.y - miny) / bh : 0;
        }
        if (u < 0) u = 0;
        if (u > 1) u = 1;
        if (vv < 0) vv = 0;
        if (vv > 1) vv = 1;
        const double tx = u * (kTex - 1), ty = vv * (kTex - 1);
        double trad = d.r * texScale;
        if (trad < 1.0) trad = 1.0;
        const int x0 = static_cast<int>(std::floor(tx - trad));
        const int x1 = static_cast<int>(std::ceil(tx + trad));
        const int y0 = static_cast<int>(std::floor(ty - trad));
        const int y1 = static_cast<int>(std::ceil(ty + trad));
        for (int y = y0; y <= y1; ++y) {
            if (y < 0 || y >= kTex) continue;
            for (int x = x0; x <= x1; ++x) {
                if (x < 0 || x >= kTex) continue;
                const double dist =
                    std::sqrt((x - tx) * (x - tx) + (y - ty) * (y - ty));
                if (dist > trad) continue;
                // Cosine dab falloff, source-over with dab opacity.
                const double m =
                    (std::cos(3.141592653589793 * dist / trad) + 1.0) * 0.5;
                const double sa = m * d.opacity;
                if (sa <= 0.0) continue;
                std::uint8_t* p =
                    &it.tex[(static_cast<std::size_t>(y) * kTex + x) * 4];
                const double da = p[3] / 255.0;
                const double oa = sa + da * (1.0 - sa);
                for (int c = 0; c < 3; ++c)
                    p[c] = static_cast<std::uint8_t>(
                        d.color[c] * sa + p[c] * (1.0 - sa) + 0.5);
                p[3] = static_cast<std::uint8_t>(oa * 255.0 + 0.5);
            }
        }
    }
    return true;
}

void renderRefScene(RefScene& scene, const RefView& v, int w, int h,
                    std::vector<std::uint8_t>& rgba, int& drawnEdges) {
    drawnEdges = 0;
    if (w <= 0 || h <= 0 || rgba.size() != static_cast<std::size_t>(w) * h * 4)
        return;
    const Proj pr = makeProj(v, w, h);
    constexpr int kTex = 1024;
    for (auto& it : scene.items) {
        if (!it.visible || !it.loaded) continue;
        std::vector<ProjTri> tris;
        double minx, miny, maxx, maxy;
        projectItemTris(it, pr, tris, minx, miny, maxx, maxy);
        // Texture fill UNDER the wireframe (aid only). flat:1 =
        // opaque commit-preview, else 50% tint.
        if (!it.tex.empty()) {
            // Far-to-near painter order (mean depth, descending).
            std::vector<std::size_t> order(tris.size());
            for (std::size_t i = 0; i < order.size(); ++i) order[i] = i;
            std::sort(order.begin(), order.end(), [&](std::size_t a,
                                                      std::size_t b) {
                return (tris[a].d[0] + tris[a].d[1] + tris[a].d[2]) >
                       (tris[b].d[0] + tris[b].d[1] + tris[b].d[2]);
            });
            const double alpha = it.flat ? 1.0 : 0.5;
            for (std::size_t oi = 0; oi < order.size(); ++oi) {
                const ProjTri& t = tris[order[oi]];
                int x0 = static_cast<int>(std::floor(
                    std::min(t.x[0], std::min(t.x[1], t.x[2]))));
                int x1 = static_cast<int>(std::ceil(
                    std::max(t.x[0], std::max(t.x[1], t.x[2]))));
                int y0 = static_cast<int>(std::floor(
                    std::min(t.y[0], std::min(t.y[1], t.y[2]))));
                int y1 = static_cast<int>(std::ceil(
                    std::max(t.y[0], std::max(t.y[1], t.y[2]))));
                if (x0 < 0) x0 = 0;
                if (y0 < 0) y0 = 0;
                if (x1 >= w) x1 = w - 1;
                if (y1 >= h) y1 = h - 1;
                for (int y = y0; y <= y1; ++y)
                    for (int x = x0; x <= x1; ++x) {
                        double w0, w1, w2;
                        if (!pointInTri(x + 0.5, y + 0.5, t, w0, w1, w2))
                            continue;
                        // UV when the mesh carries it, else planar
                        // over the item's projected screen bbox.
                        double u = 0, vv = 0;
                        if (it.hasUV) {
                            const auto& f =
                                it.model.tris[order[oi]];
                            for (int k = 0; k < 3; ++k) {
                                const double wk =
                                    (k == 0) ? w0 : (k == 1 ? w1 : w2);
                                const std::size_t vi = f[k];
                                if (vi * 2 + 1 < it.model.uvs.size()) {
                                    u += wk * it.model.uvs[vi * 2];
                                    vv += wk * it.model.uvs[vi * 2 + 1];
                                }
                            }
                        } else {
                            const double bw = maxx - minx;
                            const double bh = maxy - miny;
                            u = (bw > 1e-9) ? (x + 0.5 - minx) / bw : 0;
                            vv = (bh > 1e-9) ? (y + 0.5 - miny) / bh : 0;
                        }
                        if (u < 0) u = 0;
                        if (u > 1) u = 1;
                        if (vv < 0) vv = 0;
                        if (vv > 1) vv = 1;
                        const int texx = static_cast<int>(u * (kTex - 1));
                        const int texy = static_cast<int>(vv * (kTex - 1));
                        const std::uint8_t* tp =
                            &it.tex[(static_cast<std::size_t>(texy) * kTex +
                                     texx) * 4];
                        if (tp[3] == 0) continue;
                        const double sa = (tp[3] / 255.0) * alpha;
                        std::uint8_t* d =
                            &rgba[static_cast<std::size_t>(y) * w * 4 +
                                  x * 4];
                        const double da = d[3] / 255.0;
                        const double oa = sa + da * (1.0 - sa);
                        if (oa <= 0) continue;
                        for (int c = 0; c < 3; ++c)
                            d[c] = static_cast<std::uint8_t>(
                                tp[c] * sa + d[c] * (1.0 - sa) + 0.5);
                        d[3] = static_cast<std::uint8_t>(oa * 255.0 + 0.5);
                    }
            }
        }
        // Wireframe edges on top (M9 style), unless wire:0.
        if (!it.wire) continue;
        for (const auto& e : it.model.edges) {
            double ax, ay, bx, by;
            // Reuse the projected cache when possible: project raw.
            std::vector<Mat4> worlds;
            computeWorlds(it.model, worlds);
            const Mat4 im = itemMatrix(it);
            double dep = 0;
            if (!projectVert(it.model, worlds, &im, e.first, pr, ax, ay,
                             &dep) ||
                !projectVert(it.model, worlds, &im, e.second, pr, bx, by,
                             &dep))
                continue;
            const double dx = bx - ax, dy = by - ay;
            const int steps =
                static_cast<int>(std::max(std::fabs(dx), std::fabs(dy))) + 1;
            bool any = false;
            for (int i = 0; i <= steps; ++i) {
                const double t = steps > 0 ? static_cast<double>(i) / steps
                                           : 0;
                const int px = static_cast<int>(ax + dx * t);
                const int py = static_cast<int>(ay + dy * t);
                if (px < 0 || px >= w || py < 0 || py >= h) continue;
                std::uint8_t* d = &rgba[static_cast<std::size_t>(py) * w * 4 +
                                        px * 4];
                d[0] = static_cast<std::uint8_t>(48 + d[0] * 127 / 255);
                d[1] = static_cast<std::uint8_t>(48 + d[1] * 127 / 255);
                d[2] = static_cast<std::uint8_t>(48 + d[2] * 127 / 255);
                d[3] = static_cast<std::uint8_t>(128 + d[3] * 127 / 255);
                any = true;
            }
            if (any) ++drawnEdges;
        }
    }
}

}  // namespace openclip
