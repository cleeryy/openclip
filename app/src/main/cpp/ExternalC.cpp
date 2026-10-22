/*
 * OpenClip M1 — JNI shim implementing the frozen contract from
 * ARCHITECTURE.md on top of the engine public headers only.
 * M2 additions (documented in ARCHITECTURE.md): nLoadMyb feeds a .myb
 * document to openclip::parseMyb keeping the session color; nSetBrush
 * also accepts "stabilize"/"curve" 0..1 (stored shim-side until the
 * engine consumes them) and "mode" "brush"|"eraser"|"alpha-erase" —
 * M2 erases toward the canvas background (white), V+1 alpha-erases
 * toward layer alpha (dest-out, ARCHITECTURE.md "V+1 additions").
 * nSetLayerText stores the opaque text fiche (null clears, ORA
 * round trip as x-openclip-*) and nBlitPngIntoLayer replaces a layer's
 * pixels from a PNG file (exact canvas-size match, undoable).
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
#include "openclip/bubble.h"
#include "openclip/canvas.h"
#include "openclip/cels.h"
#include "openclip/filters.h"
#include "openclip/fill.h"
#include "openclip/gif.h"
#include "openclip/gradient.h"
#include "openclip/guides.h"
#include "openclip/ora.h"
#include "openclip/ref.h"
#include "openclip/shapes.h"
#include "openclip/stroke.h"
#include "openclip/surface.h"
#include "openclip/transform.h"
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
 *    "color":"#RRGGBB", "mode":"brush"|"eraser"|"alpha-erase"}
 * (extra keys ignored; "eraser" is the frozen legacy white-paint alias).
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
    int hold = 1;
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
    // V+2: real onion neighbor counts per side (nSetOnionCount, 0..4;
    // nSetOnion maps to 0/1 for backward compatibility).
    int onionBefore = 0, onionAfter = 0;
    float onionStrength = 0.3f;
    // V+2: pending cel-move undo entries (nMoveCel). Each records the
    // post-move frame AND that frame's history depth — the move is the
    // latest op exactly when the current frame matches and its depth
    // is back to the recorded one (exact LIFO against interleaved
    // pixel ops on the same frame; cross-frame strokes live in their
    // own frame stacks per the M7 contract).
    struct CelMove {
        std::size_t frameAfter = 0;
        std::size_t histDepthAfter = 0;
        std::size_t from = 0, to = 0;
    };
    std::vector<CelMove> celMoves;
    // V+2: engine-side selection mask (nSetSelectionMask): w*h alpha
    // bytes (PNG luma, white = selected), session-global like the mask
    // target; cleared on frame structural ops and loads.
    std::vector<std::uint8_t> selMask;
    bool selMaskOn = false;
    // V+2: approximation count from the last layered PSD save
    // (nPsdApproxCount; 0 = pixel-faithful).
    int psdApprox = 0;
    // M9: glTF reference model overlay (tracing aid, composite only).
    openclip::RefModel refModel;
    bool refLoaded = false;
    openclip::RefView refView;
    openclip::BrushParams brush;
    std::uint8_t r = 20, g = 20, b = 20;
    // M2 shim-side brush state (engine takes over when its M2 lane lands):
    // stabilize = input smoothing 0..1, curve = pressure exponent 0..1,
    // eraser = paint toward canvas background instead of ink color.
    // V+1: alphaEraser = dest-out on raster pixels (ARCHITECTURE.md
    // "V+1 additions"); on an armed mask the eraser stamps black.
    float stabilize = 0.0f;
    float curve = 0.0f;
    bool eraser = false;
    bool alphaEraser = false;
    bool strokeOpen = false;
    float lastX = 0.0f, lastY = 0.0f, lastP = 1.0f;
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
    // V+2: persistent document guides (nSetRulers "guides" key,
    // ARCHITECTURE.md "V+2 additions"). Applied in rulerStamp AFTER
    // smoothing (smooth THEN snap, spec §4.4).
    std::vector<openclip::Guide> guides;
    // V+3 snap keys (ARCHITECTURE.md "V+3 additions"): snapTol (px,
    // default 2 = V+2 behavior; <= 0 disables ALL snap), grid
    // (0|8|16|32, default 0 = off), snapPaths (neighbor vertices).
    float snapTol = 2.0f;
    float snapGridStep = 0.0f;
    bool snapPaths = false;
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
        // alpha-erase and eraser flags do not apply to masks: the V+1
        // rule stamps BLACK (hide) for alpha-erase, session ink for
        // the legacy eraser (unchanged M5 behavior). V+3: the mixer
        // knife is ignored on mask targets (redirect-to-mask stroke,
        // same guard as brush/gomme V+1).
        openclip::Dab stamped =
            s->alphaEraser
                ? openclip::dabFromPressure(s->brush, dab.x, dab.y,
                                           dab.pressure, 0, 0, 0)
                : openclip::dabFromPressure(s->brush, dab.x, dab.y,
                                           dab.pressure, dab.r, dab.g,
                                           dab.b);
        for (int ty = ty0; ty <= ty1; ++ty)
            for (int tx = tx0; tx <= tx1; ++tx)
                hist(s).touchTile(cur(s), layer, tx, ty, true);
        openclip::paintMaskDab(cur(s).layer(layer).mask, stamped, s->brush);
        return true;
    }
    for (int ty = ty0; ty <= ty1; ++ty)
        for (int tx = tx0; tx <= tx1; ++tx)
            hist(s).touchTile(cur(s), layer, tx, ty);
    if (s->alphaEraser) {
        openclip::eraseDab(cur(s).layer(layer).surface,
                           openclip::dabFromPressure(s->brush, dab.x, dab.y,
                                                    dab.pressure, dab.r,
                                                    dab.g, dab.b),
                           s->brush);
    } else {
        openclip::paintDab(cur(s).layer(layer).surface, dab, s->brush);
    }
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
void collectSnapPoints(const openclip::Layer& l,
                       std::vector<openclip::Vec2>& out) {
    if (l.isVector) {
        for (const auto& p : l.vectorData)
            for (const auto& q : p.pts) {
                if (out.size() >= 65536) return;
                out.push_back(q);
            }
    }
    if (l.isGroup)
        for (const auto& ch : l.children) collectSnapPoints(ch, out);
}

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
        // M9: snap to the NEAREST segment of the perspective grid
        // (1 segment = M6 behavior, 2 segments = 3-point perspective).
        float bestDist = 1e30f, bx = px, by = py;
        for (const auto& seg : s->lines) {
            const float ax = seg.first.x, ay = seg.first.y;
            const float bxD = seg.second.x - ax, byD = seg.second.y - ay;
            const float len2 = bxD * bxD + byD * byD;
            float t = len2 > 0 ? ((px - ax) * bxD + (py - ay) * byD) / len2 : 0;
            t = std::max(0.0f, std::min(1.0f, t));
            const float sx = ax + t * bxD, sy = ay + t * byD;
            const float ddx = px - sx, ddy = py - sy;
            const float d2 = ddx * ddx + ddy * ddy;
            if (d2 < bestDist) {
                bestDist = d2;
                bx = sx;
                by = sy;
            }
        }
        px = bx;
        py = by;
    }
    const float mappedP =
        openclip::PointCurve::fromJsonCurve(s->curve).at(pressure);
    if (s->strokeOpen && s->stabilize > 0.0f) {
        const float a = std::min(0.85f, s->stabilize * 0.85f);
        px = s->lastX * a + px * (1.0f - a);
        py = s->lastY * a + py * (1.0f - a);
    }
    // V+2 guides snap AFTER smoothing (smooth THEN snap, spec §4.4) —
    // the M6 ellipse/line projection above keeps running first, so a
    // guide within tolerance wins over the legacy ruler. V+3: the
    // tolerance is snapTol (<= 0 disables everything), then the grid,
    // then neighboring vector-path vertices (spec §2 order).
    if (s->snapTol > 0.0f) {
        if (!s->guides.empty()) {
            float gx = px, gy = py;
            if (openclip::snapGuides(s->guides, gx, gy, s->snapTol)) {
                px = gx;
                py = gy;
            }
        }
        if (s->snapGridStep > 0.0f)
            openclip::snapGrid(px, py, s->snapGridStep, s->snapTol);
        if (s->snapPaths) {
            std::vector<openclip::Vec2> pts;
            for (std::size_t li = 0;
                 li < static_cast<std::size_t>(cur(s).layerCount()); ++li)
                collectSnapPoints(cur(s).layer(li), pts);
            openclip::snapPathPoints(pts, px, py, s->snapTol);
        }
    }
    const float x0 = s->strokeOpen ? s->lastX : px;
    const float y0 = s->strokeOpen ? s->lastY : py;
    const float p0 = s->strokeOpen ? s->lastP : mappedP;
    const std::vector<openclip::Dab> base = openclip::interpolateStamps(
        s->brush, x0, y0, p0, px, py, mappedP, s->r, s->g, s->b);
    for (const auto& d : base) stampResolved(s, d);
    if (s->symX) {
        const float mx0 = 2 * s->symXAt - x0;
        const float mx = 2 * s->symXAt - px;
        for (const auto& d : openclip::interpolateStamps(
                 s->brush, mx0, y0, p0, mx, py, mappedP, s->r, s->g,
                 s->b))
            stampResolved(s, d);
    }
    if (s->symY) {
        const float my0 = 2 * s->symYAt - y0;
        const float my = 2 * s->symYAt - py;
        for (const auto& d : openclip::interpolateStamps(
                 s->brush, x0, my0, p0, px, my, mappedP, s->r, s->g,
                 s->b))
            stampResolved(s, d);
    }
    if (s->symX && s->symY) {  // quadrant mirror
        const float mx0 = 2 * s->symXAt - x0, my0 = 2 * s->symYAt - y0;
        const float mx = 2 * s->symXAt - px, my = 2 * s->symYAt - py;
        for (const auto& d : openclip::interpolateStamps(
                 s->brush, mx0, my0, p0, mx, my, mappedP, s->r, s->g,
                 s->b))
            stampResolved(s, d);
    }
    s->lastX = px;
    s->lastY = py;
    s->lastP = mappedP;
    s->strokeOpen = true;
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
    // V+3 mixer knife (ARCHITECTURE.md "V+3 additions"): clamped
    // defensively like stabilize/curve above; mix 0 = plain brush.
    const float mix = static_cast<float>(jsonNum(j, "mix", s->brush.mix));
    s->brush.mix = mix < 0.0f ? 0.0f : (mix > 1.0f ? 1.0f : mix);
    const float mixR =
        static_cast<float>(jsonNum(j, "mixRadius", s->brush.mixRadius));
    s->brush.mixRadius = mixR < 0.5f ? 0.5f : (mixR > 2.0f ? 2.0f : mixR);
    const std::string mode = jsonStr(j, "mode");
    if (mode == "alpha-erase") {
        s->alphaEraser = true;
        s->eraser = false;
    } else if (mode == "eraser") {
        // Legacy alias (frozen M2/slice-5 behavior): paints toward the
        // canvas background, kept for old presets/docs.
        s->eraser = true;
        s->alphaEraser = false;
    } else if (mode == "brush") {
        s->eraser = false;
        s->alphaEraser = false;
    }
    const std::string color = jsonStr(j, "color");
    if (!color.empty()) parseColor(color, s->r, s->g, s->b);
}

// V+1 text fiche parser (ARCHITECTURE.md "V+1 additions", M8 pattern):
// unknown keys ignored, missing keys keep code defaults.
void parseTextDescriptor(const std::string& j, openclip::Layer& l) {
    const std::string t = jsonStr(j, "text");
    if (t.empty()) return;  // no fiche without content
    l.textContent = t.substr(0, 8192);
    const std::string font = jsonStr(j, "font");
    l.textFont = font.empty() ? "sans-serif" : font.substr(0, 128);
    const double size = jsonNum(j, "size", 0.0);
    l.textSizePx =
        (size >= 1.0 && size <= 4096.0) ? static_cast<int>(size) : 0;
    const std::string style = jsonStr(j, "style");
    l.textStyle = style.empty() ? "regular" : style.substr(0, 128);
    const std::string align = jsonStr(j, "align");
    l.textAlign = align.empty() ? "left" : align.substr(0, 128);
    const double leading = jsonNum(j, "leading", 1.0);
    l.textLeading =
        (leading >= 0.5 && leading <= 4.0) ? leading : 1.0;
    const double tracking = jsonNum(j, "tracking", 0.0);
    l.textTracking =
        (tracking >= -64.0 && tracking <= 64.0) ? tracking : 0.0;
    const std::string color = jsonStr(j, "color");
    l.textColor = color.empty() ? "#141414" : color.substr(0, 128);
    const std::string box = jsonStr(j, "box");
    l.textBox = box.substr(0, 128);
    // T2 extensions (ARCHITECTURE.md §T2 §1–§2, opaque M3 keys).
    // runs arrives as a raw JSON array: capture the bracketed span
    // (bounded); string forms fall back to jsonStr.
    l.textRuns.clear();
    {
        const std::size_t at = j.find("\"runs\"");
        if (at != std::string::npos) {
            const std::size_t colon = j.find(':', at + 6);
            if (colon != std::string::npos) {
                std::size_t q = colon + 1;
                while (q < j.size() && (j[q] == ' ' || j[q] == '\t')) ++q;
                if (q < j.size() && j[q] == '[') {
                    int dep = 0;
                    std::size_t k = q;
                    bool inS = false;
                    while (k < j.size() && k - q < 16384) {
                        const char c = j[k];
                        if (c == '"' && (k == 0 || j[k - 1] != '\\'))
                            inS = !inS;
                        if (!inS) {
                            if (c == '[') ++dep;
                            if (c == ']') {
                                --dep;
                                if (dep == 0) {
                                    ++k;
                                    break;
                                }
                            }
                        }
                        ++k;
                    }
                    if (dep == 0) l.textRuns = j.substr(q, k - q);
                } else {
                    l.textRuns = jsonStr(j, "runs").substr(0, 16384);
                }
            }
        }
    }
    // outline: string "#RRGGBB" plus optional object {"w":..,
    // "color":..} — sniff the first non-space char like runs above.
    l.textOutline.clear();
    {
        const std::size_t at = j.find("\"outline\"");
        if (at != std::string::npos) {
            const std::size_t colon = j.find(':', at + 9);
            if (colon != std::string::npos) {
                std::size_t q = colon + 1;
                while (q < j.size() && (j[q] == ' ' || j[q] == '\t')) ++q;
                if (q < j.size() && j[q] == '{') {
                    int dep = 0;
                    std::size_t k = q;
                    bool inS = false;
                    while (k < j.size() && k - q < 256) {
                        const char c = j[k];
                        if (c == '"' && (k == 0 || j[k - 1] != '\\'))
                            inS = !inS;
                        if (!inS) {
                            if (c == '{') ++dep;
                            if (c == '}') {
                                --dep;
                                if (dep == 0) {
                                    ++k;
                                    break;
                                }
                            }
                        }
                        ++k;
                    }
                    if (dep == 0) l.textOutline = j.substr(q, k - q);
                } else {
                    l.textOutline = jsonStr(j, "outline").substr(0, 256);
                }
            }
        }
    }
    const std::string fontfile = jsonStr(j, "fontfile");
    l.textFontFile = fontfile.substr(0, 128);
    const std::string dir = jsonStr(j, "dir");
    l.textDir = (dir == "v") ? "v" : "";
    const std::string hy = jsonStr(j, "hyphen");
    const double hyn = jsonNum(j, "hyphen", -1.0);
    l.textHyphen = (hy == "1" || hy == "true" || hyn == 1.0) ? "1" : "";
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
           n == "svg:overlay" || n == "svg:plus" || n == "svg:soft-light" ||
           // V+2 additions (blend.h, ARCHITECTURE.md "V+2 additions").
           n == "hard-light" || n == "hard_light" || n == "hardlight" ||
           n == "difference" || n == "color" || n == "luminosity" ||
           n == "svg:hard-light" || n == "svg:difference" ||
           n == "svg:color" || n == "svg:luminosity";
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
    // V+2: vector-layer flag (SVG export + Bézier discovery read it;
    // absent = raster, the app parses tolerantly).
    out += ",\"vector\":";
    out += l.isVector ? "true" : "false";
    // M8: adjustment descriptor (absent = normal layer; the app parses
    // tolerantly). V+2 curves adds the "curve" canonical spec.
    if (!l.adjustType.empty()) {
        out += ",\"adjust\":{\"type\":";
        appendJsonStr(out, l.adjustType);
        out += ",\"amount\":";
        char abuf[32];
        std::snprintf(abuf, sizeof abuf, "%g", l.adjustAmount);
        out += abuf;
        out += ",\"stops\":";
        appendJsonStr(out, l.adjustStops);
        if (!l.adjustCurve.empty()) {
            out += ",\"curve\":";
            appendJsonStr(out, l.adjustCurve);
        }
        out += "}";
    }
    // V+1: text fiche (absent = normal raster layer; the app parses
    // tolerantly and shows the Tx badge + re-edit). T2 keys ride along
    // (runs/outline/fontfile/dir/hyphen, ARCHITECTURE.md §T2).
    if (l.hasText()) {
        out += ",\"text\":{\"content\":";
        appendJsonStr(out, l.textContent);
        out += ",\"font\":";
        appendJsonStr(out, l.textFont);
        out += ",\"size\":";
        out += std::to_string(l.textSizePx);
        out += ",\"style\":";
        appendJsonStr(out, l.textStyle);
        out += ",\"align\":";
        appendJsonStr(out, l.textAlign);
        out += ",\"leading\":";
        char lbuf[32];
        std::snprintf(lbuf, sizeof lbuf, "%g", l.textLeading);
        out += lbuf;
        out += ",\"tracking\":";
        char tbuf[32];
        std::snprintf(tbuf, sizeof tbuf, "%g", l.textTracking);
        out += tbuf;
        out += ",\"color\":";
        appendJsonStr(out, l.textColor);
        out += ",\"box\":";
        appendJsonStr(out, l.textBox);
        if (!l.textRuns.empty()) {
            // Stored as a raw JSON array span when possible (the app
            // sends arrays); foreign string forms are escaped.
            out += ",\"runs\":";
            if (l.textRuns[0] == '[')
                out += l.textRuns;
            else
                appendJsonStr(out, l.textRuns);
        }
        if (!l.textOutline.empty()) {
            // Stored as a raw JSON object span when possible.
            out += ",\"outline\":";
            if (l.textOutline[0] == '{')
                out += l.textOutline;
            else
                appendJsonStr(out, l.textOutline);
        }
        if (!l.textFontFile.empty()) {
            out += ",\"fontfile\":";
            appendJsonStr(out, l.textFontFile);
        }
        if (!l.textDir.empty()) {
            out += ",\"dir\":";
            appendJsonStr(out, l.textDir);
        }
        if (!l.textHyphen.empty()) {
            out += ",\"hyphen\":true";
        }
        out += "}";
    }
    // T2 bubble fiche (absent = normal layer; the app shows the o
    // badge + panel — ARCHITECTURE.md §T2 §5).
    if (l.hasBubble()) {
        out += ",\"bubble\":{\"shape\":";
        appendJsonStr(out, l.bubbleShape);
        out += ",\"frame\":";
        appendJsonStr(out, l.bubbleFrame);
        out += ",\"tail\":";
        appendJsonStr(out, l.bubbleTail);
        out += ",\"fill\":";
        appendJsonStr(out, l.bubbleFill.empty() ? "#FFFFFF" : l.bubbleFill);
        out += ",\"stroke\":";
        appendJsonStr(out,
                      l.bubbleStroke.empty() ? "#000000" : l.bubbleStroke);
        out += ",\"strokeW\":";
        char wbuf[32];
        std::snprintf(wbuf, sizeof wbuf, "%g", l.bubbleStrokeW);
        out += wbuf;
        out += ",\"textLayer\":";
        out += std::to_string(l.bubbleTextLayer);
        out += "}";
    }
    // T2 vector counters (the visible over-cap counter + live count;
    // styles re-read via nVectorPaths — ARCHITECTURE.md §T2 §4).
    if (l.isVector) {
        out += ",\"vectorPaths\":";
        out += std::to_string(l.vectorData.size());
        out += ",\"vectorStored\":";
        out += l.vectorStored ? "true" : "false";
    }
    // V+2: generic fiche map (absent = no fiches; the app parses
    // tolerantly and rebuilds panels/bulles/speed from it).
    if (!l.meta.empty()) {
        out += ",\"meta\":{";
        bool first = true;
        for (const auto& kv : l.meta) {
            if (!first) out += ",";
            first = false;
            appendJsonStr(out, kv.first);
            out += ":";
            appendJsonStr(out, kv.second);
        }
        out += "}";
    }
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

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nSetLayerAdjustment(JNIEnv* env, jclass,
                                                        jlong handle,
                                                        jint index,
                                                        jstring json) {
    Session* s = asSession(handle);
    if (!s) return JNI_FALSE;
    if (index < 0 ||
        index >= static_cast<jint>(cur(s).layerCount()))
        return JNI_FALSE;
    openclip::Layer& l = cur(s).layer(static_cast<std::size_t>(index));
    const std::string j = json ? toStd(env, json) : std::string();
    if (j.empty()) {  // null/empty clears the descriptor
        l.adjustType.clear();
        l.adjustAmount = 0.0;
        l.adjustStops.clear();
        l.adjustCurve.clear();
        return JNI_TRUE;
    }
    openclip::parseAdjustDescriptor(j, l.adjustType, l.adjustAmount,
                                    l.adjustStops);
    // V+2 curves: the canonical LUT spec lives in adjustCurve (parsed
    // from the same JSON; empty/invalid = honest identity, the
    // descriptor itself is still kept verbatim).
    l.adjustCurve.clear();
    if (l.adjustType == "curves") {
        std::string t, ch, spec;
        openclip::parseCurvesDescriptor(j, t, ch, spec);
        l.adjustCurve = spec;
    }
    return JNI_TRUE;
}

// V+1 text fiche (ARCHITECTURE.md "V+1 additions", M8 pattern): null or
// empty json clears the fiche; otherwise the parsed fiche is stored
// (opaque to the engine) and round-trips through ORA as x-openclip-*.
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nSetLayerText(JNIEnv* env, jclass,
                                                  jlong handle, jint index,
                                                  jstring json) {
    Session* s = asSession(handle);
    if (!s) return JNI_FALSE;
    if (index < 0 ||
        index >= static_cast<jint>(cur(s).layerCount()))
        return JNI_FALSE;
    openclip::Layer& l = cur(s).layer(static_cast<std::size_t>(index));
    const std::string j = json ? toStd(env, json) : std::string();
    if (j.empty()) {  // null/empty clears the descriptor
        l.textContent.clear();
        l.textFont.clear();
        l.textSizePx = 0;
        l.textStyle.clear();
        l.textAlign.clear();
        l.textLeading = 1.0;
        l.textTracking = 0.0;
        l.textColor.clear();
        l.textBox.clear();
        l.textRuns.clear();
        l.textOutline.clear();
        l.textFontFile.clear();
        l.textDir.clear();
        l.textHyphen.clear();
        return JNI_TRUE;
    }
    parseTextDescriptor(j, l);
    return JNI_TRUE;
}

// V+1 pixel commit (ARCHITECTURE.md "V+1 additions"): decodes the PNG
// at path and REPLACES the layer surface pixels. Requires an exact
// canvas-size match; false + layer untouched otherwise. Undoable via
// full-layer tile snapshots (filter pattern).
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nBlitPngIntoLayer(JNIEnv* env, jclass,
                                                      jlong handle,
                                                      jint index,
                                                      jstring path) {
    Session* s = asSession(handle);
    if (!s || !path) return JNI_FALSE;
    if (index < 0 ||
        index >= static_cast<jint>(cur(s).layerCount()))
        return JNI_FALSE;
    const std::string p = toStd(env, path);
    if (p.empty()) return JNI_FALSE;
    openclip::Surface decoded;
    std::string error;
    if (!openclip::decodePngFile(p, decoded, error)) return JNI_FALSE;
    if (decoded.width() != cur(s).width() ||
        decoded.height() != cur(s).height())
        return JNI_FALSE;  // layer untouched
    openclip::Layer& ly =
        cur(s).layer(static_cast<std::size_t>(index));
    if (ly.isGroup) return JNI_FALSE;
    openclip::History& h = hist(s);
    h.beginStroke();
    for (int ty = 0; ty < ly.surface.tilesY(); ++ty)
        for (int tx = 0; tx < ly.surface.tilesX(); ++tx)
            h.touchTile(cur(s), static_cast<std::size_t>(index), tx, ty);
    ly.surface.bytes().swap(decoded.bytes());
    h.endStroke(cur(s));
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_openclip_engine_EngineHost_nLoadGlbReference(JNIEnv* env, jclass,
                                                      jlong handle,
                                                      jstring path) {
    Session* s = asSession(handle);
    if (!s || !path) return -1;
    const std::string p = toStd(env, path);
    if (p.empty()) return -1;
    openclip::RefModel m;
    std::string err;
    if (!openclip::loadRefModel(p, m, err)) {
        s->refLoaded = false;
        return -1;
    }
    s->refModel = std::move(m);
    s->refLoaded = true;
    return static_cast<jint>(s->refModel.edges.size());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nSetRefView(JNIEnv* env, jclass,
                                                jlong handle, jstring json) {
    Session* s = asSession(handle);
    if (!s) return JNI_FALSE;
    if (json) openclip::parseRefViewJson(toStd(env, json), s->refView);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nSetRefVisible(JNIEnv*, jclass,
                                                   jlong handle,
                                                   jboolean on) {
    Session* s = asSession(handle);
    if (!s) return JNI_FALSE;
    s->refView.visible = (on == JNI_TRUE);
    return JNI_TRUE;
}

// V+3 pose rig (ARCHITECTURE.md "V+3 additions"): read-only joints
// introspection (M9 nVectorPaths pattern) + pose merge. No pixels,
// overlay-only, never in undo, never in nFrameCompositeInto.
extern "C" JNIEXPORT jstring JNICALL
Java_dev_openclip_engine_EngineHost_nRefJoints(JNIEnv* env, jclass,
                                               jlong handle) {
    Session* s = asSession(handle);
    if (!s) return nullptr;
    return env->NewStringUTF(openclip::refJointsJson(s->refModel).c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nSetRefPose(JNIEnv* env, jclass,
                                                jlong handle, jstring json) {
    Session* s = asSession(handle);
    if (!s) return JNI_FALSE;
    // Stored even with no model loaded (re-resolved on load); unknown
    // bones ignored, axes clamped, never fails on a live handle.
    if (json) openclip::applyRefPoseJson(s->refModel, toStd(env, json));
    return JNI_TRUE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_openclip_engine_EngineHost_nStrokeBegin(JNIEnv*, jclass, jlong handle,
                                                 jfloat x, jfloat y,
                                                 jfloat pressure) {
    Session* s = asSession(handle);
    if (!s) return 0;
    s->strokeOpen = false;
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
// V+2: structural frame ops (add/duplicate/delete/load, NOT plain
// switches) also drop the engine selection mask (pixel-bound) and the
// pending cel-move undos (frame indexes shifted); plain switches keep
// both (moves stay undoable after returning).
static void resetFrameLocalState(Session* s, bool structural) {
    s->maskTarget = -1;
    s->maskTargetOn = false;
    if (structural) {
        s->selMask.clear();
        s->selMaskOn = false;
        s->celMoves.clear();
    }
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
    // M7 onion skin (V+2: up to N neighbors per side, counts from
    // nSetOnionCount; nSetOnion maps to 0/1): neighbor composites
    // tinted gray (x0.4) at strength alpha, drawn beneath the current
    // frame (normal over). A fully opaque current frame hides the
    // ghosts; a transparent one (hidden base layer) reveals them —
    // that is the intended onion workflow.
    const std::vector<std::size_t> ghosts = openclip::onionRange(
        s->cur, s->frames.size(), s->onionBefore, s->onionAfter);
    if (!ghosts.empty()) {
        std::vector<std::uint8_t> under(px.size(), 0);
        for (std::size_t gi : ghosts) {
            openclip::Surface n;
            s->frames[gi].canvas.composite(n);
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
    // M9: reference wireframe draws OVER the frame (tracing aid; never
    // in nFrameCompositeInto so thumbnails/export stay clean).
    if (s->refLoaded && s->refView.visible) {
        int drawnEdges = 0;
        openclip::renderRefEdges(s->refModel, s->refView, width, height, px,
                                 drawnEdges);
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
    s->strokeOpen = false;
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

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nApplyTone(JNIEnv* env, jclass,
                                               jlong handle, jstring name) {
    Session* s = asSession(handle);
    if (!s) return JNI_FALSE;
    const std::string tname = toStd(env, name);
    // M10 contract: active layer, undoable like a filter (full-layer
    // tile snapshots; unknown name leaves the layer untouched).
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
    const bool ok = openclip::applyTone(ly.surface, tname);
    if (ok) {
        h.endStroke(cur(s));
    } else {
        h.cancelStroke(cur(s));
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ---- T1 paint tools (ARCHITECTURE.md "T1 additions") ----
//
// Shared guards: the ACTIVE layer must be a paintable raster layer
// (top-level, not a group, not vector, no adjustment descriptor —
// adjustment layers carry no pixels, vector layers rasterize M6 data
// at composite time so a surface write would be invisible). Groups,
// vector and adjustment layers are refused (false, layer untouched);
// visibility is left to the app guard (same as nApplyFilter). When
// the engine selection mask (V+2) is live it constrains the paint;
// otherwise the app-side V+1 guard (tap refused before the call)
// applies — never both sources at once (§1.4/§2.4/§3.4).

bool activePaintLayer(Session* s, std::size_t& idx) {
    if (!s) return false;
    const int active = cur(s).activeLayer();
    if (active < 0 ||
        active >= static_cast<int>(cur(s).layerCount()))
        return false;
    idx = static_cast<std::size_t>(active);
    const openclip::Layer& ly = cur(s).layer(idx);
    if (ly.isGroup || ly.isVector || !ly.adjustType.empty()) return false;
    return true;
}

// Undoable whole-surface paint helper (filter pattern): snapshots
// every tile of the active layer, runs `op`, ends or cancels.
template <typename Op>
bool paintUndoable(Session* s, std::size_t layerIdx, Op op) {
    openclip::History& h = hist(s);
    h.beginStroke();
    openclip::Layer& ly = cur(s).layer(layerIdx);
    for (int ty = 0; ty < ly.surface.tilesY(); ++ty)
        for (int tx = 0; tx < ly.surface.tilesX(); ++tx)
            h.touchTile(cur(s), layerIdx, tx, ty);
    const bool ok = op(ly.surface);
    if (ok) {
        h.endStroke(cur(s));
    } else {
        h.cancelStroke(cur(s));
    }
    return ok;
}

// Selection bytes for paint ops: live engine mask, or nullptr when
// unset (the app-side V+1 guard owns the constraint then).
const std::uint8_t* paintMask(Session* s, int& stride) {
    stride = 0;
    if (!s->selMaskOn || s->selMask.empty()) return nullptr;
    if ((int)s->selMask.size() != cur(s).width() * cur(s).height())
        return nullptr;
    stride = cur(s).width();
    return s->selMask.data();
}

// Shim-side JSON bool (M3 rule: unknown keys ignored, missing =
// default). Accepts true/false literals only.
bool jsonBool(const std::string& json, const char* key, bool fallback) {
    const std::string pat = std::string("\"") + key + "\"";
    const std::size_t at = json.find(pat);
    if (at == std::string::npos) return fallback;
    const std::size_t colon = json.find(':', at + pat.size());
    if (colon == std::string::npos) return fallback;
    std::size_t p = colon + 1;
    while (p < json.size() && (json[p] == ' ' || json[p] == '\t')) ++p;
    if (json.compare(p, 4, "true") == 0) return true;
    if (json.compare(p, 5, "false") == 0) return false;
    return fallback;
}

// Parse a "#RRGGBB:pos" stops array after the "stops" key into engine
// stops (positions parsed as float, unsorted — the engine sorts).
// Returns false when fewer than 2 valid stops parse.
bool parsePaintStops(const std::string& json,
                     std::vector<openclip::GradientStop>& stops) {
    stops.clear();
    const std::size_t sk = json.find("\"stops\"");
    if (sk == std::string::npos) return false;
    const std::size_t open = json.find('[', sk + 7);
    if (open == std::string::npos) return false;
    std::size_t p = open + 1;
    while (p < json.size() && stops.size() < 8) {
        const std::size_t hash = json.find('#', p);
        if (hash == std::string::npos) break;
        if (hash + 7 >= json.size()) break;
        std::uint8_t r = 0, g = 0, b = 0;
        if (!parseColor(json.substr(hash, 7), r, g, b)) {
            p = hash + 1;
            continue;
        }
        float pos = -1.0f;
        const std::size_t colon = json.find(':', hash + 7);
        if (colon != std::string::npos && colon < hash + 9) {
            char* e = nullptr;
            const double v = std::strtod(json.c_str() + colon + 1, &e);
            if (e != json.c_str() + colon + 1 &&
                v >= 0.0 && v <= 1.0)
                pos = static_cast<float>(v);
        }
        if (pos >= 0.0f) {
            openclip::GradientStop st;
            st.r = r;
            st.g = g;
            st.b = b;
            st.pos = pos;
            stops.push_back(st);
        }
        p = hash + 8;
        const std::size_t close = json.find(']', p);
        if (close != std::string::npos &&
            json.find('#', p) == std::string::npos)
            break;
    }
    return stops.size() >= 2;
}

// Parse a [[x,y],...] point array after `key` (free-gradient anchors).
// Depth-tracked: inner ']' closes a point, the outer ']' ends the
// array (a naive first-']'-wins scan would stop after anchor 0).
bool parsePtsArray(const std::string& json, const char* key,
                   std::vector<std::pair<float, float>>& pts) {
    pts.clear();
    const std::string pat = std::string("\"") + key + "\"";
    const std::size_t kk = json.find(pat);
    if (kk == std::string::npos) return false;
    const std::size_t open = json.find('[', kk + pat.size());
    if (open == std::string::npos) return false;
    int depth = 0;
    std::size_t p = open;
    while (p < json.size() && pts.size() < 8) {
        const char c = json[p];
        if (c == '[') {
            ++depth;
            ++p;
            continue;
        }
        if (c == ']') {
            if (--depth <= 0) break;
            ++p;
            continue;
        }
        if ((c >= '0' && c <= '9') || c == '-' || c == '.') {
            char* e1 = nullptr;
            const double x = std::strtod(json.c_str() + p, &e1);
            if (e1 == json.c_str() + p) {
                ++p;
                continue;
            }
            std::size_t q = static_cast<std::size_t>(e1 - json.c_str());
            while (q < json.size() && (json[q] == ' ' || json[q] == ','))
                ++q;
            char* e2 = nullptr;
            const double y = std::strtod(json.c_str() + q, &e2);
            if (e2 == json.c_str() + q) {
                p = q + 1;
                continue;
            }
            pts.emplace_back(static_cast<float>(x), static_cast<float>(y));
            p = static_cast<std::size_t>(e2 - json.c_str());
            continue;
        }
        ++p;
    }
    return !pts.empty();
}

// T1 bucket: nFloodFill(handle, x, y, colorHex, json). json M3 rule:
// {tolerance:0..1 (0.12), antialias:bool (true), expandPx:0..2 (0),
// sampleMerged:bool (false)}. Seed = (x,y) on the active layer, or
// the composite pull when sampleMerged (one pull, kernel stays
// composite-free via floodFillSeeded).
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nFloodFill(JNIEnv* env, jclass,
                                               jlong handle, jint x, jint y,
                                               jstring colorHex,
                                               jstring paramsJson) {
    Session* s = asSession(handle);
    if (!s) return JNI_FALSE;
    std::size_t layer = 0;
    if (!activePaintLayer(s, layer)) return JNI_FALSE;
    std::uint8_t r = 20, g = 20, b = 20;
    if (!parseColor(toStd(env, colorHex), r, g, b)) return JNI_FALSE;
    const std::string j = paramsJson ? toStd(env, paramsJson) : std::string();
    openclip::FillParams p;
    p.tolerance =
        static_cast<float>(jsonNum(j, "tolerance", p.tolerance));
    p.antialias = jsonBool(j, "antialias", p.antialias);
    p.expandPx = static_cast<int>(jsonNum(j, "expandPx", p.expandPx));
    const bool merged = jsonBool(j, "sampleMerged", false);
    int stride = 0;
    const std::uint8_t* mask = paintMask(s, stride);
    if (merged) {
        openclip::Surface comp;
        cur(s).composite(comp);
        if (x < 0 || y < 0 || x >= comp.width() || y >= comp.height())
            return JNI_FALSE;
        const std::vector<std::uint8_t>& px = comp.bytes();
        const std::size_t i =
            (static_cast<std::size_t>(y) * comp.width() + x) * 4;
        const double lum = 0.299 * px[i] + 0.587 * px[i + 1] + 0.114 * px[i + 2];
        return paintUndoable(s, layer, [&](openclip::Surface& surf) {
            std::string err;
            return openclip::floodFillSeeded(surf, x, y, lum, r, g, b, p,
                                             mask, stride, err);
        }) ? JNI_TRUE : JNI_FALSE;
    }
    return paintUndoable(s, layer, [&](openclip::Surface& surf) {
        std::string err;
        return openclip::floodFill(surf, x, y, r, g, b, p, mask, stride, err);
    }) ? JNI_TRUE : JNI_FALSE;
}

// T1 wand: nMagicWand(handle, x, y, json). json M3 rule:
// {tolerance:0..1 (0.15), source:"layer"|"merged" ("layer"),
// combine:"set"|"add"|"sub" ("set")}. Writes the engine selection
// mask V+2 (session-global, cleared on frame structural ops/loads);
// the app mirrors it app-side for the marching-ants overlay.
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nMagicWand(JNIEnv* env, jclass,
                                               jlong handle, jint x, jint y,
                                               jstring paramsJson) {
    Session* s = asSession(handle);
    if (!s) return JNI_FALSE;
    std::size_t layer = 0;
    if (!activePaintLayer(s, layer)) return JNI_FALSE;
    const std::string j = paramsJson ? toStd(env, paramsJson) : std::string();
    const float tol =
        static_cast<float>(jsonNum(j, "tolerance", 0.15));
    std::string source = jsonStr(j, "source");
    if (source.empty()) source = "layer";
    std::string combineS = jsonStr(j, "combine");
    if (combineS.empty()) combineS = "set";
    int combine = -1;
    if (combineS == "set") combine = 0;
    else if (combineS == "add") combine = 1;
    else if (combineS == "sub") combine = 2;
    if (combine < 0) return JNI_FALSE;
    const openclip::Surface* src = nullptr;
    openclip::Surface comp;
    if (source == "merged") {
        cur(s).composite(comp);
        src = &comp;
    } else if (source == "layer") {
        src = &cur(s).layer(layer).surface;
    } else {
        return JNI_FALSE;
    }
    const int w = src->width(), h = src->height();
    if (w <= 0 || h <= 0) return JNI_FALSE;
    if (combine == 0 || (int)s->selMask.size() != w * h)
        s->selMask.assign(static_cast<std::size_t>(w) * h, 0);
    std::string err;
    if (!openclip::magicWand(*src, x, y, tol, combine, s->selMask.data(),
                             w, err))
        return JNI_FALSE;
    s->selMaskOn = true;
    // Minimum-area rule (spec §4.2) is enforced APP-SIDE: the engine
    // writes the true region; the app drops sub-4x4 selections with a
    // hint (keeps the kernel honest and testable).
    return JNI_TRUE;
}

// T1 gradient: nPaintGradient(handle, json). Stops use the gradientMap
// encoding ["#RRGGBB:pos",...] (consistency); free anchors pts:[[x,y]].
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nPaintGradient(JNIEnv* env, jclass,
                                                   jlong handle,
                                                   jstring paramsJson) {
    Session* s = asSession(handle);
    if (!s || !paramsJson) return JNI_FALSE;
    std::size_t layer = 0;
    if (!activePaintLayer(s, layer)) return JNI_FALSE;
    const std::string j = toStd(env, paramsJson);
    openclip::GradientSpec spec;
    std::string kind = jsonStr(j, "kind");
    spec.kind = kind.empty() ? "linear" : kind;
    spec.x0 = static_cast<float>(jsonNum(j, "x0", 0));
    spec.y0 = static_cast<float>(jsonNum(j, "y0", 0));
    spec.x1 = static_cast<float>(jsonNum(j, "x1", 1));
    spec.y1 = static_cast<float>(jsonNum(j, "y1", 0));
    if (!parsePaintStops(j, spec.stops)) return JNI_FALSE;
    if (spec.kind == "free") {
        if (!parsePtsArray(j, "pts", spec.pts)) return JNI_FALSE;
    }
    spec.dither = jsonBool(j, "dither", true);
    int stride = 0;
    const std::uint8_t* mask = paintMask(s, stride);
    return paintUndoable(s, layer, [&](openclip::Surface& surf) {
        std::string err;
        return openclip::paintGradient(surf, spec, mask, stride, err);
    }) ? JNI_TRUE : JNI_FALSE;
}

// T1 figure: nStampShape(handle, json). Frame normalized shim-side
// (clamped to canvas); colors "#RRGGBB" (missing = black).
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nStampShape(JNIEnv* env, jclass,
                                                jlong handle,
                                                jstring paramsJson) {
    Session* s = asSession(handle);
    if (!s || !paramsJson) return JNI_FALSE;
    std::size_t layer = 0;
    if (!activePaintLayer(s, layer)) return JNI_FALSE;
    const std::string j = toStd(env, paramsJson);
    openclip::ShapeSpec spec;
    std::string kind = jsonStr(j, "kind");
    spec.kind = kind.empty() ? "rect" : kind;
    const float w = (float)cur(s).width(), h = (float)cur(s).height();
    auto clampX = [&](double v) {
        return (float)(v < 0 ? 0 : (v > w ? w : v));
    };
    auto clampY = [&](double v) {
        return (float)(v < 0 ? 0 : (v > h ? h : v));
    };
    spec.x0 = clampX(jsonNum(j, "x0", 0));
    spec.y0 = clampY(jsonNum(j, "y0", 0));
    spec.x1 = clampX(jsonNum(j, "x1", 1));
    spec.y1 = clampY(jsonNum(j, "y1", 1));
    spec.sides = static_cast<int>(jsonNum(j, "sides", 4));
    spec.rotation = static_cast<float>(jsonNum(j, "rotation", 0));
    spec.corner = static_cast<float>(jsonNum(j, "corner", 0));
    spec.fillOn = jsonBool(j, "fillOn", true);
    std::uint8_t fr = 0, fg = 0, fb = 0;
    if (parseColor(jsonStr(j, "fill"), fr, fg, fb)) {
        spec.fillR = fr;
        spec.fillG = fg;
        spec.fillB = fb;
    }
    spec.strokeOn = jsonBool(j, "strokeOn", false);
    spec.strokeW = static_cast<float>(jsonNum(j, "strokeW", 1));
    std::uint8_t sr = 0, sg = 0, sb = 0;
    if (parseColor(jsonStr(j, "stroke"), sr, sg, sb)) {
        spec.strokeR = sr;
        spec.strokeG = sg;
        spec.strokeB = sb;
    }
    int stride = 0;
    const std::uint8_t* mask = paintMask(s, stride);
    return paintUndoable(s, layer, [&](openclip::Surface& surf) {
        std::string err;
        return openclip::stampShape(surf, spec, mask, stride, err);
    }) ? JNI_TRUE : JNI_FALSE;
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
        resetFrameLocalState(s, true);
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
        resetFrameLocalState(s, true);
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
        int approx = 0;
        const bool ok = openclip::saveDocument(cur(s), p, fmt, error,
                                               &approx);
        if (ok && fmt == "psd") s->psdApprox = approx;
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
            int approx = 0;
            ok = openclip::savePsd(cur(s), p, error, &approx);
            if (ok) s->psdApprox = approx;
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
    if (!s) return JNI_FALSE;
    // V+2: a pending cel move applies when it is the most recent op —
    // i.e. the post-move frame matches the current frame AND that
    // frame's history depth is back to the recorded depth (exact LIFO
    // against interleaved pixel ops: a stroke after the move is undone
    // first, then the move).
    if (!s->celMoves.empty()) {
        const Session::CelMove& m = s->celMoves.back();
        if (m.frameAfter == s->cur &&
            hist(s).undoDepth() == m.histDepthAfter &&
            m.from < s->frames.size() && m.to < s->frames.size()) {
            Frame f = std::move(s->frames[m.from]);
            s->frames.erase(s->frames.begin() +
                            static_cast<std::ptrdiff_t>(m.from));
            s->frames.insert(s->frames.begin() +
                             static_cast<std::ptrdiff_t>(m.to),
                             std::move(f));
            s->cur = openclip::moveCursor(s->frames.size(), m.to,
                                          m.from, s->cur);
            s->celMoves.pop_back();
            s->selMask.clear();
            s->selMaskOn = false;
            return JNI_TRUE;
        }
    }
    return static_cast<jboolean>(hist(s).undo(cur(s)));
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

// T2 indexed vector ops (ARCHITECTURE.md §T2 §3): snapshot-first
// (1 undo step each), false + layer intact on bad input. id is the
// positional index in vectorData (the app re-reads nVectorPaths
// before every op — no id caching across gestures).
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nReplaceVectorPath(
    JNIEnv* env, jclass, jlong handle, jint index, jint id,
    jstring pathJson) {
    Session* s = asSession(handle);
    if (!s || !pathJson || index < 0 ||
        index >= static_cast<int>(cur(s).layerCount()) || id < 0)
        return JNI_FALSE;
    const std::string j = toStd(env, pathJson);
    if (j.empty()) return JNI_FALSE;
    std::string kind = jsonStr(j, "kind");
    kind = (kind == "eraser-stroke") ? "eraser-stroke" : "stroke";
    (void)kind;  // replace never converts ink<->eraser (engine keeps it)
    openclip::VectorHistory& h = vecs(s)[static_cast<std::size_t>(index)];
    std::string err;
    return openclip::replaceVectorPath(cur(s), h,
                                       static_cast<std::size_t>(index),
                                       static_cast<std::size_t>(id), j,
                                       1.0f + 3.0f * s->curve, err)
               ? JNI_TRUE
               : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nConnectVectorPaths(
    JNIEnv* env, jclass, jlong handle, jint index, jint a, jint b,
    jstring tolJson) {
    Session* s = asSession(handle);
    if (!s || index < 0 ||
        index >= static_cast<int>(cur(s).layerCount()) || a < 0 || b < 0)
        return JNI_FALSE;
    float tol = 12.0f;  // default seam tolerance (ARCHITECTURE.md §T2)
    const std::string j = tolJson ? toStd(env, tolJson) : std::string();
    if (!j.empty()) {
        // Bare number or {"tol":..} (M3 rule: garbage = default).
        char* endp = nullptr;
        const double bare =
            std::strtod(j.c_str(), &endp);
        if (endp != j.c_str() &&
            static_cast<std::size_t>(endp - j.c_str()) == j.size() &&
            bare > 0 && bare <= 512)
            tol = static_cast<float>(bare);
        else {
            const double t = jsonNum(j, "tol", 12.0);
            if (t > 0 && t <= 512) tol = static_cast<float>(t);
        }
    }
    openclip::VectorHistory& h = vecs(s)[static_cast<std::size_t>(index)];
    std::string err;
    return openclip::connectVectorPaths(cur(s), h,
                                        static_cast<std::size_t>(index),
                                        static_cast<std::size_t>(a),
                                        static_cast<std::size_t>(b), tol, err)
               ? JNI_TRUE
               : JNI_FALSE;
}

// Parse a doubles array: {"widths":[...]} or a bare [...] span.
bool parseWidthsJson(const std::string& j, std::vector<float>& out) {
    out.clear();
    std::size_t ob = j.find("\"widths\"");
    ob = (ob == std::string::npos) ? j.find('[')
                                   : j.find('[', ob + 8);
    if (ob == std::string::npos) return false;
    int dep = 0;
    std::size_t cb = std::string::npos;
    bool inS = false;
    for (std::size_t k = ob; k < j.size() && k - ob < 1048576; ++k) {
        const char c = j[k];
        if (c == '"' && (k == 0 || j[k - 1] != '\\')) inS = !inS;
        if (inS) continue;
        if (c == '[') ++dep;
        if (c == ']') {
            --dep;
            if (dep == 0) {
                cb = k;
                break;
            }
        }
    }
    if (cb == std::string::npos) return false;
    const std::string arr = j.substr(ob + 1, cb - ob - 1);
    std::size_t q = 0;
    while (q < arr.size() && out.size() < 65536) {
        while (q < arr.size() && (arr[q] < '0' || arr[q] > '9') &&
               arr[q] != '-' && arr[q] != '+' && arr[q] != '.')
            ++q;
        if (q >= arr.size()) break;
        char* e1 = nullptr;
        const float v = std::strtof(arr.c_str() + q, &e1);
        if (e1 == arr.c_str() + q) break;
        q = static_cast<std::size_t>(e1 - arr.c_str());
        out.push_back(v);
    }
    return !out.empty();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nSetVectorWidths(
    JNIEnv* env, jclass, jlong handle, jint index, jint id,
    jstring widthsJson) {
    Session* s = asSession(handle);
    if (!s || !widthsJson || index < 0 ||
        index >= static_cast<int>(cur(s).layerCount()) || id < 0)
        return JNI_FALSE;
    const std::string j = toStd(env, widthsJson);
    if (j.empty()) return JNI_FALSE;
    std::vector<float> w;
    if (!parseWidthsJson(j, w)) return JNI_FALSE;
    openclip::VectorHistory& h = vecs(s)[static_cast<std::size_t>(index)];
    std::string err;
    return openclip::setVectorWidths(cur(s), h,
                                     static_cast<std::size_t>(index),
                                     static_cast<std::size_t>(id), w, err)
               ? JNI_TRUE
               : JNI_FALSE;
}

// T2 bubble fiche (ARCHITECTURE.md §T2 §5, M8 pattern): null/empty
// clears the fiche (normal layer again); otherwise the parsed fiche
// is stored (opaque to the engine) and renders at composite time.
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nSetLayerBubble(
    JNIEnv* env, jclass, jlong handle, jint index, jstring json) {
    Session* s = asSession(handle);
    if (!s || index < 0 ||
        index >= static_cast<jint>(cur(s).layerCount()))
        return JNI_FALSE;
    openclip::Layer& l = cur(s).layer(static_cast<std::size_t>(index));
    const std::string j = json ? toStd(env, json) : std::string();
    if (j.empty()) {  // null/empty clears the descriptor
        l.bubbleShape.clear();
        l.bubbleFrame.clear();
        l.bubbleTail.clear();
        l.bubbleFill = "#FFFFFF";
        l.bubbleStroke = "#000000";
        l.bubbleStrokeW = 4.0f;
        l.bubbleTextLayer = -1;
        return JNI_TRUE;
    }
    const std::string shape = jsonStr(j, "shape");
    // frame: string "x,y,w,h" or [x,y,w,h] array (M3 tolerance).
    std::string frame = jsonStr(j, "frame");
    if (frame.empty()) {
        const std::size_t at = j.find("\"frame\"");
        if (at != std::string::npos) {
            const std::size_t ob = j.find('[', at + 7);
            if (ob != std::string::npos) {
                const std::size_t cb = j.find(']', ob);
                if (cb != std::string::npos) {
                    std::string nums;
                    for (std::size_t k = ob + 1; k < cb; ++k)
                        if ((j[k] >= '0' && j[k] <= '9') || j[k] == '-' ||
                            j[k] == '+' || j[k] == '.' || j[k] == ',')
                            nums += j[k];
                    frame = nums;
                }
            }
        }
    }
    std::string tail = jsonStr(j, "tail");
    if (tail.empty()) {
        const std::size_t at = j.find("\"tail\"");
        if (at != std::string::npos) {
            const std::size_t ob = j.find('[', at + 6);
            if (ob != std::string::npos) {
                const std::size_t cb = j.find(']', ob);
                if (cb != std::string::npos) {
                    std::string nums;
                    for (std::size_t k = ob + 1; k < cb; ++k)
                        if ((j[k] >= '0' && j[k] <= '9') || j[k] == '-' ||
                            j[k] == '+' || j[k] == '.' || j[k] == ',')
                            nums += j[k];
                    tail = nums;
                }
            }
        }
    }
    std::string fill = jsonStr(j, "fill");
    if (fill.empty()) fill = "#FFFFFF";
    std::string stroke = jsonStr(j, "stroke");
    if (stroke.empty()) stroke = "#000000";
    double sw = jsonNum(j, "strokeW", 4.0);
    if (!(sw >= 1.0 && sw <= 32.0)) sw = 4.0;
    const double tl = jsonNum(j, "textLayer", -1.0);
    openclip::BubbleSpec spec;
    std::string err;
    if (!openclip::parseBubbleSpec(shape, frame, tail, fill, stroke,
                                   static_cast<float>(sw), spec, err))
        return JNI_FALSE;
    l.bubbleShape = spec.shape;
    l.bubbleFrame = frame;
    l.bubbleTail = tail;
    l.bubbleFill = fill.substr(0, 128);
    l.bubbleStroke = stroke.substr(0, 128);
    l.bubbleStrokeW = static_cast<float>(sw);
    l.bubbleTextLayer =
        (tl >= -1.0 && tl <= 1024.0) ? static_cast<int>(tl) : -1;
    return JNI_TRUE;
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
    s->guides.clear();
    s->snapTol = 2.0f;
    s->snapGridStep = 0.0f;
    s->snapPaths = false;
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
        // M9: up to THREE points (6 numbers) — a 2-segment grid; M6
        // kept the first segment only.
        const std::size_t open = j.find('[', per + 12);
        if (open != std::string::npos) {
            std::vector<float> vals;
            std::size_t p = open + 1;
            int depth = 0;
            while (p < j.size() && vals.size() < 6) {
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
            if (vals.size() >= 6)
                s->lines.emplace_back(
                    openclip::Vec2{vals[2], vals[3]},
                    openclip::Vec2{vals[4], vals[5]});
        }
    }
    // V+2 guides (ARCHITECTURE.md "V+2 additions"): "guides":[{x} |
    // {y} | {cx,cy,r}] — vertical / horizontal / concentric. Parsed
    // object by object (unknown shapes ignored, the M3 rule).
    const std::size_t gd = j.find("\"guides\"");
    if (gd != std::string::npos) {
        const std::size_t arr = j.find('[', gd + 8);
        if (arr != std::string::npos) {
            std::size_t p = arr + 1;
            for (int kept = 0; kept < 256; ++kept) {
                const std::size_t ob = j.find('{', p);
                if (ob == std::string::npos) break;
                const std::size_t cb = j.find('}', ob + 1);
                if (cb == std::string::npos) break;
                const std::string o = j.substr(ob, cb - ob + 1);
                auto numIn = [&](const char* key, double fb, bool& has) {
                    const std::string pat =
                        std::string("\"") + key + "\"";
                    const std::size_t at = o.find(pat);
                    has = false;
                    if (at == std::string::npos) return fb;
                    const std::size_t colon = o.find(':', at + pat.size());
                    if (colon == std::string::npos) return fb;
                    char* e = nullptr;
                    const double v =
                        std::strtod(o.c_str() + colon + 1, &e);
                    if (e == o.c_str() + colon + 1) return fb;
                    has = true;
                    return v;
                };
                bool hx = false, hy = false, hcx = false, hcy = false,
                     hr = false;
                const double x = numIn("x", 0, hx);
                const double y = numIn("y", 0, hy);
                const double cx = numIn("cx", 0, hcx);
                const double cy = numIn("cy", 0, hcy);
                const double r = numIn("r", 0, hr);
                openclip::Guide g;
                if (hx && !hy && !hcx && !hcy) {
                    g.kind = openclip::Guide::Kind::Vertical;
                    g.x = static_cast<float>(x);
                    s->guides.push_back(g);
                } else if (hy && !hx && !hcx && !hcy) {
                    g.kind = openclip::Guide::Kind::Horizontal;
                    g.y = static_cast<float>(y);
                    s->guides.push_back(g);
                } else if (hcx && hcy && hr && r > 0) {
                    g.kind = openclip::Guide::Kind::Circle;
                    g.cx = static_cast<float>(cx);
                    g.cy = static_cast<float>(cy);
                    g.r = static_cast<float>(r);
                    s->guides.push_back(g);
                }
                p = cb + 1;
                const std::size_t arrEnd = j.find(']', p);
                const std::size_t nxt = j.find('{', p);
                if (arrEnd != std::string::npos &&
                    (nxt == std::string::npos || arrEnd < nxt))
                    break;
            }
        }
    }
    // V+3 snap keys (ARCHITECTURE.md "V+3 additions"): "snapTol" px
    // (default 2 = V+2; <= 0 disables), "grid" 0|8|16|32 (other values
    // ignored), "snapPaths" bool. Unknown keys ignored (M3 rule).
    const double tol = jsonNum(j, "snapTol", 2.0);
    s->snapTol =
        static_cast<float>(tol < 0.0 ? 0.0 : (tol > 64.0 ? 64.0 : tol));
    const double grid = jsonNum(j, "grid", 0.0);
    s->snapGridStep = (grid == 8.0 || grid == 16.0 || grid == 32.0)
                          ? static_cast<float>(grid)
                          : 0.0f;
    {
        const std::size_t sp = j.find("\"snapPaths\"");
        if (sp != std::string::npos) {
            const std::size_t colon = j.find(':', sp + 11);
            if (colon != std::string::npos) {
                std::size_t q = colon + 1;
                while (q < j.size() &&
                       (j[q] == ' ' || j[q] == '\t' || j[q] == '\n'))
                    ++q;
                s->snapPaths = (q < j.size() && j[q] == 't');
            }
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
    resetFrameLocalState(s, true);
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
    resetFrameLocalState(s, true);
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
    resetFrameLocalState(s, true);
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
    resetFrameLocalState(s, false);
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
    // Backward compatible: booleans map to counts 0/1 (V+2 counts win
    // when nSetOnionCount is used instead).
    s->onionBefore = (previous == JNI_TRUE) ? 1 : 0;
    s->onionAfter = (next == JNI_TRUE) ? 1 : 0;
    s->onionStrength = strength < 0.0f ? 0.0f : (strength > 1.0f ? 1.0f
                                                                 : strength);
    return JNI_TRUE;
}

// ---- V+2 additions (symbols per ARCHITECTURE.md "V+2 additions") ----

// Cel reorder: erase+insert, `cur` follows the moved cel; one undo
// entry (pending-move stack, see nUndo). Redo of a move is not
// offered (honest limit, documented in ARCHITECTURE.md).
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nMoveCel(JNIEnv*, jclass, jlong handle,
                                             jint from, jint to) {
    Session* s = asSession(handle);
    if (!s || s->frames.empty()) return JNI_FALSE;
    if (from < 0 || to < 0 ||
        from >= static_cast<jint>(s->frames.size()) ||
        to >= static_cast<jint>(s->frames.size()))
        return JNI_FALSE;
    if (from == to) return JNI_TRUE;  // no-op, still success
    Frame f = std::move(s->frames[static_cast<std::size_t>(from)]);
    s->frames.erase(s->frames.begin() + static_cast<std::ptrdiff_t>(from));
    s->frames.insert(s->frames.begin() + static_cast<std::ptrdiff_t>(to),
                     std::move(f));
    s->cur =
        openclip::moveCursor(s->frames.size(), static_cast<std::size_t>(from),
                             static_cast<std::size_t>(to), s->cur);
    // Record AFTER the move: the post-move frame + its history depth.
    Session::CelMove m;
    m.frameAfter = s->cur;
    m.histDepthAfter = hist(s).undoDepth();
    m.from = static_cast<std::size_t>(from);
    m.to = static_cast<std::size_t>(to);
    s->celMoves.push_back(m);
    s->selMask.clear();  // pixel-bound, like the mask target
    s->selMaskOn = false;
    return JNI_TRUE;
}

// Per-cel exposure multiplier 1..32 (meta, never in undo).
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nSetCelHold(JNIEnv*, jclass,
                                                jlong handle, jint index,
                                                jint frames) {
    Session* s = asSession(handle);
    if (!s || s->frames.empty()) return JNI_FALSE;
    if (index < 0 ||
        index >= static_cast<jint>(s->frames.size()))
        return JNI_FALSE;
    if (!openclip::validCelHold(frames)) return JNI_FALSE;
    s->frames[static_cast<std::size_t>(index)].hold = frames;
    return JNI_TRUE;
}

// Real onion neighbor counts 0..4 per side (rendering only).
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nSetOnionCount(JNIEnv*, jclass,
                                                   jlong handle,
                                                   jint before,
                                                   jint after) {
    Session* s = asSession(handle);
    if (!s) return JNI_FALSE;
    if (!openclip::validOnionCount(before) ||
        !openclip::validOnionCount(after))
        return JNI_FALSE;
    s->onionBefore = before;
    s->onionAfter = after;
    return JNI_TRUE;
}

// Engine-side selection mask upload: PNG file, LUMA drives selection
// (white = selected). Exact canvas-size match required; null/empty
// clears. False + previous mask kept otherwise.
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nSetSelectionMask(JNIEnv* env, jclass,
                                                      jlong handle,
                                                      jstring path) {
    Session* s = asSession(handle);
    if (!s) return JNI_FALSE;
    const std::string p = path ? toStd(env, path) : std::string();
    if (p.empty()) {
        s->selMask.clear();
        s->selMaskOn = false;
        return JNI_TRUE;
    }
    openclip::Surface decoded;
    std::string error;
    if (!openclip::decodePngFile(p, decoded, error)) return JNI_FALSE;
    if (decoded.width() != cur(s).width() ||
        decoded.height() != cur(s).height())
        return JNI_FALSE;  // previous mask kept
    const std::vector<std::uint8_t>& px = decoded.bytes();
    std::vector<std::uint8_t> mask(
        static_cast<std::size_t>(decoded.width()) * decoded.height());
    for (std::size_t i = 0; i < mask.size(); ++i)
        mask[i] = static_cast<std::uint8_t>(
            (px[i * 4 + 0] + px[i * 4 + 1] + px[i * 4 + 2] + 1) / 3);
    s->selMask.swap(mask);
    s->selMaskOn = true;
    return JNI_TRUE;
}

// Selection transform (ARCHITECTURE.md "V+2 additions"): moves/scales/
// rotates/warps the masked content of the ACTIVE raster layer.
// Undoable via full-layer tile snapshots (filter pattern). False +
// layer untouched when no mask, bad layer, or malformed JSON.
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nSelectionBlit(JNIEnv* env, jclass,
                                                   jlong handle,
                                                   jstring json) {
    Session* s = asSession(handle);
    if (!s || !json) return JNI_FALSE;
    if (!s->selMaskOn || s->selMask.empty()) return JNI_FALSE;
    const int active = cur(s).activeLayer();
    if (active < 0 ||
        active >= static_cast<int>(cur(s).layerCount()))
        return JNI_FALSE;
    openclip::Layer& ly =
        cur(s).layer(static_cast<std::size_t>(active));
    if (ly.isVector) return JNI_FALSE;
    const std::string j = toStd(env, json);
    openclip::TransformSpec spec;
    spec.dx = jsonNum(j, "dx", 0.0);
    spec.dy = jsonNum(j, "dy", 0.0);
    spec.scale = jsonNum(j, "scale", 1.0);
    spec.angleDeg = jsonNum(j, "angle", 0.0);
    spec.mode = jsonStr(j, "mode");
    if (spec.mode.empty()) spec.mode = "move";
    // Mesh mode: flat 18-number array after the "mesh" key.
    const std::size_t mk = j.find("\"mesh\"");
    if (mk != std::string::npos) {
        const std::size_t open = j.find('[', mk + 6);
        if (open == std::string::npos) return JNI_FALSE;
        std::vector<double> vals;
        std::size_t p = open + 1;
        while (p < j.size() && vals.size() < 18) {
            const char c = j[p];
            if (c == ']') break;
            if ((c >= '0' && c <= '9') || c == '-' || c == '.') {
                char* e = nullptr;
                const double v = std::strtod(j.c_str() + p, &e);
                if (e != j.c_str() + p) {
                    vals.push_back(v);
                    p = static_cast<std::size_t>(e - j.c_str());
                    continue;
                }
            }
            ++p;
        }
        if (vals.size() != 18) return JNI_FALSE;
        for (int k = 0; k < 9; ++k) {
            spec.mesh[k][0] = vals[k * 2];
            spec.mesh[k][1] = vals[k * 2 + 1];
        }
    }
    openclip::History& h = hist(s);
    h.beginStroke();
    for (int ty = 0; ty < ly.surface.tilesY(); ++ty)
        for (int tx = 0; tx < ly.surface.tilesX(); ++tx)
            h.touchTile(cur(s), static_cast<std::size_t>(active), tx, ty);
    openclip::Surface out;
    if (!openclip::blitSelection(out, ly.surface, s->selMask, spec)) {
        h.cancelStroke(cur(s));
        return JNI_FALSE;
    }
    ly.surface.bytes().swap(out.bytes());
    h.endStroke(cur(s));
    return JNI_TRUE;
}

// Box-downscale straight RGBA (aspect kept) for the GIF exporter.
namespace {
void downscaleBox(const std::vector<std::uint8_t>& src, int sw, int sh,
                  int dw, int dh, std::vector<std::uint8_t>& dst) {
    dst.assign(static_cast<std::size_t>(dw) * dh * 4, 0);
    for (int y = 0; y < dh; ++y) {
        const int y0 = y * sh / dh, y1 = (y + 1) * sh / dh;
        for (int x = 0; x < dw; ++x) {
            const int x0 = x * sw / dw, x1 = (x + 1) * sw / dw;
            long acc[4] = {0, 0, 0, 0};
            long n = 0;
            for (int sy = y0; sy < y1; ++sy)
                for (int sx = x0; sx < x1; ++sx) {
                    const std::size_t i =
                        (static_cast<std::size_t>(sy) * sw + sx) * 4;
                    for (int k = 0; k < 4; ++k) acc[k] += src[i + k];
                    ++n;
                }
            if (n == 0) n = 1;
            const std::size_t o =
                (static_cast<std::size_t>(y) * dw + x) * 4;
            for (int k = 0; k < 4; ++k)
                dst[o + k] =
                    static_cast<std::uint8_t>((acc[k] + n / 2) / n);
        }
    }
}
}  // namespace

// Animated GIF export (ARCHITECTURE.md "V+2 additions"): composites
// every frame internally, downscales to maxWidth, quantizes + LZW from
// scratch. GCE delay = cel hold x fps (optional "fps", default 12).
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nExportGif(JNIEnv* env, jclass,
                                               jlong handle, jstring path,
                                               jstring json) {
    Session* s = asSession(handle);
    if (!s || !path || s->frames.empty()) return JNI_FALSE;
    const std::string p = toStd(env, path);
    if (p.empty()) return JNI_FALSE;
    const std::string j = json ? toStd(env, json) : std::string();
    const int loop = static_cast<int>(jsonNum(j, "loop", 0));
    int maxW = static_cast<int>(jsonNum(j, "maxWidth", 1024));
    if (maxW < 1) maxW = 1;
    if (maxW > 4096) maxW = 4096;
    const double fps = jsonNum(j, "fps", 12.0);
    bool dither = true;
    if (j.find("\"dither\"") != std::string::npos &&
        j.find("\"dither\"") != std::string::npos) {
        const std::size_t at = j.find("\"dither\"");
        const std::size_t colon = j.find(':', at + 8);
        if (colon != std::string::npos) {
            const std::string tail = j.substr(colon + 1, 8);
            if (tail.find("false") != std::string::npos) dither = false;
        }
    }
    if (loop != 0 && loop != -1 && loop < 2) return JNI_FALSE;
    std::vector<openclip::GifFrame> frames;
    for (auto& f : s->frames) {
        openclip::Surface comp;
        f.canvas.composite(comp);
        const int sw = comp.width(), sh = comp.height();
        if (sw <= 0 || sh <= 0) return JNI_FALSE;
        int dw = sw, dh = sh;
        if (sw > maxW) {
            dw = maxW;
            dh = std::max(1, sh * maxW / sw);
        }
        // Premultiplied engine bytes -> straight RGBA for the writer.
        const auto& px = comp.bytes();
        std::vector<std::uint8_t> straight(
            static_cast<std::size_t>(sw) * sh * 4);
        for (std::size_t i = 0; i < static_cast<std::size_t>(sw) * sh;
             ++i) {
            const std::uint8_t a = px[i * 4 + 3];
            straight[i * 4 + 3] = a;
            if (a == 0) {
                straight[i * 4 + 0] = straight[i * 4 + 1] =
                    straight[i * 4 + 2] = 0;
            } else if (a == 255) {
                straight[i * 4 + 0] = px[i * 4 + 0];
                straight[i * 4 + 1] = px[i * 4 + 1];
                straight[i * 4 + 2] = px[i * 4 + 2];
            } else {
                for (int k = 0; k < 3; ++k) {
                    int v = (px[i * 4 + k] * 255 + a / 2) / a;
                    straight[i * 4 + k] =
                        static_cast<std::uint8_t>(v > 255 ? 255 : v);
                }
            }
        }
        openclip::GifFrame g;
        g.delayCs = openclip::celDelayCs(f.hold, fps);
        if (dw != sw) {
            downscaleBox(straight, sw, sh, dw, dh, g.rgba);
            g.w = dw;
            g.h = dh;
        } else {
            g.rgba.swap(straight);
            g.w = sw;
            g.h = sh;
        }
        frames.push_back(std::move(g));
    }
    std::string error;
    if (!openclip::writeGif(p, frames, loop, dither, error)) return JNI_FALSE;
    return JNI_TRUE;
}

// Generic opaque layer fiche (ARCHITECTURE.md "V+2 additions", decided
// here per spec §1.4): key must be x-openclip-[a-z0-9-]*, value <=
// 4 KiB; null/empty clears. Persisted verbatim as ORA <layer> attrs.
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openclip_engine_EngineHost_nSetLayerMeta(JNIEnv* env, jclass,
                                                  jlong handle, jint index,
                                                  jstring key,
                                                  jstring value) {
    Session* s = asSession(handle);
    if (!s || !key) return JNI_FALSE;
    if (index < 0 ||
        index >= static_cast<jint>(cur(s).layerCount()))
        return JNI_FALSE;
    const std::string k = toStd(env, key);
    static const std::string kPrefix = "x-openclip-";
    if (k.size() <= kPrefix.size() ||
        k.compare(0, kPrefix.size(), kPrefix) != 0)
        return JNI_FALSE;
    for (std::size_t i = kPrefix.size(); i < k.size(); ++i) {
        const char c = k[i];
        if (!(c == '-' || (c >= 'a' && c <= 'z') ||
              (c >= '0' && c <= '9')))
            return JNI_FALSE;
    }
    const std::string v = value ? toStd(env, value) : std::string();
    if (v.size() > 4096) return JNI_FALSE;
    openclip::Layer& l = cur(s).layer(static_cast<std::size_t>(index));
    if (v.empty()) {
        l.meta.erase(k);
        return JNI_TRUE;
    }
    l.meta[k] = v;
    return JNI_TRUE;
}

// Approximation count from the last layered PSD save (0 = faithful).
extern "C" JNIEXPORT jint JNICALL
Java_dev_openclip_engine_EngineHost_nPsdApproxCount(JNIEnv*, jclass,
                                                    jlong handle) {
    Session* s = asSession(handle);
    return s ? static_cast<jint>(s->psdApprox) : 0;
}

// V+2 vector introspection (ARCHITECTURE.md "V+2 getter"): read-only
// JSON of one vector layer's paths for SVG export and Bézier editing.
// Empty object on bad index or non-vector layer. No pixel path.
extern "C" JNIEXPORT jstring JNICALL
Java_dev_openclip_engine_EngineHost_nVectorPaths(JNIEnv* env, jclass,
                                                 jlong handle, jint index) {
    Session* s = asSession(handle);
    if (!s || index < 0 ||
        index >= static_cast<jint>(cur(s).layerCount()))
        return env->NewStringUTF("{}");
    const openclip::Layer& l =
        cur(s).layer(static_cast<std::size_t>(index));
    if (!l.isVector) return env->NewStringUTF("{}");
    std::string out = "{\"paths\":[";
    for (std::size_t k = 0; k < l.vectorData.size(); ++k) {
        if (k > 0) out += ",";
        const openclip::VectorPath& vp = l.vectorData[k];
        out += "{\"pts\":[";
        for (std::size_t i = 0; i < vp.pts.size(); ++i) {
            if (i > 0) out += ",";
            char buf[64];
            std::snprintf(buf, sizeof buf, "[%.2f,%.2f,1]",
                          vp.pts[i].x, vp.pts[i].y);
            out += buf;
        }
        out += "],\"widths\":[";
        for (std::size_t i = 0; i < vp.widthProfile.size(); ++i) {
            if (i > 0) out += ",";
            char buf[32];
            std::snprintf(buf, sizeof buf, "%.2f", vp.widthProfile[i]);
            out += buf;
        }
        // T2 style (ARCHITECTURE.md §T2 §4): read-only, no new symbol.
        out += "],\"kind\":\"";
        out += vp.kind;
        out += "\",\"tip\":\"";
        out += vp.tip;
        out += "\",\"size\":";
        {
            char sbuf[32];
            std::snprintf(sbuf, sizeof sbuf, "%g", vp.sizeMul);
            out += sbuf;
        }
        out += ",\"color\":\"#";
        {
            char hbuf[8];
            std::snprintf(hbuf, sizeof hbuf, "%02X%02X%02X", vp.r, vp.g,
                          vp.b);
            out += hbuf;
        }
        out += "\",\"corners\":[";
        for (std::size_t i = 0; i < vp.corners.size(); ++i) {
            if (i > 0) out += ",";
            out += vp.corners[i] ? "1" : "0";
        }
        out += "]}";
    }
    out += "]}";
    return env->NewStringUTF(out.c_str());
}
