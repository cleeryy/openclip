// SPDX-License-Identifier: GPL-3.0-or-later
#include "minizip.h"

#include <cstdio>
#include <cstring>
#include <zlib.h>

namespace openclip {
namespace zip {
namespace {

void put16(std::vector<std::uint8_t>& b, std::uint16_t v) {
    b.push_back(v & 0xff);
    b.push_back((v >> 8) & 0xff);
}
void put32(std::vector<std::uint8_t>& b, std::uint32_t v) {
    for (int i = 0; i < 4; ++i) b.push_back((v >> (8 * i)) & 0xff);
}
std::uint16_t get16(const std::uint8_t* p) {
    return p[0] | (std::uint16_t(p[1]) << 8);
}
std::uint32_t get32(const std::uint8_t* p) {
    return std::uint32_t(p[0]) | (std::uint32_t(p[1]) << 8) |
           (std::uint32_t(p[2]) << 16) | (std::uint32_t(p[3]) << 24);
}

bool readAll(const std::string& path, std::vector<std::uint8_t>& out) {
    FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return false;
    std::fseek(f, 0, SEEK_END);
    long n = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    if (n < 0) {
        std::fclose(f);
        return false;
    }
    out.resize(n);
    const std::size_t got = std::fread(out.data(), 1, n, f);
    std::fclose(f);
    return got == (std::size_t)n;
}

bool writeAll(const std::string& path, const std::vector<std::uint8_t>& data) {
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) return false;
    const std::size_t wrote = std::fwrite(data.data(), 1, data.size(), f);
    std::fclose(f);
    return wrote == data.size();
}

}  // namespace

bool writeZip(
    const std::string& path,
    const std::vector<std::pair<std::string, std::vector<std::uint8_t>>>& entries,
    bool compress, std::string& error) {
    std::vector<std::uint8_t> out;
    struct Central {
        std::string name;
        std::uint32_t crc, compSize, size, offset;
        std::uint16_t method;
    };
    std::vector<Central> central;

    for (const auto& e : entries) {
        const bool store = !compress || e.first == "mimetype";
        std::vector<std::uint8_t> comp;
        std::uint16_t method = 0;
        if (store) {
            comp = e.second;
        } else {
            uLong bound = compressBound(e.second.size());
            comp.resize(bound);
            uLong dstLen = bound;
            // Raw deflate stream for zip (method 8).
            z_stream zs{};
            if (deflateInit2(&zs, Z_DEFAULT_COMPRESSION, Z_DEFLATED, -MAX_WBITS,
                             8, Z_DEFAULT_STRATEGY) != Z_OK) {
                error = "zip: deflateInit failed";
                return false;
            }
            zs.next_in = const_cast<Bytef*>(e.second.data());
            zs.avail_in = e.second.size();
            zs.next_out = comp.data();
            zs.avail_out = comp.size();
            int rc = deflate(&zs, Z_FINISH);
            deflateEnd(&zs);
            if (rc != Z_STREAM_END) {
                error = "zip: deflate failed";
                return false;
            }
            comp.resize(comp.size() - zs.avail_out);
            method = 8;
        }
        const std::uint32_t crc = crc32(0, e.second.data(), e.second.size());
        const std::uint32_t off = out.size();
        // Local file header.
        put32(out, 0x04034b50);
        put16(out, 20);
        put16(out, 0);
        put16(out, method);
        put16(out, 0);
        put16(out, 0);
        put32(out, crc);
        put32(out, comp.size());
        put32(out, e.second.size());
        put16(out, e.first.size());
        put16(out, 0);
        out.insert(out.end(), e.first.begin(), e.first.end());
        out.insert(out.end(), comp.begin(), comp.end());
        central.push_back({e.first, crc, (std::uint32_t)comp.size(),
                           (std::uint32_t)e.second.size(), off, method});
    }

    const std::uint32_t cdirOff = out.size();
    for (const auto& c : central) {
        put32(out, 0x02014b50);
        put16(out, 20);
        put16(out, 20);
        put16(out, 0);
        put16(out, c.method);
        put16(out, 0);
        put16(out, 0);
        put32(out, c.crc);
        put32(out, c.compSize);
        put32(out, c.size);
        put16(out, c.name.size());
        put16(out, 0);
        put16(out, 0);
        put16(out, 0);
        put16(out, 0);
        put32(out, 0);
        put32(out, c.offset);
        out.insert(out.end(), c.name.begin(), c.name.end());
    }
    const std::uint32_t cdirSize = out.size() - cdirOff;
    put32(out, 0x06054b50);
    put16(out, 0);
    put16(out, 0);
    put16(out, central.size());
    put16(out, central.size());
    put32(out, cdirSize);
    put32(out, cdirOff);
    put16(out, 0);

    if (!writeAll(path, out)) {
        error = "zip: cannot write " + path;
        return false;
    }
    return true;
}

bool readZip(const std::string& path,
             std::map<std::string, std::vector<std::uint8_t>>& out,
             std::string& error) {
    std::vector<std::uint8_t> data;
    if (!readAll(path, data)) {
        error = "zip: cannot read " + path;
        return false;
    }
    if (data.size() < 22) {
        error = "zip: file too small";
        return false;
    }
    // Find end-of-central-directory (scan last 64k+22).
    std::size_t eocd = std::string::npos;
    const std::size_t scanFrom =
        data.size() > 65557 + 22 ? data.size() - (65557 + 22) : 0;
    for (std::size_t i = data.size() - 22;; --i) {
        if (get32(&data[i]) == 0x06054b50) {
            eocd = i;
            break;
        }
        if (i == scanFrom) break;
    }
    if (eocd == std::string::npos) {
        error = "zip: EOCD not found";
        return false;
    }
    const std::uint16_t count = get16(&data[eocd + 10]);
    const std::uint32_t cdirOff = get32(&data[eocd + 16]);
    std::size_t p = cdirOff;
    for (int i = 0; i < count; ++i) {
        if (p + 46 > data.size() || get32(&data[p]) != 0x02014b50) {
            error = "zip: bad central directory";
            return false;
        }
        const std::uint16_t method = get16(&data[p + 10]);
        const std::uint32_t crc = get32(&data[p + 16]);
        const std::uint32_t compSize = get32(&data[p + 20]);
        const std::uint32_t size = get32(&data[p + 24]);
        const std::uint16_t nlen = get16(&data[p + 28]);
        const std::uint16_t elen = get16(&data[p + 30]);
        const std::uint16_t clen = get16(&data[p + 32]);
        const std::uint32_t lho = get32(&data[p + 42]);
        std::string name((char*)&data[p + 46], nlen);
        p += 46 + nlen + elen + clen;
        // Local header -> data offset.
        if (lho + 30 > data.size() || get32(&data[lho]) != 0x04034b50) {
            error = "zip: bad local header for " + name;
            return false;
        }
        const std::uint16_t lnlen = get16(&data[lho + 26]);
        const std::uint16_t lelen = get16(&data[lho + 28]);
        const std::size_t dstart = lho + 30 + lnlen + lelen;
        if (dstart + compSize > data.size()) {
            error = "zip: truncated entry " + name;
            return false;
        }
        std::vector<std::uint8_t> raw;
        if (method == 0) {
            raw.assign(&data[dstart], &data[dstart] + compSize);
        } else if (method == 8) {
            raw.resize(size);
            z_stream zs{};
            if (inflateInit2(&zs, -MAX_WBITS) != Z_OK) {
                error = "zip: inflateInit failed";
                return false;
            }
            zs.next_in = const_cast<Bytef*>(&data[dstart]);
            zs.avail_in = compSize;
            zs.next_out = raw.data();
            zs.avail_out = size;
            int rc = inflate(&zs, Z_FINISH);
            inflateEnd(&zs);
            if (rc != Z_STREAM_END) {
                error = "zip: inflate failed for " + name;
                return false;
            }
        } else {
            error = "zip: unsupported method for " + name;
            return false;
        }
        if (crc32(0, raw.data(), raw.size()) != crc) {
            error = "zip: CRC mismatch for " + name;
            return false;
        }
        out[name] = std::move(raw);
    }
    return true;
}

}  // namespace zip
}  // namespace openclip
