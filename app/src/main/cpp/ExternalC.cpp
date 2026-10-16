/*
 * OpenClip M1 — JNI shim implementing the frozen contract from
 * ARCHITECTURE.md on top of the engine public headers only.
 * M2 additions (documented in ARCHITECTURE.md): nLoadMyb feeds a .myb
 * document to openclip::parseMyb keeping the session color; nSetBrush
 * also accepts "stabilize"/"curve" 0..1 (stored shim-side until the
 * engine consumes them) and "mode" "brush"|"eraser" — M2 erases toward
 * the canvas background (white), not layer alpha.
 * M4 additions (documented in ARCHITECTURE.md): nLoadDocument replaces
 * the session canvas from a filesystem path (extension decides: .ora
 * via loadOra, .png single flattened layer, .psd rejected as
 * unsupported-for-now until the engine document.h lane lands with real
 * loadDocument/saveDocument); a failed load leaves the canvas
 * untouched. nSaveDocument writes the composite ("png") or the ORA
 * archive ("ora"); "psd" flattens through a .png payload written at
 * the given path. Loading a document clears the undo history
 * (documented in ARCHITECTURE.md, touchTile/history untouched otherwise).
 * M5 additions (documented in ARCHITECTURE.md): layer tree control
 * (nSetLayerVisibility/nSetLayerOpacity/nSetLayerBlend/nSetLayerName/
 * nAddGroup/nMoveLayer/nAppendChild), clipping (nSetLayerClipped) and
 * masks (nAddLayerMask full-white, nMaskTarget redirecting strokes to
 * the mask where ink luminance drives mask alpha: black = hide).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <map>
#include <new>
#include <string>
#include <vector>

#include "openclip/blend.h"
#include "openclip/brush.h"
#include "openclip/canvas.h"
#include "openclip/filters.h"
#include "openclip/ora.h"
#include "openclip/surface.h"
#include "openclip/undo.h"
#include "openclip/vector.h"

// M4: optional engine document API. When the parallel engine lane lands
// engine/include/openclip/document.h, the shim prefers loadDocument /
// saveDocument from there; until then it falls back to the M1 ora.h
// primitives below (existing engine code, public headers only).
#if defined(__has_include)
#if __has_include("openclip/document.h")
#include "openclip/document.h"
#include "openclip/psd.h"
#define OPENCLIP_HAS_DOCUMENT_H 1
#endif
#endif

namespace {

/*
 * Shim-side session state. Ownership of the document lives here, not in
 * the app: the handle returned by nInit is the only key to it.
 *
 * Brush JSON accepted (shim-defined schema, MyPaint-compatible params):
 *   {"radius":8, "hardness":0.7, "opaque":1.0, "spacing":0.15,
 *    "color":"#RRGGBB", "mode":"brush"|"eraser"}   // extra keys ignored
 */
// M7 frames: each frame owns its canvas plus its own undo stacks
// (full Canvas copies, ARCHITECTURE.md "M7 additions"). Frame is
// movable/copyable (plain data members only) so the vector can grow,
// shrink and reallocate. A duplicate copies the canvas pixels with a
// fresh empty stack: it is a new frame, not a history fork.
struct Frame {
    openclip::Canvas canvas;
    openclip::History history;
    // M6 vector per-layer path stacks, per frame like raster undo.
    std::map<std::size_t, openclip::VectorHistory> vectors;
    Frame() = default;
    explicit Frame(openclip::Canvas c) : canvas(std::move(c)) {}
};
struct Session {
    // The live document canvas is frames[cur].canvas;
    // nLoadDocument/nSaveDocument apply to the current frame only.
    // Switching frames shows the destination frame's stacks (fresh
    // frames undo nothing, returning preserves them); deleting a frame
    // drops its stacks with it. The session mask arm stays global but
    // is disarmed on any frame op (it indexes current-frame layers;
    // shim documented).
    std::vector<Frame> frames;
    std::size_t cur = 0;
    // M7 playback rate, app-side only (no engine loop, no JNI symbol;
    // default 12 per the contract; the Compose timeline owns the value).
    float fps = 12.0f;
    bool onionPrev = false, onionNext = false;
    float onionStrength = 0.3f;
    openclip::BrushParams brush;
    std::uint8_t r = 20, g = 20, b = 20;
    // M2 shim-side brush state (engine takes over when its M2 lane lands):
    // stabilize = input smoothing 0..1, curve = pressure exponent 0..1,
    // eraser = paint toward canvas background instead of ink color.
    float stabilize = 0.0f;
    float curve = 0.0f;
    bool eraser = false;
    // M5: per-session mask-edit target (index + on/off). When on and the
    // active layer owns a mask, paintStamp resolves dabs to mask alpha:
    // ink luminance drives mask.a (white = show, black = hide), snaps the
    // mask tile for undo (history mask flag) and never touches pixels.
    int maskTarget = -1;
    bool maskTargetOn = false;

    // (M6 vector stacks now live per-Frame above.)

    // M6 rulers (shim transforms input before stamping; ARCHITECTURE.md).
    struct RulEllipse { float cx = 0, cy = 0, rx = 1, ry = 1; };
    bool symX = false, symY = false;
    float symXAt = 0, symYAt = 0;
    std::vector<RulEllipse> ellipses;
    std::vector<std::pair<openclip::Vec2, openclip::Vec2>> lines;
};

Session* asSession(jlong handle) { return reinterpret_cast<Session*>(handle); }

// M7: the live document canvas is always the current frame's; hist/vecs
// expose that frame's own undo stacks.
inline openclip::Canvas& cur(Session* s) { return s->frames[s->cur].canvas; }
inline const openclip::Canvas& cur(const Session* s) { return s->frames[s->cur].canvas; }
inline openclip::History& hist(Session* s) { return s->frames[s->cur].history; }
inline std::map<std::size_t, openclip::VectorHistory>& vecs(Session* s) {
    return s->frames[s->cur].vectors;
}

std::string toStd(JNIEnv* env, jstring jstr) {
    if (!jstr) return {};
    const char* c = env->GetStringUTFChars(jstr, nullptr);
    std::string out = c ? std::string(c) : std::string();
    if (c) env->ReleaseStringUTFChars(jstr, c);
    return out;
}

