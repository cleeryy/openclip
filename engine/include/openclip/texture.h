// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip B1 engine — non-destructive layer texture effect
// (ARCHITECTURE.md "B1 additions" §5). Pure kernel like fill_layer.h:
// the shim stores the fiche on the Layer (textureKind/textureParams),
// parseTextureSpec validates it, renderTextureHeight produces the
// deterministic height field and compositeInto modulates each pixel's
// straight colour through it (alpha untouched, rgb <= alpha).
#ifndef OPENCLIP_TEXTURE_H
#define OPENCLIP_TEXTURE_H

#include <cstdint>
#include <string>
#include <vector>

namespace openclip {

class Surface;
struct Layer;

struct TextureSpec {
    std::string kind = "paper";  // "paper" | "canvas" | "grain"
    float scale = 1.0f;          // 0.25..8 (feature size factor)
    float strength = 0.35f;      // 0..1 (modulation amplitude)
    std::uint32_t seed = 7u;     // deterministic noise seed
};

// Parse a JSON descriptor {kind,scale,strength,seed} (M3 rule: unknown
// keys ignored, missing keys keep defaults). Unparsable JSON or an
// unknown kind = false + error (the caller keeps its previous state).
bool parseTextureSpec(const std::string& json, TextureSpec& out,
                      std::string& error);

// Parse the Layer fiche fields (kind + params span
// "scale|strength|seed"). Unparsable numbers fall back to defaults
// (same spirit as the T1 fill params rule).
bool parseTextureFiche(const Layer& l, TextureSpec& out, std::string& error);

// Deterministic height field, one byte per pixel (0 = low, 255 = high),
// out resized to w*h. Same spec + same size = byte-identical output.
// paper: multi-octave value noise plus fibre streaks; canvas: woven
// sinus grid with a slow warp; grain: high-frequency noise.
bool renderTextureHeight(const TextureSpec& spec, int w, int h,
                         std::vector<std::uint8_t>& out,
                         std::string& error);

// Modulate the surface in place: for each covered pixel,
// straight c' = clamp(c * (1 + strength*(0.5 - h)*2)), repremultiplied
// and clamped to alpha. Fully transparent pixels stay byte-identical;
// alpha never changes (M3 contract). strength <= 0 = no-op.
void applyTexture(Surface& s, const TextureSpec& spec);

// Same modulation for one pixel value (composite hot path, shared by
// applyTexture so both paths stay identical).
std::uint8_t textureShade(std::uint8_t channel, std::uint8_t alpha,
                          std::uint8_t height, float strength);

}  // namespace openclip

#endif
