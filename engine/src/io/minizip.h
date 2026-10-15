// SPDX-License-Identifier: GPL-3.0-or-later
// Minimal ZIP writer/reader (stored + deflate via zlib) for OpenRaster.
// Clean-room implementation; ZIP format knowledge from public specs
// (PKWARE APPNOTE, widely documented). CRC32 via zlib.
#ifndef OPENCLIP_MINIZIP_H
#define OPENCLIP_MINIZIP_H

#include <cstdint>
#include <map>
#include <string>
#include <vector>

namespace openclip {
namespace zip {

// Write a .zip with the given entries. deflate=true compresses entries
// except those named in storedFirst (e.g. ORA mimetype must be stored).
bool writeZip(const std::string& path,
              const std::vector<std::pair<std::string, std::vector<std::uint8_t>>>& entries,
              bool compress, std::string& error);

// Read all entries of a .zip into memory.
bool readZip(const std::string& path,
             std::map<std::string, std::vector<std::uint8_t>>& out,
             std::string& error);

}  // namespace zip
}  // namespace openclip

#endif
