// SPDX-License-Identifier: GPL-3.0-or-later
#include "openclip/ref.h"

#include <cmath>
#include <cstdlib>
#include <cstring>
#include <set>
#include <string>

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
                for (std::size_t ai = 0; ai < prim.attributes_count; ++ai)
                    if (prim.attributes[ai].type == cgltf_attribute_type_position)
                        pos = prim.attributes[ai].data;
                if (!pos || pos->component_type != cgltf_component_type_r_32f ||
                    pos->type != cgltf_type_vec3 || pos->is_sparse)
                    continue;
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
                }
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
    std::vector<char> done(nn, 0);
    for (std::size_t ni = 0; ni < nn; ++ni) {
        // Iterative: parents always have smaller... no — walk order is
        // DFS, parents come FIRST (parent pushed before children pop?
        // children are pushed after, so parents have smaller ids). The
        // walk above assigns parent ids before children (preorder), so
        // a single forward pass resolves every parent.
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
    const double f = (h / 2.0) / std::tan(v.fov * kPi / 360.0);
    const double k = v.fisheye * 0.001;
    const double cx = w / 2.0 + v.offX;
    const double cy = h / 2.0 + v.offY;
    auto project = [&](std::size_t vi, double& px, double& py) {
        std::uint32_t nid = (vi < m.vertNode.size()) ? m.vertNode[vi]
                                                     : 0xFFFFFFFFu;
        double wx = m.verts[vi * 3], wy = m.verts[vi * 3 + 1],
               wz = m.verts[vi * 3 + 2];
        if (nid < nn && done[nid]) {
            double ox, oy, oz;
            applyMat(worlds[nid], wx, wy, wz, ox, oy, oz);
            wx = ox;
            wy = oy;
            wz = oz;
        }
        double rx, ry, rz;
        rotatePoint(v, wx, wy, wz, rx, ry, rz);
        const double z = rz - v.dist;
        if (z > -1e-6) return false;
        double x = rx * f / -z;
        double y = -ry * f / -z;
        const double r = std::sqrt(x * x + y * y);
        if (k > 0 && r > 1e-6) {
            const double rp = r * (1.0 + k * r * r);
            x *= rp / r;
            y *= rp / r;
        }
        px = x + cx;
        py = y + cy;
        return true;
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

}  // namespace openclip
