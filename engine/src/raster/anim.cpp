// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip T3 — fixed-schema JSON parse/serialize for animation
// tracks (ARCHITECTURE.md "T3 additions"). Minimal scanner, no
// dependency: locates the "keys" array and reads per-object numbers
// by key name. M3 rule throughout.
#include "openclip/anim.h"

#include <algorithm>
#include <cstdio>

namespace openclip {
namespace {

// Next bare number after `from` (skips ;,: whitespace quotes).
// False when no parseable number follows.
bool scanNum(const std::string& j, std::size_t from, double& v,
             std::size_t& end) {
    std::size_t p = from;
    while (p < j.size() &&
           (j[p] == ' ' || j[p] == '\t' || j[p] == '\n' ||
            j[p] == '\r' || j[p] == ':' || j[p] == ',' || j[p] == '"' ||
            j[p] == '\'')) {
        ++p;
    }
    if (p >= j.size()) return false;
    if (j[p] == ']' || j[p] == '}') return false;
    const char* beg = j.c_str() + p;
    char* stop = nullptr;
    v = std::strtod(beg, &stop);
    if (stop == beg) return false;
    end = static_cast<std::size_t>(stop - j.c_str());
    return true;
}

// Value of "key" inside object [objBeg, objEnd): number after colon.
bool objNum(const std::string& j, std::size_t objBeg, std::size_t objEnd,
            const char* key, double& v) {
    const std::string pat = std::string("\"") + key + "\"";
    std::size_t at = j.find(pat, objBeg);
    if (at == std::string::npos || at >= objEnd) return false;
    std::size_t colon = j.find(':', at + pat.size());
    if (colon == std::string::npos || colon >= objEnd) return false;
    std::size_t end = colon;
    return scanNum(j, colon + 1, v, end) && end <= objEnd + 1;
}

// Value of "key" as a bare string (ease names). False when absent.
bool objStr(const std::string& j, std::size_t objBeg, std::size_t objEnd,
            const char* key, std::string& v) {
    const std::string pat = std::string("\"") + key + "\"";
    std::size_t at = j.find(pat, objBeg);
    if (at == std::string::npos || at >= objEnd) return false;
    std::size_t colon = j.find(':', at + pat.size());
    if (colon == std::string::npos || colon >= objEnd) return false;
    std::size_t q0 = j.find('"', colon + 1);
    if (q0 == std::string::npos || q0 >= objEnd) return false;
    std::size_t q1 = j.find('"', q0 + 1);
    if (q1 == std::string::npos || q1 > objEnd) return false;
    v = j.substr(q0 + 1, q1 - q0 - 1);
    return true;
}

bool parseKeyList(const std::string& json,
                  std::vector<std::vector<std::pair<std::string, double>>>& nums,
                  std::vector<std::string>& eases) {
    nums.clear();
    eases.clear();
    const std::size_t ka = json.find("\"keys\"");
    if (ka == std::string::npos) return false;
    const std::size_t arr = json.find('[', ka + 6);
    if (arr == std::string::npos) return false;
    std::size_t p = arr + 1;
    while (true) {
        while (p < json.size() && (json[p] == ' ' || json[p] == '\t' ||
                                   json[p] == '\n' || json[p] == '\r' ||
                                   json[p] == ',')) {
            ++p;
        }
        if (p >= json.size()) return false;
        if (json[p] == ']') return true;  // empty or end
        if (json[p] != '{') return false;
        // Match braces (no nesting inside key objects).
        const std::size_t end = json.find('}', p + 1);
        if (end == std::string::npos) return false;
        static const char* kNames[] = {"cel", "dx",  "dy",    "scale",
                                       "angle", "opacity", "x", "y",
                                       "zoom"};
        std::vector<std::pair<std::string, double>> row;
        for (const char* k : kNames) {
            double v = 0;
            if (objNum(json, p, end, k, v)) row.push_back({k, v});
        }
        std::string ease = "linear";
        objStr(json, p, end, "ease", ease);
        nums.push_back(std::move(row));
        eases.push_back(ease);
        p = end + 1;
    }
}

double rowNum(
    const std::vector<std::pair<std::string, double>>& row,
    const std::string& key, double fallback) {
    for (const auto& kv : row)
        if (kv.first == key) return kv.second;
    return fallback;
}

}  // namespace

bool parseAnimTrack(const std::string& json, std::vector<AnimKey>& out) {
    std::vector<std::vector<std::pair<std::string, double>>> nums;
    std::vector<std::string> eases;
    if (!parseKeyList(json, nums, eases)) return false;
    std::vector<AnimKey> keys;
    for (std::size_t i = 0; i < nums.size(); ++i) {
        AnimKey k;
        const double cel = rowNum(nums[i], "cel", 0.0);
        if (cel < 0.0 || cel > 1000000.0) return false;  // garbage
        k.cel = static_cast<int>(std::lround(cel));
        k.keys.dx = static_cast<float>(rowNum(nums[i], "dx", 0.0));
        k.keys.dy = static_cast<float>(rowNum(nums[i], "dy", 0.0));
        k.keys.scale =
            clampAnimScale(static_cast<float>(rowNum(nums[i], "scale", 1.0)));
        k.keys.angle = static_cast<float>(rowNum(nums[i], "angle", 0.0));
        k.keys.opacity =
            clamp01(static_cast<float>(rowNum(nums[i], "opacity", 1.0)));
        k.ease = validEase(eases[i]) ? eases[i] : "linear";
        keys.push_back(k);
    }
    std::sort(keys.begin(), keys.end(),
              [](const AnimKey& a, const AnimKey& b) { return a.cel < b.cel; });
    out.swap(keys);
    return true;
}

bool parseCameraTrack(const std::string& json,
                      std::vector<CameraKey>& out) {
    std::vector<std::vector<std::pair<std::string, double>>> nums;
    std::vector<std::string> eases;
    if (!parseKeyList(json, nums, eases)) return false;
    std::vector<CameraKey> keys;
    for (std::size_t i = 0; i < nums.size(); ++i) {
        CameraKey k;
        const double cel = rowNum(nums[i], "cel", 0.0);
        if (cel < 0.0 || cel > 1000000.0) return false;
        k.cel = static_cast<int>(std::lround(cel));
        k.x = static_cast<float>(rowNum(nums[i], "x", 0.0));
        k.y = static_cast<float>(rowNum(nums[i], "y", 0.0));
        k.zoom =
            clampCamZoom(static_cast<float>(rowNum(nums[i], "zoom", 1.0)));
        k.angle = static_cast<float>(rowNum(nums[i], "angle", 0.0));
        k.ease = validEase(eases[i]) ? eases[i] : "linear";
        keys.push_back(k);
    }
    std::sort(keys.begin(), keys.end(), [](const CameraKey& a,
                                           const CameraKey& b) {
        return a.cel < b.cel;
    });
    out.swap(keys);
    return true;
}

std::string animTrackJson(const std::vector<AnimKey>& keys) {
    std::string j = "{\"keys\":[";
    for (std::size_t i = 0; i < keys.size(); ++i) {
        char buf[256];
        std::snprintf(buf, sizeof(buf),
                      "%s{\"cel\":%d,\"dx\":%.3f,\"dy\":%.3f,"
                      "\"scale\":%.4f,\"angle\":%.3f,\"opacity\":%.4f,"
                      "\"ease\":\"%s\"}",
                      i ? "," : "", keys[i].cel, keys[i].keys.dx,
                      keys[i].keys.dy, keys[i].keys.scale,
                      keys[i].keys.angle, keys[i].keys.opacity,
                      keys[i].ease.c_str());
        j += buf;
    }
    j += "]}";
    return j;
}

std::string cameraTrackJson(const std::vector<CameraKey>& keys) {
    std::string j = "{\"keys\":[";
    for (std::size_t i = 0; i < keys.size(); ++i) {
        char buf[256];
        std::snprintf(buf, sizeof(buf),
                      "%s{\"cel\":%d,\"x\":%.3f,\"y\":%.3f,"
                      "\"zoom\":%.4f,\"angle\":%.3f,\"ease\":\"%s\"}",
                      i ? "," : "", keys[i].cel, keys[i].x, keys[i].y,
                      keys[i].zoom, keys[i].angle, keys[i].ease.c_str());
        j += buf;
    }
    j += "]}";
    return j;
}

}  // namespace openclip
