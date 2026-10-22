// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once

#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <string_view>

namespace Vulkan::Framegen {

enum class SyncMode { Auto, Async, Complete };

constexpr SyncMode ParseSyncMode(std::string_view value) {
    if (value == "async") return SyncMode::Async;
    if (value == "complete") return SyncMode::Complete;
    return SyncMode::Auto;
}

constexpr bool UseAsync(SyncMode mode, bool verified = false, bool host = false) {
    return !host && (mode == SyncMode::Async || (mode == SyncMode::Auto && verified));
}

inline SyncMode SyncModeFromEnv(const char* name) {
    const char* value = std::getenv(name);
    return ParseSyncMode(value ? value : "");
}

inline bool Enabled(const char* name) {
    const char* value = std::getenv(name);
    return value && std::string_view(value) == "1";
}

// 本地 token 不进入 wire；serial 防止同代中迟到的取消归还已重新分配的缓冲。
struct Reservation {
    uint32_t generation{};
    uint32_t index{};
    uint64_t serial{};
};

struct BufferLease {
    uint64_t serial{};
    bool reserved{};
    bool handed{};

    Reservation Reserve(uint32_t generation, uint32_t index) {
        if (reserved || handed) return {};
        reserved = true;
        return {generation, index, ++serial};
    }
    bool Matches(Reservation token, uint32_t generation) const {
        return reserved && token.serial != 0 && token.serial == serial &&
               token.generation == generation;
    }
    bool Cancel(Reservation token, uint32_t generation) {
        if (!Matches(token, generation)) return false;
        reserved = false;
        return true;
    }
    bool HandOff(Reservation token, uint32_t generation) {
        if (!Matches(token, generation)) return false;
        reserved = false;
        handed = true;
        return true;
    }
    bool Release() {
        if (!handed) return false;
        handed = false;
        return true;
    }
};

constexpr uint32_t GenerationCount(uint32_t multiplier, bool warmup, bool reserved) {
    return !warmup && reserved && multiplier >= 2 && multiplier <= 4 ? multiplier - 1 : 0;
}

struct PacingPlan {
    using Clock = std::chrono::steady_clock;
    Clock::time_point base{};
    Clock::duration interval{};
    uint32_t multiplier{};

    static PacingPlan Create(Clock::time_point base, double ms, uint32_t multiplier, bool enabled) {
        if (!enabled || !std::isfinite(ms) || ms < 2.0 || ms >= 200.0 ||
            multiplier < 2 || multiplier > 4) {
            return {};
        }
        return {base, std::chrono::duration_cast<Clock::duration>(
                          std::chrono::duration<double, std::milli>(ms)), multiplier};
    }
    bool Active() const { return multiplier != 0; }
    Clock::time_point Slot(uint32_t index) const {
        return base + interval * index / multiplier;
    }
    Clock::time_point RealTarget() const { return Slot(multiplier - 1); }
    bool Expired(Clock::time_point now) const { return Active() && now >= RealTarget(); }
};

constexpr bool FullCoverage(int32_t x, int32_t y, uint32_t width, uint32_t height,
                            uint32_t target_width, uint32_t target_height) {
    return x == 0 && y == 0 && width == target_width && height == target_height;
}

constexpr bool DirectCopy(uint32_t source_width, uint32_t source_height,
                          uint32_t target_width, uint32_t target_height,
                          bool same_format, bool full_coverage) {
    return same_format && full_coverage && source_width == target_width &&
           source_height == target_height;
}

} // namespace Vulkan::Framegen
