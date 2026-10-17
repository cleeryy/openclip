// SPDX-License-Identifier: GPL-3.0-or-later
#include "openclip/ref.h"

#include <cmath>
#include <cstring>
#include <set>

#define CGLTF_IMPLEMENTATION
#include "cgltf.h"

namespace openclip {

namespace {

constexpr double kPi = 3.14159265358979323846;

// Node world transform baking: world = parent * local; local from
// matrix or TRS. 4x4 row-major-ish helper (mat = 16 doubles, row-major
// convention like glTF column-major arrays transposed on load).
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

void nodeWorld(const cgltf_node* n, Mat4& out) {
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
    if (n->parent) {
        Mat4 p;
        nodeWorld(n->parent, p);
        Mat4 c;
        mulMat(p, out, c);
        out = c;
    }
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
        for (std::size_t ni = 0; ni < scene->nodes_count; ++ni) {
            const cgltf_node* node = scene->nodes[ni];
            if (!node || !node->mesh) continue;
            Mat4 world;
            nodeWorld(node, world);
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
                    double wx, wy, wz;
                    applyMat(world, lx, ly, lz, wx, wy, wz);
                    verts.push_back(static_cast<float>(wx));
                    verts.push_back(static_cast<float>(wy));
                    verts.push_back(static_cast<float>(wz));
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
        ok = !out.verts.empty();
    }
    cgltf_free(data);
    if (!ok) err = "no mesh geometry";
    return ok;
}

void parseRefViewJson(const std::string& json, RefView& v) {
    // Same flat-JSON conventions as the M3 shim: jsonNum(json, key, def).
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
    const double f = (h / 2.0) / std::tan(v.fov * kPi / 360.0);
    const double k = v.fisheye * 0.001;
    const double cx = w / 2.0 + v.offX;
    const double cy = h / 2.0 + v.offY;
    auto project = [&](std::size_t vi, double& px, double& py) {
        double rx, ry, rz;
        rotatePoint(v, m.verts[vi * 3], m.verts[vi * 3 + 1],
                    m.verts[vi * 3 + 2], rx, ry, rz);
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

}  // namespace openclip
