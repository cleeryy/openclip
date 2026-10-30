// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip B1 engine — retouch and ink kernels: clone stamp, smudge,
// dodge/burn and the pattern walker (ARCHITECTURE.md "B1 additions").
// Pure functions over Surface, no Canvas/JNI dependency; the shim keeps
// undo, selection and guards.
#ifndef OPENCLIP_RETOUCH_H
#define OPENCLIP_RETOUCH_H

#include <cstdint>
#include <string>
#include <vector>

#include "openclip/brush.h"

namespace openclip {

class Surface;

// ---- Clone stamp (§3) ----
//
// One anchor per session: the app sets it on a long press, each stroke
// begin derives the sampling offset. Non-aligned re-anchors on EVERY
// stroke begin; aligned keeps the previous offset so sampling continues
// where the last stroke stopped (the classic two behaviours).
struct CloneAnchor {
    bool have = false;    // a source point was picked
    float x = 0, y = 0;   // anchor (source) point
    bool aligned = false;
    bool offValid = false;
    float offX = 0, offY = 0;  // sample = dab + (offX, offY)
};

// Long press: records the anchor and invalidates the running offset.
void cloneSetSource(CloneAnchor& a, float x, float y, bool aligned);
// Stroke begin: (re)computes the offset. aligned + previous offset
// keeps it; otherwise offset = anchor - (x,y).
void cloneStrokeBegin(CloneAnchor& a, float x, float y);
bool cloneReady(const CloneAnchor& a);

// One clone stamp: sample src at (d.x+offX, d.y+offY) and source-over it
// into dst through the dab falloff. Nearest-neighbour copy (a clone is
// pixel-exact), sampled alpha preserved; out-of-bounds samples are
// transparent (no ink). src may alias dst (self-clone).
void cloneDab(Surface& dst, const Surface& src, const Dab& d,
              const BrushParams& p, float offX, float offY);

// ---- Smudge (§2) ----
//
// Carried premultiplied paint state; the stroke owns one instance.
struct SmudgeState {
    bool primed = false;
    float r = 0, g = 0, b = 0, a = 0;  // straight colour + carried alpha
};

// Pick up the falloff-weighted local colour, blend it into `state`
// (k = 0.6 - 0.3*strength: a strong smudge keeps 70% carried paint) and
// deposit the carried
// colour at the dab with mask = falloff*opacity*strength. Consequence:
// paint moves in the stroke direction (next dab deposits what the
// previous one picked up).
void smudgeDab(Surface& s, const Dab& d, const BrushParams& p,
               SmudgeState& state, float strength);

// ---- Dodge / burn (§2) ----
//
// Existing pixels only (dst.a == 0 skipped): unpremultiply, lighten
// (dodge: c += (255-c)*m) or darken (burn: c -= c*m), repremultiply
// clamped to alpha. Alpha is never touched (M3 contract).
void dodgeBurnDab(Surface& s, const Dab& d, const BrushParams& p, bool burn);

// ---- Pattern brush (§4) ----
struct PatternSpec {
    float spacing = 24.0f;     // px between stamps (>= 1)
    float scale = 1.0f;        // stamp size factor over the tile size
    float rotationDeg = 0.0f;  // extra rotation, clockwise
    float jitterPos = 0.0f;    // px, uniform in [-j, +j] per axis
    float jitterScale = 0.0f;  // fraction, uniform in [-j, +j]
    float jitterRot = 0.0f;    // degrees, uniform in [-j, +j]
    std::uint32_t seed = 1;    // deterministic jitter stream
    bool alongPath = true;     // rotate each stamp by the path tangent
};

struct PatternStamp {
    float x = 0, y = 0;
    float angleDeg = 0;  // clockwise, screen coords (y down)
    float scale = 1;
};

// Pure spacing walker: begin() emits the first stamp, to() accumulates
// the travelled distance and emits stamps every `spacing` px
// (deterministic jitter from `seed`; carry across segments so the
// spacing never restarts at a polyline joint).
class PatternWalker {
public:
    explicit PatternWalker(PatternSpec spec);
    void begin(float x, float y, float pressure);
    void to(float x, float y, float pressure);
    // Stamps emitted since the last take().
    std::vector<PatternStamp> take();
    std::size_t count() const { return count_; }
    const PatternSpec& spec() const { return spec_; }

private:
    void emit(float x, float y, float pressure, float dirDeg);
    float rand01();  // xorshift32 -> [0,1)

    PatternSpec spec_;
    float px_ = 0, py_ = 0, pp_ = 1;
    float carry_ = 0;
    float dir_ = 0;
    bool open_ = false;
    std::uint32_t rng_ = 1;
    std::vector<PatternStamp> pending_;
    std::size_t count_ = 0;
};

// Clean-room procedural motif tile (straight over premultiplied surface):
// "dots" filled disc, "ring" annulus, "star" five-point star, "cross"
// plus sign. px clamped 8..128. false + error on unknown kind/bad size.
bool proceduralMotif(const std::string& kind, int px, std::uint8_t r,
                     std::uint8_t g, std::uint8_t b, Surface& out,
                     std::string& error);

// Stamp the tile once at st.x/st.y, rotated clockwise by st.angleDeg and
// scaled by st.scale about the tile centre (nearest-neighbour sampling:
// crisp motifs). Source-over with the stamp opacity; alpha-safe.
void stampPattern(Surface& dst, const Surface& tile, const PatternStamp& st,
                  float opacity, bool alphaLock = false);

// Inclusive pixel bounds a stamp covers (shim tile snapshots).
void patternStampBounds(const PatternStamp& st, int tileW, int tileH, int& x0,
                        int& y0, int& x1, int& y1);

}  // namespace openclip

#endif
