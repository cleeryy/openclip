// SPDX-License-Identifier: GPL-3.0-or-later
// Headerless assert harness: TEST(name) { ... } with CHECK / CHECK_EQ.
#ifndef TEST_H
#define TEST_H

#include <cstdio>
#include <filesystem>
#include <string>
#include <system_error>
#include <vector>

namespace testh {

struct Case {
    std::string name;
    void (*fn)();
};

inline std::vector<Case>& registry() {
    static std::vector<Case> r;
    return r;
}

inline int& failures() {
    static int f = 0;
    return f;
}

inline int& checks() {
    static int c = 0;
    return c;
}

inline void fail(const char* file, int line, const std::string& msg) {
    ++failures();
    std::printf("  FAIL %s:%d: %s\n", file, line, msg.c_str());
    std::fflush(stdout);
}

// Portable scratch directory for tests that touch disk. Resolves the OS
// temp dir (honouring TMPDIR), keeps a dedicated `openclip-tests`
// sub-directory created on first use. Never throws: on any filesystem
// error it degrades to the bare temp dir (and to "." as a last resort)
// so tests report a normal CHECK failure instead of aborting.
inline std::string& scratchDir() {
    static std::string dir = [] {
        std::error_code ec;
        std::filesystem::path base = std::filesystem::temp_directory_path(ec);
        if (ec || base.empty()) base = std::filesystem::path("/tmp");
        const std::filesystem::path p = base / "openclip-tests";
        std::filesystem::create_directories(p, ec);
        if (ec) {
            std::error_code ec2;
            if (std::filesystem::is_directory(base, ec2)) return base.string();
            return std::string(".");
        }
        return p.string();
    }();
    return dir;
}

// Absolute path of `name` inside the scratch directory. Callers keep
// per-test unique names so the suite stays deterministic and can run in
// parallel with other checkouts.
inline std::string tmpPath(const std::string& name) {
    return (std::filesystem::path(scratchDir()) / name).string();
}

// Best-effort cleanup: drop the scratch directory when nothing is left in
// it. Non-recursive on purpose, so a concurrently running suite in another
// worktree is never disturbed.
inline void cleanupScratch() {
    std::error_code ec;
    std::filesystem::remove(scratchDir(), ec);
}

struct Registrar {
    Registrar(const char* name, void (*fn)()) {
        registry().push_back({name, fn});
    }
};

inline int runAll() {
    int passed = 0;
    for (auto& c : registry()) {
        const int f0 = failures();
        std::printf("[ %s ]\n", c.name.c_str());
        std::fflush(stdout);
        c.fn();
        if (failures() == f0) {
            ++passed;
        } else {
            std::printf("  ^^ %s FAILED\n", c.name.c_str());
            std::fflush(stdout);
        }
    }
    std::printf("\n%d/%lu tests passed, %d checks, %d failures\n",
                passed, (unsigned long)registry().size(), checks(), failures());
    std::fflush(stdout);
    cleanupScratch();
    return failures() == 0 ? 0 : 1;
}

}  // namespace testh

#define TEST(name)                                        \
    static void test_fn_##name();                         \
    static testh::Registrar reg_##name(#name, test_fn_##name); \
    static void test_fn_##name()

#define CHECK(cond)                                              \
    do {                                                         \
        ++testh::checks();                                       \
        if (!(cond))                                             \
            testh::fail(__FILE__, __LINE__, "CHECK(" #cond ")"); \
    } while (0)

#define CHECK_EQ(a, b)                                                     \
    do {                                                                   \
        ++testh::checks();                                                 \
        if (!((a) == (b)))                                                  \
            testh::fail(__FILE__, __LINE__,                                \
                        std::string("CHECK_EQ(" #a ", " #b ")"));           \
    } while (0)

#endif
