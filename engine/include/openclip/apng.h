// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip T3 — animated PNG writer + PNG sequence writer from
// scratch (clean-room, ARCHITECTURE.md "T3 additions"). APNG chunks
// (signature, IHDR RGBA8, acTL, fcTL per frame, IDAT first frame,
// fdAT rest, IEND); zlib via system -lz (same as minizip), CRC32 via
// zlib. Sequence = one PNG file per playback tick (holds éclatés,
// honest with the V+2 rhythm).
#ifndef OPENCLIP_APNG_H
#define OPENCLIP_APNG_H

#include <cstdint>
#include <string>
#include <vector>

namespace openclip {

class Surface;

struct ApngFrame {
    int w = 0, h = 0;
    // Straight (non-premultiplied) RGBA bytes, row-major, w*h*4.
    std::vector<std::uint8_t> rgba;
    int delayCs = 8;  // fcTL delay numerator over 100 (1/100 s)
};

// Loop policy mirrors GIF: 0 = play once (acTL num_plays 1),
// -1 = infinite (num_plays 0), N > 1 = N plays. Delays come from the
// caller (cel holds x fps). False + error on empty input, dimension
// mismatch, or IO failure (no partial file left behind).
bool writeApng(const std::string& path,
               const std::vector<ApngFrame>& frames, int loop,
               std::string& error);

// Flatten a premultiplied surface over an opaque matte color
// (sticker preset: no alpha surprise — alpha == 255 everywhere
// after, the M3 rgb<=alpha contract holds trivially).
void flattenMatte(Surface& s, std::uint8_t r, std::uint8_t g,
                  std::uint8_t b);

// PNG sequence plan: one output file per playback tick. expand=true
// repeats frame i `holds[i]` times (honest with the rhythm);
// false = one file per cel. holds size must match frames; invalid
// holds fall back to 1 (same clamp as celDelayCs).
std::vector<int> planPngSeq(int frames, const std::vector<int>& holds,
                            bool expand);

// Write the planned sequence as prefix_%04d.png files into an
// existing directory (ARCHITECTURE.md documents why files and not
// an engine ZIP: progress + cancel app-side). One Surface per
// planned tick, premultiplied; matte = "" keeps alpha, otherwise
// "#RRGGBB" flattens first. False + error when the dir is not
// writable, frames are empty, or a write fails (files already
// written are left in place, count reported honestly).
bool writePngSeq(const std::string& dir, const std::string& prefix,
                 const std::vector<const Surface*>& ticks,
                 const std::string& matte, std::string& error);

}  // namespace openclip

#endif
