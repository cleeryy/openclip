// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip M1 engine — raster surface: 256px tiles, RGBA8 premultiplied.
#ifndef OPENCLIP_SURFACE_H
#define OPENCLIP_SURFACE_H

#include <cstdint>
#include <vector>

namespace openclip {

inline constexpr int kTileSize = 256;

struct Pixel {
    std::uint8_t r = 0, g = 0, b = 0, a = 0;  // premultiplied: rgb <= a
};

class Surface {
public:
    Surface() = default;
    Surface(int w, int h) { resize(w, h); }

    void resize(int w, int h);
    void clear();
    int width() const { return w_; }
    int height() const { return h_; }
    bool empty() const { return px_.empty(); }

    Pixel get(int x, int y) const;
    void set(int x, int y, Pixel p);
    // Raw premultiplied RGBA bytes, row-major.
    const std::vector<std::uint8_t>& bytes() const { return px_; }
    std::vector<std::uint8_t>& bytes() { return px_; }

    int tilesX() const { return (w_ + kTileSize - 1) / kTileSize; }
    int tilesY() const { return (h_ + kTileSize - 1) / kTileSize; }

    // Copy one 256px tile (clipped at edges) into out (RGBA bytes).
    void tileCopy(int tx, int ty, std::vector<std::uint8_t>& out) const;
    void tileRestore(int tx, int ty, const std::vector<std::uint8_t>& data);

private:
    int w_ = 0, h_ = 0;
    std::vector<std::uint8_t> px_;
};

}  // namespace openclip

#endif
