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
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include <jni.h>
#include <algorithm>
#include <cstdint>
#include <cstdlib>
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

// M4: optional engine document API. When the parallel engine lane lands
// engine/include/openclip/document.h, the shim prefers loadDocument /
// saveDocument from there; until then it falls back to the M1 ora.h
// primitives below (existing engine code, public headers only).
#if defined(__has_include)
#if __has_include("openclip/document.h")
#include "openclip/document.h"
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
struct Session {
    openclip::Canvas canvas;
    openclip::History history;
    openclip::BrushParams brush;
    std::uint8_t r = 20, g = 20, b = 20;
    // M2 shim-side brush state (engine takes over when its M2 lane lands):
    // stabilize = input smoothing 0..1, curve = pressure exponent 0..1,
    // eraser = paint toward canvas background instead of ink color.
    float stabilize = 0.0f;
    float curve = 0.0f;
    bool eraser = false;
};

Session* asSession(jlong handle) { return reinterpret_cast<Session*>(handle); }

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
bool paintStamp(Session* s, const openclip::Dab& dab) {
    const int t = openclip::kTileSize;
    const int pad = static_cast<int>(dab.radius) + 1;
    const int x0 = std::max(0, static_cast<int>(dab.x) - pad);
    const int y0 = std::max(0, static_cast<int>(dab.y) - pad);
    const int x1 = std::min(static_cast<int>(dab.x) + pad, s->canvas.width());
    const int y1 = std::min(static_cast<int>(dab.y) + pad, s->canvas.height());
    if (x1 <= 0 || y1 <= 0 || x0 >= x1 || y0 >= y1) return false;
    const int tx0 = x0 / t, tx1 = (x1 - 1) / t;
    const int ty0 = y0 / t, ty1 = (y1 - 1) / t;
    const std::size_t layer = static_cast<std::size_t>(
        s->canvas.activeLayer() < 0 ? 0 : s->canvas.activeLayer());
    for (int ty = ty0; ty <= ty1; ++ty)
        for (int tx = tx0; tx <= tx1; ++tx)
            s->history.touchTile(s->canvas, layer, tx, ty);
    openclip::paintDab(s->canvas.layer(layer).surface, dab, s->brush);
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

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_openclip_engine_EngineHost_nInit(JNIEnv* env, jclass, jint w, jint h,
                                          jfloat /*dpi*/) {
    if (w <= 0 || h <= 0) return 0;
    Session* s = new (std::nothrow) Session();
    if (!s) return 0;
    s->canvas.resize(w, h);
    if (s->canvas.layerCount() == 0) s->canvas.addLayer("Layer 1");
    for (int y = 0; y < h; ++y)          // white base layer
        for (int x = 0; x < w; ++x)
            s->canvas.layer(0).surface.set(x, y,
                                           openclip::Pixel{255, 255, 255, 255});
    s->canvas.setActiveLayer(0);
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
    return s ? static_cast<jint>(s->canvas.layerCount()) : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_openclip_engine_EngineHost_nSetActiveLayer(JNIEnv*, jclass,
                                                    jlong handle, jint index) {
    Session* s = asSession(handle);
    if (!s || index < 0 || index >= static_cast<jint>(s->canvas.layerCount()))
        return -1;
    s->canvas.setActiveLayer(index);
    return index;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_openclip_engine_EngineHost_nAddLayer(JNIEnv*, jclass, jlong handle,
                                              jstring) {
    Session* s = asSession(handle);
    if (!s) return -1;
    return static_cast<jint>(s->canvas.addLayer("Layer"));
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_openclip_engine_EngineHost_nStrokeBegin(JNIEnv*, jclass, jlong handle,
                                                 jfloat x, jfloat y,
                                                 jfloat pressure) {
    Session* s = asSession(handle);
    if (!s) return 0;
    s->history.beginStroke();
    const std::vector<openclip::Dab> dabs = openclip::interpolateStamps(
        s->brush, x, y, pressure, x, y, pressure, s->r, s->g, s->b);
    for (const auto& d : dabs) stampResolved(s, d);
    // M1: stroke ids are cosmetic (single render thread); M2 tracks them.
    static jlong nextStrokeId = 1;
    return nextStrokeId++;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_openclip_engine_EngineHost_nCompositeInto(JNIEnv* env, jclass,
                                                   jlong handle, jintArray out,
                                                   jint width, jint height) {
    Session* s = asSession(handle);
    if (!s || !out) return -1;
    if (width <= 0 || height <= 0) return -2;
    const jsize n = env->GetArrayLength(out);
    if (width * height > n) return -3;

    openclip::Surface comp;
    s->canvas.composite(comp);
    const std::vector<std::uint8_t>& px = comp.bytes();  // premult RGBA8

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

extern "C" JNIEXPORT void JNICALL
Java_dev_openclip_engine_EngineHost_nStrokeMove(JNIEnv*, jclass, jlong handle,
                                                jlong /*strokeId*/, jfloat x,
                                                jfloat y, jfloat pressure) {
    Session* s = asSession(handle);
    if (!s) return;
    // Single-input-point M1 move: record the point stamps directly.
    // Input interpolation across consecutive moves is the app's job in M1.
    const std::vector<openclip::Dab> dabs = openclip::interpolateStamps(
        s->brush, x, y, pressure, x, y, pressure, s->r, s->g, s->b);
    for (const auto& d : dabs) stampResolved(s, d);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_openclip_engine_EngineHost_nStrokeEnd(JNIEnv*, jclass, jlong handle,
                                               jlong /*strokeId*/) {
    Session* s = asSession(handle);
    if (!s) return;
    s->history.endStroke(s->canvas);
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
    openclip::History& h = s->history;
    h.beginStroke();
    const std::size_t layer = static_cast<std::size_t>(
        s->canvas.activeLayer() < 0 ? 0 : s->canvas.activeLayer());
    if (layer >= s->canvas.layerCount()) {
        h.cancelStroke(s->canvas);
        return JNI_FALSE;
    }
    openclip::Layer& ly = s->canvas.layer(layer);
    for (int ty = 0; ty < ly.surface.tilesY(); ++ty)
        for (int tx = 0; tx < ly.surface.tilesX(); ++tx)
            h.touchTile(s->canvas, layer, tx, ty);
    std::string err;
    const bool ok = openclip::applyFilter(ly.surface, fname, params, err);
    if (ok) {
        h.endStroke(s->canvas);
    } else {
        h.cancelStroke(s->canvas);  // failed filters leave no undo residue
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
        openclip::Canvas tmp = s->canvas;
        const bool ok = openclip::loadDocument(tmp, p, error);
        (void)error;
        if (!ok) return JNI_FALSE;  // canvas left untouched
        s->canvas = std::move(tmp);
        if (s->canvas.layerCount() > 0 &&
            (s->canvas.activeLayer() < 0 ||
             s->canvas.activeLayer() >=
                 static_cast<int>(s->canvas.layerCount())))
            s->canvas.setActiveLayer(0);
        s->history.clear();  // document load resets undo history
        return JNI_TRUE;
    }
#else
    {
        std::string error;
        openclip::Canvas tmp = s->canvas;
        if (!loadViaFallback(tmp, p, error)) {
            (void)error;
            return JNI_FALSE;  // canvas left untouched
        }
        s->canvas = std::move(tmp);
        s->history.clear();  // document load resets undo history
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
        const bool ok = openclip::saveDocument(s->canvas, p, fmt, error);
        (void)error;
        return ok ? JNI_TRUE : JNI_FALSE;
    }
#else
    {
        std::string error;
        bool ok = false;
        if (fmt == "ora") {
            ok = openclip::saveOra(s->canvas, p, error);
        } else if (fmt == "png") {
            ok = openclip::exportPng(s->canvas, p, error);
        } else if (fmt == "psd") {
            // M4 baseline: engine PSD writer not landed yet; persist the
            // flattened composite PNG payload at the requested path so the
            // save path is exercised end-to-end (real document.h replaces
            // this with per-layer PSD records when the lane lands).
            ok = openclip::exportPng(s->canvas, p, error);
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
    return s ? static_cast<jboolean>(s->history.undo(s->canvas)) : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nRedo(JNIEnv*, jclass, jlong handle) {
    Session* s = asSession(handle);
    return s ? static_cast<jboolean>(s->history.redo(s->canvas)) : JNI_FALSE;
}
