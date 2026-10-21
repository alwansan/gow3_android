// SPDX-License-Identifier: GPL-2.0-or-later
// 适配 Daedalus fork 的 Android CPU/GPU 预算；桌面缺省策略保持不变。
#pragma once

#include <cstdlib>
#include <string_view>

namespace Gow3Mobile {

inline bool Android() {
#ifdef __ANDROID__
    return true;
#else
    return std::getenv("GOW3_ANDROID_INPUT") || std::getenv("GOW3_ANDROID_PORT");
#endif
}

inline bool Enabled(const char* name, bool fallback = true) {
    const char* value = std::getenv(name);
    return value && *value ? std::string_view(value) != "0" : fallback;
}

constexpr unsigned FramesQueued(bool android) { return android ? 2 : 1; }
constexpr unsigned PreparationWorkers(bool android, unsigned desktop) { return android ? 0 : desktop; }
constexpr unsigned CompilerWorkers(bool android, unsigned desktop) { return android ? 2 : desktop; }
constexpr unsigned CaptureWorkers(bool android, unsigned desktop) { return android ? 1 : desktop; }
inline unsigned WorkerOverride(const char* name, unsigned fallback) {
    if (const char* value = std::getenv(name)) {
        char* end{};
        const auto count = std::strtoul(value, &end, 10);
        if (end != value && !*end && count >= 1 && count <= 6) return unsigned(count);
    }
    return fallback;
}
constexpr bool WaitHostCopies(bool has_downloads, bool fast_path) {
    return has_downloads || !fast_path;
}
constexpr unsigned long long BoundedTick(unsigned long long signal, unsigned limit = 8) {
    return signal > limit ? signal - limit : 0;
}

} // namespace Gow3Mobile
