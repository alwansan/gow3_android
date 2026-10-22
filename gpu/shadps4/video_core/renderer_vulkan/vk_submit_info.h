// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once

#include <array>
#include <cstdint>
#include <cstdlib>
#include "video_core/renderer_vulkan/vk_common.h"

namespace Vulkan {

struct SubmitInfo {
    static constexpr uint32_t MaxWaits = 4;
    // 调用方最多四个信号，另留一个给 scheduler 的 timeline。
    static constexpr uint32_t MaxSignals = 5;
    std::array<vk::Semaphore, MaxWaits> wait_semas{};
    std::array<uint64_t, MaxWaits> wait_ticks{};
    std::array<vk::PipelineStageFlags, MaxWaits> wait_stages{};
    std::array<vk::Semaphore, MaxSignals> signal_semas{};
    std::array<uint64_t, MaxSignals> signal_ticks{};
    vk::Fence fence{};
    uint32_t num_wait_semas{};
    uint32_t num_signal_semas{};

    bool TryAddWait(vk::Semaphore semaphore, uint64_t tick = 1,
                    vk::PipelineStageFlags stage = vk::PipelineStageFlagBits::eAllCommands) {
        if (!semaphore) return true;
        if (num_wait_semas == MaxWaits) return false;
        wait_semas[num_wait_semas] = semaphore;
        wait_ticks[num_wait_semas] = tick;
        wait_stages[num_wait_semas++] = stage;
        return true;
    }
    void AddWait(vk::Semaphore semaphore, uint64_t tick = 1,
                 vk::PipelineStageFlags stage = vk::PipelineStageFlagBits::eAllCommands) {
        if (!TryAddWait(semaphore, tick, stage)) std::abort();
    }
    bool TryAddSignal(vk::Semaphore semaphore, uint64_t tick = 1) {
        if (!semaphore) return true;
        if (num_signal_semas == MaxSignals) return false;
        signal_semas[num_signal_semas] = semaphore;
        signal_ticks[num_signal_semas++] = tick;
        return true;
    }
    void AddSignal(vk::Semaphore semaphore, uint64_t tick = 1) {
        if (!TryAddSignal(semaphore, tick)) std::abort();
    }
    void AddSignal(vk::Fence value) { fence = value; }
};

} // namespace Vulkan
