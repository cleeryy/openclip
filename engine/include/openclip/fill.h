// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip T1 engine — scanline flood-fill kernel shared by the bucket
// (§1) and the magic wand (§4): ONE implementation, two envelopes.
// Clean-room, from scratch (iterative span queue, never recursive;
// visited = W*H bits, OOM = false + untouched).
#ifndef OPENCLIP_FILL_H
#define OPENCLIP_FILL_H

#include <cstdint>
#include <string>
#include <vector>

namespace openclip {

class Surface;

// Bucket options (ARCHITECTURE.md "T1 additions", nFloodFill JSON):
// tolerance 0..1 (0 = exact seed match, 1 = everything), antialias =
// 1px boundary ramp (partial alpha, premultiplied-safe), expandPx 0..2
// = outward dilation of the painted region (anti-halo under strokes).
struct FillParams {
    float tolerance = 0.12f;
    bool antialias = true;
    int expandPx = 0;
};

// Fill the 4-connected region containing (x,y) on s with ink (straight
// rgb). seed = color of (x,y) (sampleMerged only changes WHO the shim
// passes as seed: the kernel compares against `seed` itself, never a
// composite). mask (nullptr = no selection) constrains the paint:
// outside the mask is byte-identical. Region metric: |lum(px) -
// lum(seed)| <= tol*255 (Rec.601 luma, same weights as gradientMap).
// Antialias ramps the 1px frontier (half coverage on member pixels
// touching the region boundary, premultiplied-safe: rgb <= alpha). Paint is source-over
// with the straight ink color; alpha grows BY DESIGN here (paint, not
// a filter — the M3 alpha contract is for filters/tones only).
// Sample-merged variant: the seed luma comes from the shim-side
// composite pull (one pull, never a pull loop) instead of s at the
// seed point — the region walk still runs on s. Same contract
// otherwise (seed point only bounds-checks, still required in-range).
bool floodFillSeeded(Surface& s, int x, int y, double seedLum,
                     std::uint8_t r, std::uint8_t g, std::uint8_t b,
                     const FillParams& p, const std::uint8_t* mask,
                     int maskStride, std::string& error);
// Returns false + surface byte-identical when the seed is outside the
// surface, the surface is empty, or params are invalid
// (tolerance outside [0,1], expandPx outside [0,2]).
bool floodFill(Surface& s, int x, int y, std::uint8_t r, std::uint8_t g,
               std::uint8_t b, const FillParams& p,
               const std::uint8_t* mask, int maskStride, std::string& error);

// Magic wand (§4): the SAME kernel in mask-output mode. Region metric
// identical to floodFill (4-connected, Rec.601 luma + tolerance).
// Writes 0/255 into maskOut (full resolution, maskStride bytes per
// row; combine 0=set/replace, 1=add/OR, 2=subtract/AND-NOT against the
// incoming maskOut content). src is never modified. Returns false +
// maskOut byte-identical when the seed is outside src, src is empty,
// tolerance is outside [0,1], or combine is outside [0,2].
bool magicWand(const Surface& src, int x, int y, float tolerance,
               int combine, std::uint8_t* maskOut, int maskStride,
               std::string& error);

}  // namespace openclip

#endif
