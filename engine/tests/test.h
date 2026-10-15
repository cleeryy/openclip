// SPDX-License-Identifier: GPL-3.0-or-later
// Headerless assert harness: TEST(name) { ... } with CHECK / CHECK_EQ.
#ifndef TEST_H
#define TEST_H

#include <cstdio>
#include <string>
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
        c.fn();
        if (failures() == f0) {
            ++passed;
        } else {
            std::printf("  ^^ %s FAILED\n", c.name.c_str());
        }
    }
    std::printf("\n%d/%lu tests passed, %d checks, %d failures\n",
                passed, (unsigned long)registry().size(), checks(), failures());
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
