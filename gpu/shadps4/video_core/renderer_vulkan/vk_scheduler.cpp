// SPDX-FileCopyrightText: Copyright 2025 shadPS4 Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <thread>
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <unordered_map>
#ifndef _WIN32
#include <dlfcn.h>
#include <pthread.h>
#endif
#include <functional>

#include "gow3_copy.h"
#include "video_core/renderer_vulkan/vk_gpu_profiler.h"
#include "gow3_toggles.h"
#include "video_core/renderer_vulkan/vk_wait_diagnostics.h"
#include "common/arch.h"
#include "common/assert.h"
#include "common/debug.h"
#include "common/thread.h"
#include "imgui/renderer/texture_manager.h"
#include "video_core/renderer_vulkan/vk_instance.h"
#include "video_core/renderer_vulkan/vk_recording_observer.h"
#include "video_core/renderer_vulkan/vk_scheduler.h"
#include "gow3_threads.h"
#include "gow3_mobile_policy.h"

namespace Vulkan {

namespace {
/// gow3: Bloodborne 风格的可选切段阈值。0（默认）关闭，正常路径不产生任何额外 pass 断裂；
/// >0 时每录制这么多 KiB 就收口一次 open render pass，并在下一次同 state 的 Begin 用 LOAD 续接。
size_t PassCutBytes() {
    static const size_t bytes = [] {
        const char* env = std::getenv("GOW3_PASS_CUT_KB");
        const int kb = env ? std::atoi(env) : 0;
        return kb > 0 ? size_t(kb) * 1024 : size_t{0};
    }();
    return bytes;
}
} // namespace

std::mutex Scheduler::submit_mutex;

Scheduler::Scheduler(const Instance& instance, bool threaded_recording)
    : instance{instance}, work_semaphore{instance}, command_pool{instance, &work_semaphore} {
    // gow3: GOW3_VK_RECORD_THREAD=0 records on the calling thread.
    const char* env = std::getenv("GOW3_VK_RECORD_THREAD");
    if (threaded_recording && !(env && env[0] == '0')) {
        record_chunk = AcquireChunk();
        recorder_thread = std::jthread(std::bind_front(&Scheduler::RecorderThread, this));
        recording_threaded = true;
    }
    // GPU breadcrumbs：每个 scheduler 是一个命令流（vk_breadcrumbs.h）。
    crumb_stream = Breadcrumbs::NewStream(
        instance, recording_threaded ? "game draws and composition" : "presentation");
    if (const size_t cut = PassCutBytes(); cut != 0) {
        std::printf("GPU: cut an open render pass every %zu KiB of recording "
                    "(GOW3_PASS_CUT_KB=0 disables)\n",
                    cut / 1024);
    }
#if TRACY_GPU_ENABLED
    profiler_scope = reinterpret_cast<tracy::VkCtxScope*>(std::malloc(sizeof(tracy::VkCtxScope)));
#endif
    AllocateWorkerCommandBuffers();
    priority_pending_ops_thread =
        std::jthread(std::bind_front(&Scheduler::PriorityPendingOpsThread, this));
}

Scheduler::~Scheduler() {
    if (recorder_thread.joinable()) {
        SyncRecording();
        recorder_thread.request_stop();
        recorder_cv.notify_all();
        recorder_thread.join();
    }
#if TRACY_GPU_ENABLED
    std::free(profiler_scope);
#endif
}

void Scheduler::BeginRendering(const RenderState& new_state) {
    if (is_rendering && render_state == new_state) {
        return;
    }
    // 只有按字节阈值切段（KickRecording）后、同一 state 的继续才用 LOAD 续接；普通重开仍按
    // 调用方的 is_clear/depth_clear/stencil_clear 契约，避免改变帧首清屏语义。
    const bool resume = resume_rendering && !is_rendering && render_state == new_state;
    resume_rendering = false;
    EndRendering();
    is_rendering = true;
    render_state = new_state;
    // 配对护栏：只有确实要录制 Begin 才递增；同 state 续接在上面的提前返回里已排除。
    render_pass_pairing.OnBegin();
    NoteEvent(EventBeginPass);
    const auto producer = ObserveProducer("BeginRendering");

    std::array<vk::RenderingAttachmentInfo, 8> color_attachments;
    for (u32 i = 0; i < render_state.num_color_attachments; ++i) {
        const auto& cb = render_state.color_attachments[i];
        color_attachments[i] = vk::RenderingAttachmentInfo{
            .imageView = cb.image_view,
            .imageLayout = cb.image_layout,
            .loadOp = cb.is_clear && !resume ? vk::AttachmentLoadOp::eClear
                                             : vk::AttachmentLoadOp::eLoad,
            .storeOp = vk::AttachmentStoreOp::eStore,
            .clearValue = vk::ClearValue{.color = vk::ClearColorValue{.uint32 = cb.clear_value}},
        };
    }

    const auto& db = render_state.depth_stencil_attachment;
    const vk::RenderingAttachmentInfo depth_attachment = {
        .imageView = db.image_view,
        .imageLayout = db.image_layout,
        .loadOp = db.depth_clear && !resume ? vk::AttachmentLoadOp::eClear
                                            : vk::AttachmentLoadOp::eLoad,
        .storeOp = vk::AttachmentStoreOp::eStore,
        .clearValue =
            vk::ClearValue{.depthStencil = vk::ClearDepthStencilValue{.depth = std::bit_cast<float>(
                                                                          db.clear_value[0])}},
    };
    const vk::RenderingAttachmentInfo stencil_attachment = {
        .imageView = db.image_view,
        .imageLayout = db.image_layout,
        .loadOp = db.stencil_clear && !resume ? vk::AttachmentLoadOp::eClear
                                              : vk::AttachmentLoadOp::eLoad,
        .storeOp = vk::AttachmentStoreOp::eStore,
        .clearValue = vk::ClearValue{.depthStencil =
                                         vk::ClearDepthStencilValue{.stencil = db.clear_value[1]}},
    };

    const vk::RenderingInfo rendering_info = {
        .renderArea =
            {
                .offset = {0, 0},
                .extent = {render_state.width, render_state.height},
            },
        .layerCount = render_state.num_layers,
        .colorAttachmentCount = render_state.num_color_attachments,
        .pColorAttachments = color_attachments.data(),
        .pDepthAttachment = db.has_depth ? &depth_attachment : nullptr,
        .pStencilAttachment = db.has_stencil ? &stencil_attachment : nullptr,
    };

    if (!recording_threaded) {
        ObserveRenderBegin(current_cmdbuf, "Scheduler::BeginRendering(immediate)");
        current_cmdbuf.beginRendering(rendering_info);
        return;
    }
    // The attachment infos live on this stack frame: the recorded closure keeps copies.
    Record([info = rendering_info, color_attachments, depth_attachment,
            stencil_attachment](vk::CommandBuffer cmdbuf) mutable {
        ObserveRenderBegin(cmdbuf, "Scheduler::BeginRendering");
        info.pColorAttachments = color_attachments.data();
        if (info.pDepthAttachment) {
            info.pDepthAttachment = &depth_attachment;
        }
        if (info.pStencilAttachment) {
            info.pStencilAttachment = &stencil_attachment;
        }
        cmdbuf.beginRendering(info);
    });
}

void Scheduler::EndRendering() {
    if (!is_rendering) {
        return;
    }
    // 配对护栏：深度为 0 却要 End，说明记录侧没有对应的 Begin（跨代错位：Begin 入队后、End
    // 入队前并发提交换代，End 落进下一代）。此时告警并放弃该命令，不让驱动解引用空 subpass；
    // 告警代表仍有状态错配，不是已修复。
    if (!render_pass_pairing.OnEnd()) {
        std::printf("GPU pairing guard: EndRendering without Begin discarded (caller=%p); "
                    "render pass state mismatch remains\n",
                    __builtin_return_address(0));
        std::fflush(stdout);
        NoteEvent(EventGuardReject);
        is_rendering = false;
        bytes_since_cut = 0;
        return;
    }
    is_rendering = false;
    bytes_since_cut = 0;
    NoteEvent(EventEndPass);
    Record([this](vk::CommandBuffer cmdbuf) {
        ObserveRenderEnd(cmdbuf, "Scheduler::EndRendering");
        cmdbuf.endRendering();
    });
}

const char* Scheduler::RecentEventName(u32 kind) {
    switch (kind) {
    case EventSubmit:
        return "submit";
    case EventBeginPass:
        return "begin-pass";
    case EventEndPass:
        return "end-pass";
    case EventGuardReject:
        return "guard-reject";
    case EventSwap:
        return "swap-generation";
    case EventDirectEnter:
        return "direct-enter";
    case EventDirectLeave:
        return "direct-leave";
    }
    return "?";
}

void Scheduler::NoteEvent(RecentEventKind kind, u64 value) {
    // 默认关闭：正常游玩零开销。需要抓稀发设备丢失时设 GOW3_SCHED_TRACE=1。
    static const bool enabled = [] {
        const char* env = std::getenv("GOW3_SCHED_TRACE");
        return env && env[0] == '1';
    }();
    if (!enabled) {
        return;
    }
    const u64 now_ms = u64(std::chrono::duration_cast<std::chrono::milliseconds>(
                               std::chrono::system_clock::now().time_since_epoch())
                               .count());
    std::scoped_lock lk{recent_mutex};
    RecentEvent& event = recent_events[recent_event_index % RecentEventCount];
    event.time_ms = now_ms;
    event.value = value;
    event.kind = kind;
    event.depth = render_pass_pairing.Depth();
    event.flags = (is_rendering ? 1u : 0u) | (direct_mode ? 2u : 0u);
    event.width = render_state.width;
    event.height = render_state.height;
    event.colors = render_state.num_color_attachments;
    ++recent_event_index;
}

void Scheduler::DumpWaitState(vk::Result result, u64 tick) {
    const u64 now_ms = u64(std::chrono::duration_cast<std::chrono::milliseconds>(
                               std::chrono::system_clock::now().time_since_epoch())
                               .count());
    std::scoped_lock lk{recent_mutex};
    std::printf("GPU wait state (%s): tick=%llu, GPU tick=%llu, pairing_depth=%u, "
                "is_rendering=%d, direct_mode=%d, queued_chunks=%zu, open=%ux%u colors=%u, "
                "events=%u\n",
                vk::to_string(result).c_str(), (unsigned long long)tick,
                (unsigned long long)work_semaphore.KnownGpuTick(), render_pass_pairing.Depth(),
                is_rendering ? 1 : 0, direct_mode ? 1 : 0,
                queued_chunks.load(std::memory_order_relaxed), render_state.width,
                render_state.height, render_state.num_color_attachments, recent_event_index);
    const u32 count = std::min<u32>(recent_event_index, RecentEventCount);
    if (count != 0) {
        const u32 first =
            recent_event_index > RecentEventCount ? recent_event_index - RecentEventCount : 0;
        const u64 base = recent_events[first % RecentEventCount].time_ms;
        for (u32 i = 0; i < count; ++i) {
            const RecentEvent& event = recent_events[(first + i) % RecentEventCount];
            std::printf("    %+.3f s %s value=%llu depth=%u%s%s %ux%u colors=%u\n",
                        double(event.time_ms - base) / 1000.0, RecentEventName(event.kind),
                        (unsigned long long)event.value, event.depth,
                        (event.flags & 1u) ? " rendering" : "",
                        (event.flags & 2u) ? " direct" : "", event.width, event.height,
                        event.colors);
        }
    }
    std::printf("  wall time %llu ms\n", (unsigned long long)now_ms);
    std::fflush(stdout);
}

void Scheduler::TraceDirectRecording(void* caller) {
    static const bool enabled = [] {
        const char* env = std::getenv("GOW3_RECORDER_TRACE");
        return env && env[0] == '1';
    }();
    if (!enabled) {
        return;
    }
    static std::mutex mutex;
    static std::unordered_map<void*, u64> callers;
    static u64 calls;
    std::scoped_lock lk{mutex};
    ++callers[caller];
    if (++calls % 2000) {
        return;
    }
    std::vector<std::pair<u64, void*>> top;
    for (const auto& [address, count] : callers) {
        top.emplace_back(count, address);
    }
    std::ranges::sort(top, std::greater{});
    for (size_t i = 0; i < std::min<size_t>(top.size(), 8); ++i) {
#ifdef _WIN32
        struct { const char* dli_fname; void* dli_fbase; } info{nullptr, nullptr};
#else
        Dl_info info{};
        dladdr(top[i].second, &info);
#endif
        std::printf("Recorder sync caller: %llu x %s+0x%lx\n",
                    static_cast<unsigned long long>(top[i].first),
                    info.dli_fname ? info.dli_fname : "?",
                    static_cast<unsigned long>(reinterpret_cast<uintptr_t>(top[i].second) -
                                               reinterpret_cast<uintptr_t>(info.dli_fbase)));
    }
    callers.clear();
}

std::unique_ptr<RecordChunk> Scheduler::AcquireChunk() {
    std::scoped_lock lk{recorder_mutex};
    if (free_chunks.empty()) {
        return std::make_unique<RecordChunk>();
    }
    auto chunk = std::move(free_chunks.back());
    free_chunks.pop_back();
    return chunk;
}

void Scheduler::SignalAfterHostCopies(std::function<void()> signal) {
    if (!IsRecordingDeferred()) {
        WaitHostCopies();
        signal();
        return;
    }
    Gow3Copy::FlushBatch();
    deferred_signals_issued.fetch_add(1, std::memory_order_relaxed);
    Record([signal = std::move(signal), done = deferred_signals_done](vk::CommandBuffer) mutable {
        Gow3Copy::AfterCopies([signal = std::move(signal), done = std::move(done)] {
            signal();
            done->fetch_add(1, std::memory_order_release);
        });
    });
    KickRecording(true);
}

void Scheduler::WaitDeferredSignals() {
    const u64 issued = deferred_signals_issued.load(std::memory_order_relaxed);
    if (deferred_signals_done->load(std::memory_order_acquire) >= issued ||
        Gow3Toggle::Disabled(Gow3Toggle::OrderedGuestWrites)) {
        return;
    }
    Gow3Stats::WaitTimer timer{Gow3Stats::host_copies_wait_ns};
    KickRecording(true);
    while (deferred_signals_done->load(std::memory_order_acquire) < issued) {
        // Helps the copy threads the signals wait for.
        Gow3Copy::WaitAsync();
        std::this_thread::yield();
    }
}

void Scheduler::WaitHostCopies() {
    if (host_copies_done.load(std::memory_order_acquire) < host_copies_issued) {
        Gow3Stats::WaitTimer timer{Gow3Stats::host_copies_wait_ns};
        Gow3Stats::host_copy_waits.fetch_add(1, std::memory_order_relaxed);
        KickRecording(true);
        while (host_copies_done.load(std::memory_order_acquire) < host_copies_issued) {
            std::this_thread::yield();
        }
    }
    Gow3Stats::WaitTimer timer{Gow3Stats::copy_threads_wait_ns};
    Gow3Copy::WaitAsync();
}

void Scheduler::KickRecording(bool force) {
    if (!recording_threaded) {
        return;
    }
    std::scoped_lock kick_lock{kick_mutex};
    const auto producer = ObserveProducer("KickRecording");
    // KickRecording 不更换 command buffer 代（换代只在 SubmitExecution 之后的
    // AllocateWorkerCommandBuffers）。在这里收口会让每个 draw/dispatch 都触发一次结束/重开；
    // Adreno 为 tiled 架构，每次收口等于 GMEM resolve + reload，实测把 60 FPS 压到 30 FPS。
    // 跨代错位由配对护栏拦截，提交前收口在 SubmitExecution 完成。
    // Callers kick where nobody holds the raw command buffer: deferral resumes.
    const bool was_direct = direct_mode;
    direct_mode = false;
    if (was_direct) {
        NoteEvent(EventDirectLeave);
        // 对齐 Bloodborne 的 LeaveDirectSegment：离开 direct 段时收口 open pass，下一次同
        // state 的 BeginRendering 用 LOAD 续接。
        if (is_rendering) {
            EndRendering();
            resume_rendering = true;
        }
        last_chunk_size = CurrentChunk().Size();
    } else {
        // gow3: 不逐 draw 收口（那等于每绘制一次 GMEM resolve + reload）。只按字节阈值切段：
        // 累计录制量达到 GOW3_PASS_CUT_KB 时收口一次 open pass，并让下一次同 state 的
        // BeginRendering 用 LOAD 续接。阈值 0（默认）时这里只是两次比较。
        const size_t chunk_size = CurrentChunk().Size();
        bytes_since_cut +=
            chunk_size >= last_chunk_size ? chunk_size - last_chunk_size : chunk_size;
        last_chunk_size = chunk_size;
        if (const size_t cut = PassCutBytes();
            cut != 0 && is_rendering && bytes_since_cut >= cut) {
            EndRendering();
            resume_rendering = true;
            last_chunk_size = CurrentChunk().Size();
        }
    }
    // Batches of tens of KiB keep the queue handoff cheap relative to the work it carries.
    if (!force && full_chunks.empty() && CurrentChunk().Size() < 32 * 1024) {
        return;
    }
    if (full_chunks.empty() && CurrentChunk().Empty()) {
        return;
    }
    // Acquire the replacement before handing over the current chunk. This mirrors Bloodborne's
    // issue #100 fix: record_chunk remains valid throughout the handover, including if another
    // recording path is entered while the queue lock is held.
    std::unique_ptr<RecordChunk> next_record =
        CurrentChunk().Empty() ? nullptr : AcquireChunk();
    bool wake;
    {
        std::scoped_lock lk{recorder_mutex};
        for (auto& chunk : full_chunks) {
            recorder_queue.push_back(std::move(chunk));
        }
        if (next_record) {
            record_chunk.swap(next_record);
            recorder_queue.push_back(std::move(next_record));
        }
        wake = recorder_sleeping;
        queued_chunks.store(recorder_queue.size(), std::memory_order_release);
    }
    full_chunks.clear();
    // A busy recorder picks the new chunks up by itself: waking it is a syscall per draw.
    if (wake) {
        recorder_cv.notify_one();
    }
}

void Scheduler::SyncRecording() {
    if (!recording_threaded) {
        return;
    }
    KickRecording(true);
    Gow3Stats::WaitTimer timer{Gow3Stats::sync_recording_ns};
    std::unique_lock lk{recorder_mutex};
    recorder_idle_cv.wait(lk, [this] { return recorder_queue.empty() && !recorder_busy; });
}

void Scheduler::RecorderThread(std::stop_token stoken) {
    Common::SetCurrentThreadName("gow3:VkRecorder");
    while (true) {
        // Spin briefly before sleeping: the next chunk usually follows within microseconds,
        // and a sleeping recorder costs the GPU thread a wake-up syscall per kick. With few
        // hardware threads (Steam Deck: 8) the spin would take time from guest threads.
        static const auto spin_time = std::chrono::microseconds(
            Gow3Threads::Available() >= 12 ? 200 : 20);
        const auto spin_until = std::chrono::steady_clock::now() + spin_time;
        for (u32 spins = 1; queued_chunks.load(std::memory_order_acquire) == 0; ++spins) {
            COMMON_CPU_RELAX();
            // The clock is read every 256 pauses, not per iteration.
            if (!(spins & 255) &&
                (stoken.stop_requested() || std::chrono::steady_clock::now() >= spin_until)) {
                break;
            }
        }
        std::unique_ptr<RecordChunk> chunk;
        {
            std::unique_lock lk{recorder_mutex};
            recorder_sleeping = true;
            recorder_cv.wait(lk, stoken, [this] { return !recorder_queue.empty(); });
            recorder_sleeping = false;
            if (recorder_queue.empty()) {
                return; // stop requested
            }
            chunk = std::move(recorder_queue.front());
            recorder_queue.pop_front();
            queued_chunks.store(recorder_queue.size(), std::memory_order_release);
            recorder_busy = true;
        }
        // current_cmdbuf only changes after SyncRecording(), which waits for this thread.
        chunk->Execute(current_cmdbuf);
        {
            std::scoped_lock lk{recorder_mutex};
            free_chunks.push_back(std::move(chunk));
            recorder_busy = false;
            if (recorder_queue.empty()) {
                recorder_idle_cv.notify_all();
            }
        }
    }
}

void Scheduler::Flush(SubmitInfo& info) {
    // When flushing, we only send data to the driver; no waiting is necessary.
    SubmitExecution(info);
}

void Scheduler::Flush() {
    SubmitInfo info{};
    Flush(info);
}

void Scheduler::Finish(std::source_location caller) {
    // When finishing, we need to wait for the submission to have executed on the device.
    const u64 presubmit_tick = CurrentTick();
    const bool diagnose = WaitDiagnostics::Enabled();
    const auto start = diagnose ? std::chrono::steady_clock::now() : std::chrono::steady_clock::time_point{};
    SubmitInfo info{};
    SubmitExecution(info);
    const u64 submit_ns = diagnose ? std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now() - start).count() : 0;
    Wait(presubmit_tick, caller, submit_ns);
}

