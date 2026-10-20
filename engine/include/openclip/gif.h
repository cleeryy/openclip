// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip V+2 — animated GIF89a writer from scratch (clean-room).
// Median-cut 256 quantize + LZW encoder + NETSCAPE2.0 loop extension.
// No third-party code (GIF patents expired 2003-2004). Delays come
// from the caller (cel holds x fps, ARCHITECTURE.md "V+2 additions").
#ifndef OPENCLIP_GIF_H
#define OPENCLIP_GIF_H

#include <cstdint>
#include <string>
#include <vector>

namespace openclip {

struct GifFrame {
    int w = 0, h = 0;
    // Straight (non-premultiplied) RGBA bytes, row-major, w*h*4.
    std::vector<std::uint8_t> rgba;
    int delayCs = 8;  // Graphic Control Extension delay, 1/100 s
};

// Loop policy: 0 = play once (no NETSCAPE extension), -1 = infinite
// (extension count 0), N > 1 = N total plays.
inline constexpr int kGifLoopOnce = 0;
inline constexpr int kGifLoopInfinite = -1;

// Reduce straight RGBA bytes to a <=256-entry palette (median-cut on
// RGB, alpha thresholded at 128 -> transparent index when any pixel
// is transparent). `dither` applies ordered Bayer-8 diffusion against
// the palette. Output: palette (RGB triples) + per-pixel indexes.
void gifQuantize(const std::vector<std::uint8_t>& rgba, int w, int h,
                 bool dither, std::vector<std::uint8_t>& palette,
                 std::vector<std::uint8_t>& indexes, bool& hasTransparent);

// LZW-compress index bytes for `minCodeSize` (GIF variant, clear/eoi
// codes, sub-blocked output WITHOUT the terminator — the caller adds
// block framing). Pure function, unit-tested via round trip in tests.
std::vector<std::uint8_t> gifLzwEncode(const std::vector<std::uint8_t>& idx,
                                       int minCodeSize);

// Write frames to `path` (all frames MUST share dimensions; frames
// with delayCs < 0 are clamped to 0). Global 256-color table (actual
// size = first pow2 >= palette, min 2). Returns false + `error` on
// empty input, dimension mismatch, or IO failure (no partial file
// left behind on encode errors before open; a failed open leaves no
// file).
bool writeGif(const std::string& path, const std::vector<GifFrame>& frames,
              int loop, bool dither, std::string& error);

}  // namespace openclip

#endif
