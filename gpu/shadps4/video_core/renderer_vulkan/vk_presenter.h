// SPDX-FileCopyrightText: Copyright 2024 shadPS4 Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <atomic>
#include <deque>

#include <condition_variable>

#include "core/libraries/videoout/buffer.h"
#include "video_core/renderer_vulkan/host_passes/fsr_pass.h"
#include "video_core/renderer_vulkan/host_passes/pp_pass.h"
#include "video_core/renderer_vulkan/vk_instance.h"
#include "video_core/renderer_vulkan/vk_runtime.h"
#include "video_core/renderer_vulkan/vk_scheduler.h"
#include "video_core/renderer_vulkan/vk_swapchain.h"
#ifdef __ANDROID__
#include "gow3_lsfg_state.h" // 帧生成运行期状态（change add-native-frame-generation）
#endif
#include "video_core/texture_cache/texture_cache.h"

namespace Frontend {
class WindowSDL;
}

namespace AmdGpu {
struct Liverpool;
}

namespace Vulkan {

struct Frame {
    u32 width;
    u32 height;
    VmaAllocation allocation;
    vk::Image image;
    vk::ImageView image_view;
    vk::Fence present_done;
    vk::Semaphore ready_semaphore;
    u64 ready_tick;
    bool is_hdr{false};
    u8 id{};

};

enum SchedulerType {
    Draw,
    Present,
    CpuFlip,
};

class Rasterizer;

#ifdef __ANDROID__
class Lsfg; // 帧生成引擎宿主（gow3_lsfg.h）
#endif

class Presenter {
public:
    Presenter(Frontend::WindowSDL& window, AmdGpu::Liverpool* liverpool);
    ~Presenter();

    HostPasses::PostProcessingPass::Settings& GetPPSettingsRef() {
        return pp_settings;
    }

    HostPasses::FsrPass::Settings& GetFsrSettingsRef() {
        return fsr_settings;
    }

    Frontend::WindowSDL& GetWindow() const {
        return window;
    }

    Rasterizer& GetRasterizer() const {
        return *rasterizer.get();
    }

    bool IsHDRSupported() const {
        return swapchain.HasHDR();
    }

    void SetHDR(bool enable) {
        if (!IsHDRSupported()) {
            return;
        }
        swapchain.SetHDR(enable);
        pp_settings.hdr = enable ? 1 : 0;
    }

    VideoCore::Image& RegisterVideoOutSurface(
        const Libraries::VideoOut::BufferAttributeGroup& attribute, VAddr cpu_address) {
        vo_buffers_addr.emplace_back(cpu_address);
        auto desc = VideoCore::TextureCache::ImageDesc{attribute, cpu_address};
        const auto image_id = texture_cache.FindImage(desc);
        auto& image = texture_cache.GetImage(image_id);
        image.usage.vo_surface = 1u;
        return image;
    }

    bool IsVideoOutSurface(const AmdGpu::ColorBuffer& color_buffer) const;

    Frame* PrepareFrame(const Libraries::VideoOut::BufferAttributeGroup& attribute,
                        VAddr cpu_address);

    Frame* PrepareBlankFrame(bool present_thread);

    void Present(Frame* frame, bool is_reusing_frame = false, bool is_game_frame = true);
    /// gow3: a black frame with the loading screen (Gow3Overlay::SetLoading) only.
    void PresentLoadingFrame();
    Frame* PrepareLastFrame();

private:
    Frame* GetRenderFrame();

    void RecreateFrame(Frame* frame, u32 width, u32 height);

    // ---- 帧生成（change add-native-frame-generation，BB 同构）----
    void PollLsfgState();
    void ApplyLsfgState(const Gow3LsfgState::State& next);
    void ApplyLsfgOutputMode();
    void ApplyFramesCap();
    bool PresentWithLsfg(Frame* frame);

    void SetExpectedGameSize(s32 width, s32 height);

private:
    float expected_ratio{1920.0 / 1080.0f};
    u32 expected_frame_width{1920};
    u32 expected_frame_height{1080};

    Frontend::WindowSDL& window;
    Instance instance;
    HostPasses::FsrPass fsr_pass;
    bool fsr_created = false;
    HostPasses::FsrPass::Settings fsr_settings{};
    HostPasses::PostProcessingPass::Settings pp_settings{};
    HostPasses::PostProcessingPass pp_pass;
    AmdGpu::Liverpool* liverpool;
    Scheduler draw_scheduler;
    std::deque<u64> recent_frame_ticks; ///< gow3: GOW3_FRAMES_AHEAD bound (PrepareFrame)
    Scheduler present_scheduler;
    Scheduler flip_scheduler;
#ifdef __ANDROID__
    // 帧生成：状态 watcher + 引擎实例（倍率变化/开关都是重建 Lsfg，BB 同语义）。
    // 声明顺序必须在 swapchain 之前：swapchain 构造要读 lsfg->Multiplier()
    // （C++ 按声明顺序构造成员，反了就是读未构造存储的 UB）。
    Gow3LsfgState::State lsfg_requested{Gow3LsfgState::Initial()};
    Gow3LsfgState::Watcher lsfg_watch{lsfg_requested};
    std::unique_ptr<Lsfg> lsfg;
    std::atomic<u32> frames_cap{0}; ///< 0 = 无上限（直通/引擎未启用）
    // HUD 遥测文件（state 路径 + ".stats"）：每秒一次原子写；off 时清零一次。
    // lsfg_stats_active 标记本会话曾写过活数据；lsfg_stats_cleared 保证引擎不活跃时
    // 每个会话只清一次（跨会话残留的 framegen.stats 也会在首次呈现周期被清除）。
    std::chrono::steady_clock::time_point lsfg_stats_ts{};
    bool lsfg_stats_active{false};
    bool lsfg_stats_cleared{false};
    void WriteLsfgStats();
#endif
    Swapchain swapchain;
    Runtime runtime;
    std::unique_ptr<Rasterizer> rasterizer;
    VideoCore::TextureCache& texture_cache;
    vk::UniqueCommandPool command_pool;
    std::vector<Frame> present_frames;
    std::queue<Frame*> free_queue;
    Frame* last_submit_frame;
    std::mutex free_mutex;
    std::condition_variable free_cv;
    std::condition_variable_any frame_cv;
    std::vector<VAddr> vo_buffers_addr;
};

} // namespace Vulkan