void Scheduler::WaitSubmitted(std::source_location caller) {
    const u64 tick = CurrentTick();
    if (tick > 1) {
        Wait(tick - 1, caller);
    }
}

void Scheduler::Wait(u64 tick, std::source_location caller, u64 submit_ns) {
    const bool diagnose = WaitDiagnostics::Enabled();
    auto start = diagnose ? std::chrono::steady_clock::now() : std::chrono::steady_clock::time_point{};
    if (tick >= work_semaphore.CurrentTick()) {
        // Make sure we are not waiting for the current tick without signalling
        SubmitInfo info{};
        Flush(info);
    }
    Gow3Stats::WaitTimer timer{Gow3Stats::tick_wait_ns};
    const auto submitted = diagnose ? std::chrono::steady_clock::now() : start;
    // 卡死检测：等 5 秒还没到说明 GPU 可能挂死，先打印等待状态、最近调度事件和 breadcrumbs
    // 报告再继续等。报告只打一次（ReportStuck 内部保证），继续等的部分保持原有阻塞语义。
    const vk::Result wait_result = work_semaphore.WaitForResult(tick, 5'000'000'000ull);
    if (wait_result != vk::Result::eSuccess) {
        DumpWaitState(wait_result, tick);
        if (wait_result == vk::Result::eErrorDeviceLost) {
            Breadcrumbs::ReportDeviceLost("waiting for a submission");
            ASSERT_MSG(false, "Device lost while waiting for a submission");
        }
#ifndef _WIN32
        char thread[16] = {};
        pthread_getname_np(pthread_self(), thread, sizeof(thread));
        std::printf("GPU wait stalled 5 s on %s: tick %llu, GPU at %llu\n", thread,
                    static_cast<unsigned long long>(tick),
                    static_cast<unsigned long long>(work_semaphore.KnownGpuTick()));
#else
        std::printf("GPU wait stalled 5 s: tick %llu, GPU at %llu\n",
                    static_cast<unsigned long long>(tick),
                    static_cast<unsigned long long>(work_semaphore.KnownGpuTick()));
#endif
        Breadcrumbs::ReportStuck("a wait stalled 5 s");
        work_semaphore.Wait(tick);
    }
    if (diagnose) {
        const auto completed = std::chrono::steady_clock::now();
        WaitDiagnostics::Get().Add(caller.file_name(), caller.line(),
            submit_ns + std::chrono::duration_cast<std::chrono::nanoseconds>(submitted - start).count(),
            std::chrono::duration_cast<std::chrono::nanoseconds>(completed - submitted).count());
    }
}

void Scheduler::PopPendingOperations() {
    if (num_pending_ops.load(std::memory_order_acquire) == 0) {
        return; // every draw comes here
    }
    std::unique_lock lk(pending_ops_mutex);
    // gow3: this runs on every draw and dispatch. Querying the timeline semaphore is an
    // ioctl, so it is skipped when nothing waits and done once per 32 calls (~0.3 ms; reading
    // the clock per draw instead was itself a hot spot).
    if (pending_ops.empty()) {
        return;
    }
    if (!work_semaphore.IsFree(pending_ops.front().gpu_tick)) {
        if ((++pending_polls & 31) != 0 && !Gow3Toggle::Disabled(Gow3Toggle::PendingPollLimit)) {
            return;
        }
        work_semaphore.Refresh();
    }
    while (!pending_ops.empty() && work_semaphore.IsFree(pending_ops.front().gpu_tick)) {
        pending_ops.front().callback();
        pending_ops.pop();
        num_pending_ops.fetch_sub(1, std::memory_order_release);
    }
}

void Scheduler::AllocateWorkerCommandBuffers() {
    const vk::CommandBufferBeginInfo begin_info = {
        .flags = vk::CommandBufferUsageFlagBits::eOneTimeSubmit,
    };

    // 换代：新 command buffer 代的渲染深度从 0 开始。上一代若有未闭合范围，SubmitExecution
    // 已先 EndRendering 收口；换代重置使"Begin 在旧代、孤立 End 在新代"的错位被 EndRendering
    // 护栏拦截（深度 0 时告警并跳过）。
    render_pass_pairing.OnSwap();
    NoteEvent(EventSwap);
    bytes_since_cut = 0;
    last_chunk_size = 0;

    current_cmdbuf = command_pool.Commit();
    ObserveNewSequence(current_cmdbuf);
    Check(current_cmdbuf.begin(begin_info));

    // Invalidate dynamic state so it gets applied to the new command buffer.
    dynamic_state.Invalidate();

#if TRACY_GPU_ENABLED
    auto* profiler_ctx = instance.GetProfilerContext();
    if (profiler_ctx) {
        static const auto scope_loc =
            GPU_SCOPE_LOCATION("Guest Frame", MarkersPalette::GpuMarkerColor);
        new (profiler_scope) tracy::VkCtxScope{profiler_ctx, &scope_loc, current_cmdbuf, true};
    }
#endif
}

void Scheduler::SubmitExecution(SubmitInfo& info) {
    std::scoped_lock lk{submit_mutex};
    const auto producer = ObserveProducer("SubmitExecution");
    const u64 signal_value = work_semaphore.NextTick();
    NoteEvent(EventSubmit, signal_value);
    static const bool bounded_submit =
        Gow3Mobile::Android() && !Gow3Mobile::Enabled("GOW3_VK_POLL_EACH_SUBMIT", false);
    if (bounded_submit) {
        if (const u64 tick = Gow3Mobile::BoundedTick(signal_value)) {
            work_semaphore.Wait(tick);
        }
    }

#if TRACY_GPU_ENABLED
    auto* profiler_ctx = instance.GetProfilerContext();
    if (profiler_ctx) {
        profiler_scope->~VkCtxScope();
        TracyVkCollect(profiler_ctx, current_cmdbuf);
    }
#endif

    if (on_submit) {
        on_submit(info);
    }

    EndRendering();
    // 提交会结束这一代的渲染范围；下一次 Begin 不从上一代续接（避免把上一帧的附件内容带进来）。
    resume_rendering = false;
    if (auto* profiler = GpuProfiler::Get(); profiler && profiler->Records(this)) {
        // Until the next submission's first timestamp: mostly the GPU waiting for it.
        profiler->Mark(0x5B317ull, [] { return std::string{"(between submissions: GPU idle)"}; });
    }
    SyncRecording();
    // 提交前收口保证：SyncRecording 之后若仍有未配对的 Begin（并发下 Begin 闭包已入队而 End 未
    // 记录，或被护栏跳过），先强制补一个 End 再收口，避免带着未闭合的渲染范围提交；换代时深度
    // 归 0 使下一次 Begin 重新计数。
    if (render_pass_pairing.Depth() != 0) {
        std::printf("GPU pairing guard: submit with open render pass closed (depth=%u, caller=%p); "
                    "render pass state mismatch remains\n",
                    render_pass_pairing.Depth(), __builtin_return_address(0));
        Record([this](vk::CommandBuffer cmdbuf) {
            ObserveRenderEnd(cmdbuf, "Scheduler::SubmitExecution(force close)");
            cmdbuf.endRendering();
        });
        SyncRecording();
        render_pass_pairing.OnSwap();
    }
    // Guest memory copies into staging read by this submission (copy threads).
    WaitHostCopies();
    ObserveSubmit(current_cmdbuf);
    Check(current_cmdbuf.end());

    const vk::Semaphore timeline = work_semaphore.Handle();
    info.AddSignal(timeline, signal_value);

    const vk::TimelineSemaphoreSubmitInfo timeline_si = {
        .waitSemaphoreValueCount = info.num_wait_semas,
        .pWaitSemaphoreValues = info.wait_ticks.data(),
        .signalSemaphoreValueCount = info.num_signal_semas,
        .pSignalSemaphoreValues = info.signal_ticks.data(),
    };

    const vk::SubmitInfo submit_info = {
        .pNext = &timeline_si,
        .waitSemaphoreCount = info.num_wait_semas,
        .pWaitSemaphores = info.wait_semas.data(),
        .pWaitDstStageMask = info.wait_stages.data(),
        .commandBufferCount = 1U,
        .pCommandBuffers = &current_cmdbuf,
        .signalSemaphoreCount = info.num_signal_semas,
        .pSignalSemaphores = info.signal_semas.data(),
    };

    ImGui::Core::TextureManager::Submit();
    auto submit_result = instance.GetGraphicsQueue().submit(submit_info, info.fence);
    if (submit_result == vk::Result::eErrorDeviceLost) {
        Breadcrumbs::ReportDeviceLost("submit");
    }
    ASSERT_MSG(submit_result != vk::Result::eErrorDeviceLost, "Device lost during submit");

    if (!bounded_submit) work_semaphore.Refresh();
    AllocateWorkerCommandBuffers();

    // Apply pending operations
    PopPendingOperations();
}

void Scheduler::PriorityPendingOpsThread(std::stop_token stoken) {
    Common::SetCurrentThreadName("shadPS4:GpuSchedPriorityPendingOpsRunner");

    while (!stoken.stop_requested()) {
        PendingOp op;
        {
            std::unique_lock lk(priority_pending_ops_mutex);
            priority_pending_ops_cv.wait(lk, stoken,
                                         [this] { return !priority_pending_ops.empty(); });
            if (stoken.stop_requested()) {
                break;
            }

            op = std::move(priority_pending_ops.front());
            priority_pending_ops.pop();
        }

        work_semaphore.Wait(op.gpu_tick);
        if (stoken.stop_requested()) {
            break;
        }

        op.callback();
    }
}

void DynamicState::Commit(const Instance& instance, const vk::CommandBuffer& cmdbuf) {
    // A null command buffer only updates the dirty flags, exactly as recording would.
    CommitWith(instance.IsDepthBoundsSupported(), instance.IsDynamicColorWriteMaskSupported(),
               instance.IsAttachmentFeedbackLoopLayoutSupported(), [&](auto&& command) {
                   if (cmdbuf) {
                       command(cmdbuf);
                   }
               });
}

} // namespace Vulkan
