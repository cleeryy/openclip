// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip M4 — document-level load/save across formats.
#include "openclip/document.h"

#include "openclip/ora.h"
#include "openclip/psd.h"
#include "openclip/tiff.h"

#include <cstdio>

namespace openclip {
namespace {

bool readFileHead(const std::string& path, std::uint8_t* buf,
                  std::size_t n) {
    std::FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return false;
    const std::size_t got = std::fread(buf, 1, n, f);
    std::fclose(f);
    return got == n;
}

}  // namespace

DocFormat detectFormat(const std::string& path) {
    std::uint8_t head[40] = {0};
    if (!readFileHead(path, head, sizeof head)) return DocFormat::Unknown;
    if (head[0] == '8' && head[1] == 'B' && head[2] == 'P' && head[3] == 'S')
        return DocFormat::Psd;
    if (head[0] == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G')
        return DocFormat::Png;
    if (head[0] == 'I' && head[1] == 'I' && head[2] == 42 &&
        head[3] == 0)
        return DocFormat::Tiff;
    if (head[0] == 'M' && head[1] == 'M' && head[2] == 0 && head[3] == 42)
        return DocFormat::Tiff;
    if (head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4) {
        // ZIP container: possible ORA — check the stored mimetype area
        // heuristically (first local file entry header). Definitive check
        // happens in loadOra.
        return DocFormat::Ora;
    }
    return DocFormat::Unknown;
}

bool loadDocument(Canvas& c, const std::string& path, std::string& error) {
    const DocFormat fmt = detectFormat(path);
    switch (fmt) {
        case DocFormat::Ora:
            return loadOra(c, path, error);
        case DocFormat::Png: {
            // Flatten-load: one layer from the PNG via the ORA helpers is
            // not exposed; use stb through theora path by a temporary ORA
            // is overkill — load directly via loadPngFile if provided in
            // ora.h? Keep the M4 contract straightforward: PNG import goes
            // through the same surface decode used by ORA (re-exposed).
            extern bool loadPngInto(Canvas&, const std::string&,
                                    std::string&);  // defined in ora.cpp
            return loadPngInto(c, path, error);
        }
        case DocFormat::Psd:
            return loadPsd(c, path, error);
        case DocFormat::Tiff:
            return loadTiff(c, path, error);
        case DocFormat::Unknown:
        default:
            error = "document: unknown or unsupported format for " + path;
            return false;
    }
}

bool saveDocument(const Canvas& c, const std::string& path,
                  const std::string& format, std::string& error,
                  int* approxOut) {
    if (format == "ora") return saveOra(c, path, error);
    if (format == "psd") return savePsd(c, path, error, approxOut);
    if (format == "png") {
        extern bool exportPng(const Canvas&, const std::string&,
                              std::string&);  // defined in ora.cpp
        return exportPng(c, path, error);
    }
    error = "document: unknown save format \"" + format +
            "\" (choose ora | png | psd)";
    return false;
}

}  // namespace openclip