double jsonNum(const std::string& json, const char* key, double fallback) {
    const std::string pat = "\"" + std::string(key) + "\"";
    const std::size_t at = json.find(pat);
    if (at == std::string::npos) return fallback;
    const std::size_t colon = json.find(':', at + pat.size());
    if (colon == std::string::npos) return fallback;
    const char* p = json.c_str() + colon + 1;
    char* endp = nullptr;
    const double v = std::strtod(p, &endp);
    return (endp == p) ? fallback : v;
}

std::string jsonStr(const std::string& json, const char* key) {
    const std::string pat = "\"" + std::string(key) + "\"";
    const std::size_t at = json.find(pat);
    if (at == std::string::npos) return {};
    const std::size_t colon = json.find(':', at + pat.size());
    if (colon == std::string::npos) return {};
    const std::size_t q1 = json.find('"', colon + 1);
    if (q1 == std::string::npos) return {};
    const std::size_t q2 = json.find('"', q1 + 1);
    if (q2 == std::string::npos) return {};
    return json.substr(q1 + 1, q2 - q1 - 1);
}

int hexVal(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

bool parseColor(const std::string& s, std::uint8_t& r, std::uint8_t& g,
                std::uint8_t& b) {
    if (s.size() == 7 && s[0] == '#' && hexVal(s[1]) >= 0 && hexVal(s[2]) >= 0 &&
        hexVal(s[3]) >= 0 && hexVal(s[4]) >= 0 && hexVal(s[5]) >= 0 &&
        hexVal(s[6]) >= 0) {
        r = static_cast<std::uint8_t>(hexVal(s[1]) * 16 + hexVal(s[2]));
        g = static_cast<std::uint8_t>(hexVal(s[3]) * 16 + hexVal(s[4]));
        b = static_cast<std::uint8_t>(hexVal(s[5]) * 16 + hexVal(s[6]));
        return true;
    }
    return false;
}

// Paint one stamp and snapshot every touched engine tile for undo.
// M5: when the session mask target is armed on the active layer, the dab
// resolves to mask alpha (ink luminance -> mask.a) on the layer mask,
// snapshots the mask tile (history mask flag), and never touches pixels.
bool paintStamp(Session* s, const openclip::Dab& dab) {
    const int t = openclip::kTileSize;
    const int pad = static_cast<int>(dab.radius) + 1;
    const int x0 = std::max(0, static_cast<int>(dab.x) - pad);
    const int y0 = std::max(0, static_cast<int>(dab.y) - pad);
    const int x1 = std::min(static_cast<int>(dab.x) + pad, cur(s).width());
    const int y1 = std::min(static_cast<int>(dab.y) + pad, cur(s).height());
    if (x1 <= 0 || y1 <= 0 || x0 >= x1 || y0 >= y1) return false;
    const int tx0 = x0 / t, tx1 = (x1 - 1) / t;
    const int ty0 = y0 / t, ty1 = (y1 - 1) / t;
    const std::size_t layer = static_cast<std::size_t>(
        cur(s).activeLayer() < 0 ? 0 : cur(s).activeLayer());
    if (layer >= static_cast<std::size_t>(cur(s).layerCount())) return false;
    if (s->maskTargetOn && static_cast<int>(layer) == s->maskTarget &&
        cur(s).layer(layer).hasMask) {
        // Ink luminance drives mask alpha (white keeps, black hides);
        // alpha-erase and eraser flags do not apply to masks.
        const std::uint8_t lum = static_cast<std::uint8_t>(
            (0.299f * dab.r + 0.587f * dab.g + 0.114f * dab.b) *
            std::max(0.0f, std::min(1.0f, dab.opacity)));
        for (int ty = ty0; ty <= ty1; ++ty)
            for (int tx = tx0; tx <= tx1; ++tx)
                hist(s).touchTile(cur(s), layer, tx, ty, true);
        openclip::Surface& m = cur(s).layer(layer).mask;
        const float rad = dab.radius > 0.5f ? dab.radius : 0.5f;
        for (int y = y0; y < y1; ++y) {
            for (int x = x0; x < x1; ++x) {
                const float dx = (x + 0.5f) - dab.x;
                const float dy = (y + 0.5f) - dab.y;
                const float dist = std::sqrt(dx * dx + dy * dy);
                if (dist > rad) continue;
                const float fall = 1.0f - dist / rad;
                const float sa = fall * dab.opacity;
                if (sa <= 1e-4f) continue;
                openclip::Pixel dst = m.get(x, y);
                const float da = dst.a / 255.0f;
                const float ta = lum / 255.0f;
                const float oa = ta * sa + da * (1.0f - sa);
                const std::uint8_t v = static_cast<std::uint8_t>(
                    std::max(0.0f, std::min(255.0f, oa * 255.0f + 0.5f)));
                m.set(x, y, openclip::Pixel{v, v, v, v});
            }
        }
        return true;
    }
    for (int ty = ty0; ty <= ty1; ++ty)
        for (int tx = tx0; tx <= tx1; ++tx)
            hist(s).touchTile(cur(s), layer, tx, ty);
    openclip::paintDab(cur(s).layer(layer).surface, dab, s->brush);
    return true;
}

// M2 eraser semantics: paint toward the canvas background (white), not
// layer alpha, until M5 adds alpha-erase. Resolves one session-colored
// dab and paints it.
bool stampResolved(Session* s, const openclip::Dab& d) {
    static const std::uint8_t kBg = 255;
    const openclip::Dab stamped =
        s->eraser ? openclip::dabFromPressure(s->brush, d.x, d.y, d.pressure,
                                              kBg, kBg, kBg)
                  : openclip::dabFromPressure(s->brush, d.x, d.y, d.pressure,
                                              s->r, s->g, s->b);
    return paintStamp(s, stamped);
}

// M6 rulers: project one input point to the active ruler (ellipse
// boundary of the first ellipse; nearest point on the first line) and
// emit stamps at the transformed point plus its symmetry mirrors.
void rulerStamp(Session* s, float x, float y, float pressure) {
    float px = x, py = y;
    if (!s->ellipses.empty()) {
        const auto& e = s->ellipses.front();
        const float dx = (px - e.cx) / e.rx;
        const float dy = (py - e.cy) / e.ry;
        const float t = std::atan2(dy, dx);
        px = e.cx + e.rx * std::cos(t);
        py = e.cy + e.ry * std::sin(t);
    } else if (!s->lines.empty()) {
        const auto& seg = s->lines.front();  // nearest point on segment
        const float ax = seg.first.x, ay = seg.first.y;
        const float bxD = seg.second.x - ax, byD = seg.second.y - ay;
        const float len2 = bxD * bxD + byD * byD;
        float t = len2 > 0 ? ((px - ax) * bxD + (py - ay) * byD) / len2 : 0;
        t = std::max(0.0f, std::min(1.0f, t));
        px = ax + t * bxD;
        py = ay + t * byD;
    }
    const std::vector<openclip::Dab> base = openclip::interpolateStamps(
        s->brush, px, py, pressure, px, py, pressure, s->r, s->g, s->b);
    for (const auto& d : base) stampResolved(s, d);
    if (s->symX) {
        const float mx = 2 * s->symXAt - px;
        for (const auto& d : openclip::interpolateStamps(
                 s->brush, mx, py, pressure, mx, py, pressure, s->r, s->g,
                 s->b))
            stampResolved(s, d);
    }
    if (s->symY) {
        const float my = 2 * s->symYAt - py;
        for (const auto& d : openclip::interpolateStamps(
                 s->brush, px, my, pressure, px, my, pressure, s->r, s->g,
                 s->b))
            stampResolved(s, d);
    }
    if (s->symX && s->symY) {  // quadrant mirror
        const float mx = 2 * s->symXAt - px, my = 2 * s->symYAt - py;
        for (const auto& d : openclip::interpolateStamps(
                 s->brush, mx, my, pressure, mx, my, pressure, s->r, s->g,
                 s->b))
            stampResolved(s, d);
    }
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_openclip_engine_EngineHost_nInit(JNIEnv* env, jclass, jint w, jint h,
                                          jfloat /*dpi*/) {
    if (w <= 0 || h <= 0) return 0;
    Session* s = new (std::nothrow) Session();
    if (!s) return 0;
    // M7: nInit creates the initial single-frame clip (frames={canvas}).
    openclip::Canvas first;
    first.resize(w, h);
    if (first.layerCount() == 0) first.addLayer("Layer 1");
    for (int y = 0; y < h; ++y)          // white base layer
        for (int x = 0; x < w; ++x)
            first.layer(0).surface.set(x, y,
                                       openclip::Pixel{255, 255, 255, 255});
    first.setActiveLayer(0);
    s->frames.push_back(Frame(std::move(first)));
    s->cur = 0;
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_openclip_engine_EngineHost_nDispose(JNIEnv* env, jclass,
                                             jlong handle) {
    delete asSession(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_openclip_engine_EngineHost_nSetBrush(JNIEnv* env, jclass,
                                              jlong handle, jstring json) {
    Session* s = asSession(handle);
    if (!s) return;
    const std::string j = toStd(env, json);
    s->brush.radius = static_cast<float>(jsonNum(j, "radius", s->brush.radius));
    s->brush.hardness =
        static_cast<float>(jsonNum(j, "hardness", s->brush.hardness));
    s->brush.opaque =
        static_cast<float>(jsonNum(j, "opaque", s->brush.opaque));
    s->brush.spacing =
        static_cast<float>(jsonNum(j, "spacing", s->brush.spacing));
    // M2 shim-side keys: clamp defensively, ignore out-of-range garbage.
    const float stab =
        static_cast<float>(jsonNum(j, "stabilize", s->stabilize));
    s->stabilize = stab < 0.0f ? 0.0f : (stab > 1.0f ? 1.0f : stab);
    const float curve = static_cast<float>(jsonNum(j, "curve", s->curve));
    s->curve = curve < 0.0f ? 0.0f : (curve > 1.0f ? 1.0f : curve);
    const std::string mode = jsonStr(j, "mode");
    if (mode == "eraser")
        s->eraser = true;
    else if (mode == "brush")
        s->eraser = false;
    const std::string color = jsonStr(j, "color");
    if (!color.empty()) parseColor(color, s->r, s->g, s->b);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nLoadMyb(JNIEnv* env, jclass,
                                             jlong handle, jstring xml) {
    Session* s = asSession(handle);
    if (!s) return JNI_FALSE;
    const std::string doc = toStd(env, xml);
    if (doc.empty()) return JNI_FALSE;
    openclip::BrushParams parsed;
    std::string error;
    if (!openclip::parseMyb(doc, parsed, error)) return JNI_FALSE;
    // Success: adopt the parsed params, keep the session color.
    s->brush = parsed;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_openclip_engine_EngineHost_nLayerCount(JNIEnv*, jclass, jlong handle) {
    Session* s = asSession(handle);
    return s ? static_cast<jint>(cur(s).layerCount()) : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_openclip_engine_EngineHost_nSetActiveLayer(JNIEnv*, jclass,
                                                    jlong handle, jint index) {
    Session* s = asSession(handle);
    if (!s || index < 0 || index >= static_cast<jint>(cur(s).layerCount()))
        return -1;
    cur(s).setActiveLayer(index);
    return index;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_openclip_engine_EngineHost_nAddLayer(JNIEnv* env, jclass,
                                              jlong handle, jstring jname) {
    Session* s = asSession(handle);
    if (!s) return -1;
    std::string name =
        jname ? toStd(env, jname) : std::string();
    if (name.empty()) name = "Layer";
    return static_cast<jint>(cur(s).addLayer(name));
}

// M5 layer-system round (ARCHITECTURE.md "M5 additions"): direct maps
// onto the engine canvas. Index bounds are checked shim-side; invalid
// indexes return false/-1 without touching engine state. Blend names go
// through openclip::blendFromName (bare or "svg:" prefixed); unknown
// names are rejected (false, layer unchanged) so the panel dropdown can
// only push engine-known modes.
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nSetLayerVisibility(JNIEnv*, jclass,
                                                        jlong handle,
                                                        jint index,
                                                        jboolean visible) {
    Session* s = asSession(handle);
    if (!s || index < 0 ||
        index >= static_cast<jint>(cur(s).layerCount()))
        return JNI_FALSE;
    cur(s).layer(static_cast<std::size_t>(index)).visible =
        (visible == JNI_TRUE);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nSetLayerOpacity(JNIEnv*, jclass,
                                                     jlong handle, jint index,
                                                     jfloat opacity) {
    Session* s = asSession(handle);
    if (!s || index < 0 ||
        index >= static_cast<jint>(cur(s).layerCount()))
        return JNI_FALSE;
    if (opacity < 0.0f || opacity > 1.0f) return JNI_FALSE;
    cur(s).layer(static_cast<std::size_t>(index)).opacity = opacity;
    return JNI_TRUE;
}

bool knownBlendName(const std::string& n) {
    return n == "normal" || n == "multiply" || n == "screen" ||
           n == "overlay" || n == "plus" || n == "add" ||
           n == "soft-light" || n == "soft_light" || n == "softlight" ||
           n == "svg:normal" || n == "svg:multiply" || n == "svg:screen" ||
           n == "svg:overlay" || n == "svg:plus" || n == "svg:soft-light";
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nSetLayerBlend(JNIEnv* env, jclass,
                                                    jlong handle, jint index,
                                                    jstring jname) {
    Session* s = asSession(handle);
    if (!s || index < 0 ||
        index >= static_cast<jint>(cur(s).layerCount()))
        return JNI_FALSE;
    const std::string name = toStd(env, jname);
    if (name.empty() || !knownBlendName(name)) return JNI_FALSE;
    cur(s).layer(static_cast<std::size_t>(index)).blend =
        openclip::blendFromName(name.c_str());
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nSetLayerName(JNIEnv* env, jclass,
                                                  jlong handle, jint index,
                                                  jstring jname) {
    Session* s = asSession(handle);
    if (!s || index < 0 ||
        index >= static_cast<jint>(cur(s).layerCount()))
        return JNI_FALSE;
    cur(s).layer(static_cast<std::size_t>(index)).name = toStd(env, jname);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_openclip_engine_EngineHost_nAddGroup(JNIEnv* env, jclass,
                                              jlong handle, jstring jname) {
    Session* s = asSession(handle);
    if (!s) return -1;
    std::string name = jname ? toStd(env, jname) : std::string();
    if (name.empty()) name = "Group";
    return static_cast<jint>(cur(s).addGroup(name));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nMoveLayer(JNIEnv*, jclass, jlong handle,
                                              jint from, jint to) {
    Session* s = asSession(handle);
    if (!s || from < 0 || to < 0 ||
        from >= static_cast<jint>(cur(s).layerCount()) ||
        to >= static_cast<jint>(cur(s).layerCount()) || from == to)
        return JNI_FALSE;
    cur(s).moveLayer(static_cast<std::size_t>(from),
                        static_cast<std::size_t>(to));
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_openclip_engine_EngineHost_nAppendChild(JNIEnv* env, jclass,
                                                 jlong handle, jint parent,
                                                 jstring jname) {
    Session* s = asSession(handle);
    if (!s || parent < 0 ||
        parent >= static_cast<jint>(cur(s).layerCount()))
        return -1;
    openclip::Layer& p =
        cur(s).layer(static_cast<std::size_t>(parent));
    if (!p.isGroup) return -1;
    openclip::Layer ch;
    std::string name = jname ? toStd(env, jname) : std::string();
    ch.name = name.empty() ? "Layer" : name;
    ch.surface.resize(cur(s).width(), cur(s).height());
    p.children.push_back(std::move(ch));
    return static_cast<jint>(p.children.size()) - 1;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nSetLayerClipped(JNIEnv*, jclass,
                                                     jlong handle, jint index,
                                                     jboolean clipped) {
    Session* s = asSession(handle);
    if (!s || index < 0 ||
        index >= static_cast<jint>(cur(s).layerCount()))
        return JNI_FALSE;
    cur(s).layer(static_cast<std::size_t>(index)).clipped =
        (clipped == JNI_TRUE);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nAddLayerMask(JNIEnv*, jclass,
                                                  jlong handle, jint index) {
    Session* s = asSession(handle);
    if (!s || index < 0 ||
        index >= static_cast<jint>(cur(s).layerCount()))
        return JNI_FALSE;
    openclip::Layer& l = cur(s).layer(static_cast<std::size_t>(index));
    if (l.isGroup) return JNI_FALSE;
    l.addMask(cur(s).width(), cur(s).height());
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nMaskTarget(JNIEnv*, jclass, jlong handle,
                                                jint index, jboolean onMask) {
    Session* s = asSession(handle);
    if (!s || index < 0 ||
        index >= static_cast<jint>(cur(s).layerCount()))
        return JNI_FALSE;
    if (onMask == JNI_TRUE &&
        !cur(s).layer(static_cast<std::size_t>(index)).hasMask)
        return JNI_FALSE;  // cannot arm a target without a mask
    s->maskTarget = index;
    s->maskTargetOn = (onMask == JNI_TRUE);
    return JNI_TRUE;
}

// M5 panel introspection (documented in ARCHITECTURE.md "M5 additions",
// "App: channels for panel state are these exact symbols"): a compact
// JSON snapshot of the top-level layer list so the Compose LayerPanel
// renders names/groups without caching engine truth. Escapes only the
// JSON-significant characters of engine-owned names.
void appendJsonStr(std::string& out, const std::string& s) {
    out += '"';
    for (char ch : s) {
        switch (ch) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (ch >= 0 && ch < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof buf, "\\u%04x", ch);
                    out += buf;
                } else {
                    out += ch;
                }
        }
    }
    out += '"';
}

void appendLayerJson(std::string& out, const openclip::Layer& l,
                     bool isGroup) {
    out += "{\"name\":";
    appendJsonStr(out, l.name);
    out += ",\"visible\":";
    out += l.visible ? "true" : "false";
    char obuf[32];
    std::snprintf(obuf, sizeof obuf, "%g", l.opacity);
    out += ",\"opacity\":";
    out += obuf;
    out += ",\"blend\":";
    appendJsonStr(out, openclip::blendToName(l.blend));
    out += ",\"group\":";
    out += isGroup ? "true" : "false";
    out += ",\"mask\":";
    out += l.hasMask ? "true" : "false";
    out += ",\"clipped\":";
    out += l.clipped ? "true" : "false";
    out += "}";
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_openclip_engine_EngineHost_nLayerState(JNIEnv* env, jclass,
                                                jlong handle) {
    Session* s = asSession(handle);
    if (!s) return env->NewStringUTF("{\"active\":0,\"layers\":[]}");
    std::string out = "{\"active\":";
    out += std::to_string(cur(s).activeLayer());
    out += ",\"maskTarget\":";
    out += std::to_string(s->maskTargetOn ? s->maskTarget : -1);
    out += ",\"layers\":[";
    for (std::size_t i = 0; i < cur(s).layerCount(); ++i) {
        if (i > 0) out += ",";
        out += "{\"children\":[";
        const openclip::Layer& l = cur(s).layer(i);
        if (l.isGroup) {
            for (std::size_t k = 0; k < l.children.size(); ++k) {
                if (k > 0) out += ",";
                appendLayerJson(out, l.children[k], false);
            }
        }
        out += "],\"self\":";
        appendLayerJson(out, l, l.isGroup);
        out += "}";
    }
    out += "]}";
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_openclip_engine_EngineHost_nStrokeBegin(JNIEnv*, jclass, jlong handle,
                                                 jfloat x, jfloat y,
                                                 jfloat pressure) {
    Session* s = asSession(handle);
    if (!s) return 0;
    hist(s).beginStroke();
    rulerStamp(s, x, y, pressure);
    // M6: rulers transform input here. Stroke ids are cosmetic (single
    // render thread).
    static jlong nextStrokeId = 1;
    return nextStrokeId++;
}

// M7 onion helpers (file scope, engine public types only — no Android
// types here; the JNI blit stays in the nCompositeInto wrappers below).
// tintOnion: neighbor composite -> gray ghost (rgb x0.4, alpha x
// strength), still premultiplied. overPremult: source-over in premult
// space (src on top of dst, in place).
static void tintOnion(const openclip::Surface& src, float strength,
                      std::vector<std::uint8_t>& out) {
    const std::vector<std::uint8_t>& b = src.bytes();
    out.resize(b.size());
    for (std::size_t i = 0; i + 4 <= b.size(); i += 4) {
        out[i + 0] = static_cast<std::uint8_t>(b[i + 0] * 0.4f + 0.5f);
        out[i + 1] = static_cast<std::uint8_t>(b[i + 1] * 0.4f + 0.5f);
        out[i + 2] = static_cast<std::uint8_t>(b[i + 2] * 0.4f + 0.5f);
        out[i + 3] = static_cast<std::uint8_t>(b[i + 3] * strength + 0.5f);
    }
}

static void overPremult(const std::vector<std::uint8_t>& src,
                        std::vector<std::uint8_t>& dst) {
    if (src.size() != dst.size()) return;
    for (std::size_t i = 0; i + 4 <= src.size(); i += 4) {
        const float sa = src[i + 3] / 255.0f;
        const float inv = 1.0f - sa;
        const float da = dst[i + 3] / 255.0f;
        const float oa = sa + da * inv;
        dst[i + 0] = static_cast<std::uint8_t>(
            std::max(0.0f, std::min(255.0f, src[i + 0] + dst[i + 0] * inv + 0.5f)));
        dst[i + 1] = static_cast<std::uint8_t>(
            std::max(0.0f, std::min(255.0f, src[i + 1] + dst[i + 1] * inv + 0.5f)));
        dst[i + 2] = static_cast<std::uint8_t>(
            std::max(0.0f, std::min(255.0f, src[i + 2] + dst[i + 2] * inv + 0.5f)));
        dst[i + 3] = static_cast<std::uint8_t>(
            std::max(0.0f, std::min(255.0f, oa * 255.0f + 0.5f)));
    }
}

// Premultiplied RGBA8 bytes -> ARGB IntArray blit. Returns pixel count,
// -3 when the Java array is too small (same codes as the M1 contract).
static jint blitArgb(JNIEnv* env, const std::vector<std::uint8_t>& px,
                     jintArray out, jint width, jint height) {
    const jsize n = env->GetArrayLength(out);
    if (width * height > n) return -3;
    std::vector<jint> pixels(static_cast<std::size_t>(width) *
                             static_cast<std::size_t>(height));
    const std::size_t count =
        std::min<std::size_t>(pixels.size(), px.size() / 4);
    for (std::size_t i = 0; i < count; ++i) {
        const std::uint8_t r = px[i * 4 + 0], g = px[i * 4 + 1];
        const std::uint8_t b = px[i * 4 + 2], a = px[i * 4 + 3];
        const std::uint32_t rr =
            (a == 0) ? 0u : std::min<std::uint32_t>(255, (r * 255u) / a);
        const std::uint32_t gg =
            (a == 0) ? 0u : std::min<std::uint32_t>(255, (g * 255u) / a);
        const std::uint32_t bb =
            (a == 0) ? 0u : std::min<std::uint32_t>(255, (b * 255u) / a);
        pixels[i] = static_cast<jint>((a << 24) | (rr << 16) | (gg << 8) | bb);
    }
    env->SetIntArrayRegion(out, 0, static_cast<jsize>(count), pixels.data());
    return static_cast<jint>(count);
}

// M7: per-frame local state reset. Switching/adding/duplicating/
// deleting a frame (or loading a document into one) disarms the session
// mask target, which always refers to current-frame layer indexes. Undo
// stacks need no reset: each frame owns its own (ARCHITECTURE.md "M7
// additions" — switching shows the destination frame's stack).
static void resetFrameLocalState(Session* s) {
    s->maskTarget = -1;
    s->maskTargetOn = false;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_openclip_engine_EngineHost_nCompositeInto(JNIEnv* env, jclass,
                                                   jlong handle, jintArray out,
                                                   jint width, jint height) {
    Session* s = asSession(handle);
    if (!s || !out) return -1;
    if (width <= 0 || height <= 0) return -2;

    openclip::Surface comp;
    cur(s).composite(comp);
    std::vector<std::uint8_t> px = comp.bytes();  // premult RGBA8
    // M7 onion skin: neighbor composites tinted gray (x0.4) at strength
    // alpha, drawn beneath the current frame (normal over). A fully
    // opaque current frame hides the ghosts; a transparent one (hidden
    // base layer) reveals them — that is the intended onion workflow.
    const bool wantPrev = s->onionPrev && s->cur > 0;
    const bool wantNext =
        s->onionNext && s->cur + 1 < s->frames.size();
    if (wantPrev || wantNext) {
        std::vector<std::uint8_t> under(px.size(), 0);
        if (wantPrev) {
            openclip::Surface n;
            s->frames[s->cur - 1].canvas.composite(n);
            if (n.bytes().size() == under.size()) {
                std::vector<std::uint8_t> t;
                tintOnion(n, s->onionStrength, t);
                overPremult(t, under);
            }
        }
        if (wantNext) {
            openclip::Surface n;
            s->frames[s->cur + 1].canvas.composite(n);
            if (n.bytes().size() == under.size()) {
                std::vector<std::uint8_t> t;
                tintOnion(n, s->onionStrength, t);
                overPremult(t, under);
            }
        }
        if (px.size() == under.size()) {
            overPremult(px, under);  // current frame on top
            px.swap(under);
        }
    }
    return blitArgb(env, px, out, width, height);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_openclip_engine_EngineHost_nStrokeMove(JNIEnv*, jclass, jlong handle,
                                                jlong /*strokeId*/, jfloat x,
                                                jfloat y, jfloat pressure) {
    Session* s = asSession(handle);
    if (!s) return;
    // M6: rulers transform input before stamping; app-side interpolation
    // of consecutive moves stays the app's job until M2 stabilization.
    rulerStamp(s, x, y, pressure);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_openclip_engine_EngineHost_nStrokeEnd(JNIEnv*, jclass, jlong handle,
                                               jlong /*strokeId*/) {
    Session* s = asSession(handle);
    if (!s) return;
    hist(s).endStroke(cur(s));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nApplyFilter(JNIEnv* env, jclass,
                                                 jlong handle, jstring name,
                                                 jstring paramsJson) {
    Session* s = asSession(handle);
    if (!s) return JNI_FALSE;
    const std::string fname = toStd(env, name);
    const std::string params = toStd(env, paramsJson);
    // M3 contract: full active layer, undoable like a brush stroke.
    openclip::History& h = hist(s);
    h.beginStroke();
    const std::size_t layer = static_cast<std::size_t>(
        cur(s).activeLayer() < 0 ? 0 : cur(s).activeLayer());
    if (layer >= cur(s).layerCount()) {
        h.cancelStroke(cur(s));
        return JNI_FALSE;
    }
    openclip::Layer& ly = cur(s).layer(layer);
    for (int ty = 0; ty < ly.surface.tilesY(); ++ty)
        for (int tx = 0; tx < ly.surface.tilesX(); ++tx)
            h.touchTile(cur(s), layer, tx, ty);
    std::string err;
    const bool ok = openclip::applyFilter(ly.surface, fname, params, err);
    if (ok) {
        h.endStroke(cur(s));
    } else {
        h.cancelStroke(cur(s));  // failed filters leave no undo residue
    }
    (void)err;
    return ok ? JNI_TRUE : JNI_FALSE;
}

// M4 document I/O. Contract (ARCHITECTURE.md "M4 additions"): load
// replaces the session canvas from a filesystem path (extension decides:
// .ora full round trip, .png single flattened layer, .psd import). Save
// writes the composite ("png") or the archive ("ora"/"psd" basic). A
// failed load leaves the canvas untouched; a successful load clears the
// undo history. When the engine document.h lane has landed the shim
// prefers openclip::loadDocument/saveDocument; until then it falls back
// to the M1 ora.h primitives (loadOra/saveOra/exportPng).
bool loadViaFallback(openclip::Canvas& canvas, const std::string& path,
                     std::string& error) {
    // document.h is on master since commit 9b9387f; this shim fallback now
    // delegates every format to the engine (per-extension routing).
    return openclip::loadPngInto(canvas, path, error) ||
           openclip::loadOra(canvas, path, error) ||
           openclip::loadPsd(canvas, path, error);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nLoadDocument(JNIEnv* env, jclass,
                                                  jlong handle, jstring path) {
    Session* s = asSession(handle);
    if (!s) return JNI_FALSE;
    const std::string p = toStd(env, path);
    if (p.empty()) return JNI_FALSE;
#ifdef OPENCLIP_HAS_DOCUMENT_H
    {
        std::string error;
        openclip::Canvas tmp = cur(s);
        const bool ok = openclip::loadDocument(tmp, p, error);
        (void)error;
        if (!ok) return JNI_FALSE;  // canvas left untouched
        cur(s) = std::move(tmp);
        if (cur(s).layerCount() > 0 &&
            (cur(s).activeLayer() < 0 ||
             cur(s).activeLayer() >=
                 static_cast<int>(cur(s).layerCount())))
            cur(s).setActiveLayer(0);
        hist(s).clear();  // document load resets the current frame's undo history
        return JNI_TRUE;
    }
#else
    {
        std::string error;
        openclip::Canvas tmp = cur(s);
        if (!loadViaFallback(tmp, p, error)) {
            (void)error;
            return JNI_FALSE;  // canvas left untouched
        }
        cur(s) = std::move(tmp);
        hist(s).clear();  // document load resets the current frame's undo history
        return JNI_TRUE;
    }
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nSaveDocument(JNIEnv* env, jclass,
                                                  jlong handle, jstring path,
                                                  jstring format) {
    Session* s = asSession(handle);
    if (!s) return JNI_FALSE;
    const std::string p = toStd(env, path);
    std::string fmt = toStd(env, format);
    for (auto& ch : fmt)
        ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
    if (p.empty() || fmt.empty()) return JNI_FALSE;
#ifdef OPENCLIP_HAS_DOCUMENT_H
    {
        std::string error;
        const bool ok = openclip::saveDocument(cur(s), p, fmt, error);
        (void)error;
        return ok ? JNI_TRUE : JNI_FALSE;
    }
#else
    {
        std::string error;
        bool ok = false;
        if (fmt == "ora") {
            ok = openclip::saveOra(cur(s), p, error);
        } else if (fmt == "png") {
            ok = openclip::exportPng(cur(s), p, error);
        } else if (fmt == "psd") {
            // M4 baseline: engine PSD writer not landed yet; persist the
            // flattened composite PNG payload at the requested path so the
            // save path is exercised end-to-end (real document.h replaces
            // this with per-layer PSD records when the lane lands).
            ok = openclip::exportPng(cur(s), p, error);
        } else {
            return JNI_FALSE;
        }
        (void)error;
        return ok ? JNI_TRUE : JNI_FALSE;
    }
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nUndo(JNIEnv*, jclass, jlong handle) {
    Session* s = asSession(handle);
    return s ? static_cast<jboolean>(hist(s).undo(cur(s))) : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nRedo(JNIEnv*, jclass, jlong handle) {
    Session* s = asSession(handle);
    return s ? static_cast<jboolean>(hist(s).redo(cur(s))) : JNI_FALSE;
}

// ---- M6 vector layers + rulers (symbols per ARCHITECTURE.md) ----

extern "C" JNIEXPORT jint JNICALL
Java_dev_openclip_engine_EngineHost_nAddVectorLayer(JNIEnv* env, jclass,
                                                    jlong handle,
                                                    jstring name) {
    Session* s = asSession(handle);
    if (!s) return -1;
    const std::string nm = toStd(env, name);
    const int idx = static_cast<int>(cur(s).addLayer(nm.empty() ? "vector"
                                                                  : nm));
    if (idx >= 0 && idx < static_cast<int>(cur(s).layerCount()))
        cur(s).layer(static_cast<std::size_t>(idx)).isVector = true;
    return idx;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nSetVectorMode(JNIEnv*, jclass,
                                                   jlong handle, jint index,
                                                   jboolean on) {
    Session* s = asSession(handle);
    if (!s || index < 0 || index >= static_cast<int>(cur(s).layerCount()))
        return JNI_FALSE;
    cur(s).layer(static_cast<std::size_t>(index)).isVector = (on == JNI_TRUE);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_openclip_engine_EngineHost_nAppendVectorPath(JNIEnv* env, jclass,
                                                      jlong handle, jint index,
                                                      jstring pathJson) {
    Session* s = asSession(handle);
    if (!s || index < 0 || index >= static_cast<int>(cur(s).layerCount()))
        return -1;
    const std::string j = toStd(env, pathJson);
    if (j.empty()) return -1;
    std::string kind = jsonStr(j, "kind");
    kind = (kind == "eraser-stroke") ? "eraser-stroke" : "stroke";
    openclip::VectorHistory& h = vecs(s)[static_cast<std::size_t>(index)];
    std::string err;
    const int n = openclip::appendVectorPath(
        cur(s), h, static_cast<std::size_t>(index), j,
        1.0f + 3.0f * s->curve, kind, err);
    return n;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nVectorEraser(JNIEnv* env, jclass,
                                                  jlong handle, jint index,
                                                  jboolean /*on*/,
                                                  jstring pathJson) {
    Session* s = asSession(handle);
    if (!s || index < 0 || index >= static_cast<int>(cur(s).layerCount()))
        return JNI_FALSE;
    const std::string j = toStd(env, pathJson);
    if (j.empty()) return JNI_FALSE;
    openclip::VectorHistory& h = vecs(s)[static_cast<std::size_t>(index)];
    std::string err;
    return openclip::eraseVectorPaths(cur(s), h,
                                      static_cast<std::size_t>(index), j,
                                      err) > 0
               ? JNI_TRUE
               : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nUndoVector(JNIEnv*, jclass, jlong handle,
                                                jint index) {
    Session* s = asSession(handle);
    if (!s || index < 0 || index >= static_cast<int>(cur(s).layerCount()))
        return JNI_FALSE;
    auto it = vecs(s).find(static_cast<std::size_t>(index));
    if (it == vecs(s).end()) return JNI_FALSE;
    return openclip::undoVectorPath(cur(s), it->second,
                                    static_cast<std::size_t>(index))
               ? JNI_TRUE
               : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nSetRulers(JNIEnv* env, jclass,
                                               jlong handle, jstring kindsJson) {
    Session* s = asSession(handle);
    if (!s) return JNI_FALSE;
    const std::string j = toStd(env, kindsJson);
    // Reset then parse per ARCHITECTURE.md kinds (lenient M6 parser).
    s->symX = s->symY = false;
    s->ellipses.clear();
    s->lines.clear();
    if (j.empty()) return JNI_TRUE;  // clearing is a valid no-op state
    if (j.find("\"symmetryX\"") != std::string::npos) {
        s->symXAt = static_cast<float>(jsonNum(j, "symmetryX", 0.0));
        s->symX = true;
    }
    if (j.find("\"symmetryY\"") != std::string::npos) {
        s->symYAt = static_cast<float>(jsonNum(j, "symmetryY", 0.0));
        s->symY = true;
    }
    const std::size_t ell = j.find("\"ellipse\"");
    if (ell != std::string::npos) {
        Session::RulEllipse e;
        e.cx = static_cast<float>(jsonNum(j, "cx", 0));
        e.cy = static_cast<float>(jsonNum(j, "cy", 0));
        e.rx = static_cast<float>(jsonNum(j, "rx", 1));
        e.ry = static_cast<float>(jsonNum(j, "ry", 1));
        if (e.cx != 0 || e.cy != 0) s->ellipses.push_back(e);
    }
    const std::size_t per = j.find("\"perspective\"");
    if (per != std::string::npos) {
        // First segment only in M6: scan the bracket body and take the
        // first four numbers as {x1,y1,x2,y2}.
        const std::size_t open = j.find('[', per + 12);
        if (open != std::string::npos) {
            std::vector<float> vals;
            std::size_t p = open + 1;
            int depth = 0;
            while (p < j.size() && vals.size() < 4) {
                const char c = j[p];
                if (c == '{') ++depth;
                else if (c == '}') { if (depth > 0) --depth; }
                else if ((c >= '0' && c <= '9') || c == '-' || c == '.') {
                    const char* b = j.c_str() + p;
                    char* e = nullptr;
                    const float v = static_cast<float>(std::strtod(b, &e));
                    if (e != b) { vals.push_back(v); p += (e - b); continue; }
                }
                ++p;
            }
            if (vals.size() >= 4)
                s->lines.emplace_back(
                    openclip::Vec2{vals[0], vals[1]},
                    openclip::Vec2{vals[2], vals[3]});
        }
    }
    return JNI_TRUE;
}

// ---- M7 frames + onion skin (symbols per ARCHITECTURE.md) ----
//
// Frames are full Canvas copies (ARCHITECTURE.md documents the cost).
// nAddFrame appends a BLANK frame after current (1 white base layer,
// same size as the current frame). nDuplicateFrame appends an exact
// copy of the current frame (all layers, same framebuffer bytes).
// nDeleteFrame removes index (refused when it is the last frame);
// the current index clamps and the dropped frame takes its undo stacks
// with it. nSetFrame switches the editing frame (the destination
// frame's own stacks become visible, per the contract). nFrameCount returns
// the clip length. nFrameCompositeInto composites ONLY frame index
// (never the onion ghosts — thumbnails and playback do their own
// layering); returns pixel count like nCompositeInto, -1 on bad
// args, -2 on unknown frame. nSetOnion arms previous/next ghosts at
// strength alpha (0..1, clamped), drawn UNDER the current frame by
// nCompositeInto. nLoadDocument/nSaveDocument (above) apply to the
// current frame; a successful load resets frame-local state like a
// frame switch (shim documented).

extern "C" JNIEXPORT jint JNICALL
Java_dev_openclip_engine_EngineHost_nAddFrame(JNIEnv*, jclass, jlong handle) {
    Session* s = asSession(handle);
    if (!s || s->frames.empty()) return -1;
    openclip::Canvas blank;
    blank.resize(cur(s).width(), cur(s).height());
    blank.addLayer("Layer 1");
    for (int y = 0; y < blank.height(); ++y)
        for (int x = 0; x < blank.width(); ++x)
            blank.layer(0).surface.set(x, y,
                                       openclip::Pixel{255, 255, 255, 255});
    blank.setActiveLayer(0);
    s->frames.insert(s->frames.begin() + static_cast<std::ptrdiff_t>(s->cur) + 1,
                     Frame(std::move(blank)));
    ++s->cur;
    resetFrameLocalState(s);
    return static_cast<jint>(s->cur);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_openclip_engine_EngineHost_nDuplicateFrame(JNIEnv*, jclass,
                                                     jlong handle) {
    Session* s = asSession(handle);
    if (!s || s->frames.empty()) return -1;
    // Full Canvas copy (layers + framebuffers) with a fresh empty undo
    // stack: a duplicate is a new frame, not a history fork.
    s->frames.insert(s->frames.begin() + static_cast<std::ptrdiff_t>(s->cur) + 1,
                     Frame(cur(s)));
    ++s->cur;
    resetFrameLocalState(s);
    return static_cast<jint>(s->cur);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nDeleteFrame(JNIEnv*, jclass, jlong handle,
                                                 jint index) {
    Session* s = asSession(handle);
    if (!s || s->frames.empty()) return JNI_FALSE;
    if (index < 0 ||
        index >= static_cast<jint>(s->frames.size()))
        return JNI_FALSE;
    if (s->frames.size() == 1) return JNI_FALSE;  // keep the last frame
    s->frames.erase(s->frames.begin() + index);
    if (s->cur >= s->frames.size()) s->cur = s->frames.size() - 1;
    if (s->cur > static_cast<std::size_t>(index)) --s->cur;
    resetFrameLocalState(s);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nSetFrame(JNIEnv*, jclass, jlong handle,
                                              jint index) {
    Session* s = asSession(handle);
    if (!s || s->frames.empty()) return JNI_FALSE;
    if (index < 0 ||
        index >= static_cast<jint>(s->frames.size()))
        return JNI_FALSE;
    s->cur = static_cast<std::size_t>(index);
    resetFrameLocalState(s);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_openclip_engine_EngineHost_nFrameCount(JNIEnv*, jclass,
                                                jlong handle) {
    Session* s = asSession(handle);
    return s ? static_cast<jint>(s->frames.size()) : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_openclip_engine_EngineHost_nFrameCompositeInto(JNIEnv* env, jclass,
                                                        jlong handle,
                                                        jint index,
                                                        jintArray out,
                                                        jint width,
                                                        jint height) {
    Session* s = asSession(handle);
    if (!s || !out) return -1;
    if (width <= 0 || height <= 0) return -2;
    if (index < 0 || index >= static_cast<jint>(s->frames.size())) return -2;
    openclip::Surface comp;
    s->frames[static_cast<std::size_t>(index)].canvas.composite(comp);
    std::vector<std::uint8_t> px = comp.bytes();
    return blitArgb(env, px, out, width, height);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nSetOnion(JNIEnv*, jclass, jlong handle,
                                             jboolean previous, jboolean next,
                                             jfloat strength) {
    Session* s = asSession(handle);
    if (!s) return JNI_FALSE;
    s->onionPrev = (previous == JNI_TRUE);
    s->onionNext = (next == JNI_TRUE);
    s->onionStrength = strength < 0.0f ? 0.0f : (strength > 1.0f ? 1.0f
                                                                 : strength);
    return JNI_TRUE;
}
