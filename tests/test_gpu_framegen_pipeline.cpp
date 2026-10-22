// SPDX-License-Identifier: GPL-2.0-or-later
#include <cassert>
#include <cstdio>
#include <limits>
#include "video_core/renderer_vulkan/framegen_policy.h"
#include "video_core/renderer_vulkan/vk_submit_info.h"
#include "video_core/renderer_vulkan/vk_framegen_barriers.h"
#include "video_core/renderer_vulkan/sync_file_poll.h"
#include <unistd.h>

int main() {
    using namespace Vulkan;
    using namespace Vulkan::Framegen;
    assert(!UseAsync(ParseSyncMode("")));
    assert(!UseAsync(ParseSyncMode("broken")));
    assert(!UseAsync(ParseSyncMode("complete"), true));
    assert(UseAsync(ParseSyncMode("async")));
    assert(UseAsync(ParseSyncMode("auto"), true));
    assert(!UseAsync(ParseSyncMode("async"), true, true));
    const auto release = ReleaseOutput({}, 3);
    assert(release.oldLayout == vk::ImageLayout::eTransferSrcOptimal);
    assert(release.newLayout == vk::ImageLayout::eGeneral);
    assert(release.srcQueueFamilyIndex == 3);
    assert(release.dstQueueFamilyIndex == VK_QUEUE_FAMILY_EXTERNAL);
    int descriptors[2];
    assert(pipe(descriptors) == 0);
    assert(PollSyncFile(descriptors[0], 0) == 0);
    const char byte = 'x';
    assert(write(descriptors[1], &byte, 1) == 1);
    assert(PollSyncFile(descriptors[0], 0) == 1);
    close(descriptors[0]);
    assert(PollSyncFile(descriptors[0], 0) < 0);
    close(descriptors[1]);
    SubmitInfo info{};
    info.AddWait({});
    assert(info.num_wait_semas == 0);
    for (uint64_t i = 0; i < 4; ++i) {
        const vk::Semaphore semaphore{reinterpret_cast<VkSemaphore>(uintptr_t(i + 1))};
        assert(info.TryAddWait(semaphore, i + 11, vk::PipelineStageFlagBits::eTransfer));
        assert(info.wait_semas[i] == semaphore);
        assert(info.wait_ticks[i] == i + 11);
        assert(info.wait_stages[i] == vk::PipelineStageFlagBits::eTransfer);
        assert(info.TryAddSignal(semaphore, i + 21));
    }
    const vk::Semaphore extra{reinterpret_cast<VkSemaphore>(uintptr_t(9))};
    assert(!info.TryAddWait(extra));
    assert(info.TryAddSignal(extra, 99));
    assert(!info.TryAddSignal(extra));
    BufferLease lease{};
    for (unsigned i = 0; i < 100; ++i) {
        auto token = lease.Reserve(7, 2);
        assert(token.serial != 0 && token.index == 2);
        assert(!lease.Reserve(7, 2).serial);
        assert(!lease.Cancel(token, 8));
        assert(lease.Cancel(token, 7));
        assert(!lease.Cancel(token, 7));
        const auto next = lease.Reserve(7, 2);
        assert(!lease.Cancel(token, 7));
        assert(lease.HandOff(next, 7));
        assert(!lease.Cancel(next, 7));
        assert(lease.Release());
        assert(!lease.Release());
    }
    for (uint32_t multiplier = 2; multiplier <= 4; ++multiplier) {
        assert(GenerationCount(multiplier, false, true) == multiplier - 1);
        assert(GenerationCount(multiplier, true, true) == 0);
        assert(GenerationCount(multiplier, false, false) == 0);
        const auto now = PacingPlan::Clock::now();
        const auto plan = PacingPlan::Create(now, 30.0, multiplier, true);
        assert(plan.Active() && !plan.Expired(now));
        assert(plan.Expired(plan.RealTarget()));
        assert(plan.Slot(0) == now);
        assert(plan.RealTarget() < now + std::chrono::milliseconds(30));
    }
    assert(!PacingPlan::Create({}, std::numeric_limits<double>::quiet_NaN(), 2, true).Active());
    assert(!PacingPlan::Create({}, std::numeric_limits<double>::infinity(), 2, true).Active());
    assert(!PacingPlan::Create({}, 30, 2, false).Active());
    assert(!PacingPlan::Create({}, 0, 2, true).Active());
    assert(FullCoverage(0, 0, 1920, 1080, 1920, 1080));
    assert(!FullCoverage(0, 1, 1920, 1079, 1920, 1080));
    assert(DirectCopy(1919, 1079, 1919, 1079, true, true));
    assert(!DirectCopy(1920, 1080, 1920, 1080, false, true));
    assert(!DirectCopy(1280, 720, 1920, 1080, true, true));
    std::puts("gpu-framegen-pipeline PASS");
}
