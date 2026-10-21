// SPDX-License-Identifier: GPL-2.0-or-later
// gow3port: Bionic 的「swapchain」：N 个由 AHardwareBuffer 支撑的 VkImage，由 Android app 显示。
//
// 游戏进程里没有 VkSurfaceKHR / VkSwapchainKHR（直连加载的 Turnip 没有 WSI，进程也没有
// Surface）。presenter 的接口不变（acquire -> render -> present）；在它背后：
//  - 缓冲区在这里分配（R8G8B8A8_UNORM、GPU_COLOR_OUTPUT | GPU_SAMPLED_IMAGE |
//    COMPOSER_OVERLAY），用 VK_ANDROID_external_memory_android_hardware_buffer 导入，
//    并一次性发给 app（gow3_present_client.h）；
//  - AcquireNextImage 取一个空闲缓冲区（全部在 app 手里时等 RELEASE）并把它的 release
//    fence 转成 presenter 的提交等待的信号量；
//  - Present 把 present-ready 信号量导出为 sync file（VK_KHR_external_semaphore_fd）随帧
//    发出去：即 app 的 acquire fence。
// 最多 image_count - 1 帧在 app 手里（外加正在发送的那一帧）。
// 节拍由 app 决定：GOW3_VSYNC=1（gow3port.ini vsync=1；HELLO 的 GOW3_PRESENT_FLAG_FIFO）
// 时每帧至少显示一个刷新周期、按序，并扣住未显示帧的 RELEASE，于是 AcquireNextImage 会阻塞，
// 游戏最多以刷新率运行；否则最新帧获胜、其余被跳过。两种模式本类都只等 RELEASE，与模式无关。
// 直通默认三个缓冲；游戏进程内帧生成开启时按倍率扩展并强制 FIFO。
#pragma once

#ifdef __ANDROID__
#include <deque>
#include <vector>
#include "gow3_present_client.h"
#include "video_core/renderer_vulkan/framegen_policy.h"
#include "common/types.h"
#include "video_core/renderer_vulkan/vk_common.h"

struct AHardwareBuffer;

namespace Frontend {
class WindowSDL;
}

namespace Vulkan {

class Instance;

class Swapchain {
public:
    /// `lsfg_multiplier`：帧生成倍率（2–4，0=关）。≥2 时缓冲数 = 倍率+3、强制 FIFO
    /// （change add-native-frame-generation，BB 同语义）。
    explicit Swapchain(const Instance& instance, const Frontend::WindowSDL& window,
                       u32 lsfg_multiplier = 0);
    ~Swapchain();

    /// 本 run 里 swapchain 可能有的最多缓冲数。
    static u32 MaxImageCount();

    /// Creates (or recreates) the buffers with a given size.
    void Create(u32 width, u32 height);
    void Recreate(u32 width, u32 height);

    /// Takes the next free buffer; false when the buffers must be recreated (0x0 size).
    bool AcquireNextImage();
    using Reservation = Framegen::Reservation;
    bool TryReserve(Reservation& token);
    Reservation CurrentReservation() const;
    bool Select(Reservation token);
    void Cancel(Reservation token);
    /// Hands the current buffer to the app.
    bool Present();

    u32 CurrentIndex() const { return image_index; }
    void SelectImage(u32 index) { image_index = index; }
    u32 Generation() const { return generation; }

    vk::Image Image() const { return buffers[image_index].image; }
    vk::ImageView ImageView() const { return buffers[image_index].view; }
    vk::SurfaceFormatKHR GetSurfaceFormat() const { return surface_format; }
    bool IsPresentable() const { return !zero_extent && !buffers.empty(); }
    u32 GetWidth() const { return width; }
    u32 GetHeight() const { return height; }
    u32 GetImageCount() const { return image_count; }
    u32 GetFrameIndex() const { return image_index; }
    vk::Extent2D GetExtent() const { return extent; }

    /// The semaphore the frame's submission waits on; null when the buffer needs no wait.
    [[nodiscard]] vk::Semaphore GetImageAcquiredSemaphore() const {
        return !buffers.empty() && buffers[image_index].acquire_imported ? image_acquired[image_index]
                                                                          : vk::Semaphore{};
    }
    [[nodiscard]] vk::Semaphore GetPresentReadySemaphore() const {
        return present_ready[image_index];
    }

    // No HDR through AHardwareBuffers (RGBA8 only).
    bool HasHDR() const { return false; }
    void SetHDR(bool) {}
    bool GetHDR() const { return false; }

    /// The layout and queue-family ownership the image has when handed to the app.
    vk::ImageLayout PresentLayout() const { return vk::ImageLayout::eGeneral; }
    /// {src, dst} of the barrier that takes the image back from the app / gives it to the app.
    std::pair<u32, u32> AcquireQueues() const;
    std::pair<u32, u32> ReleaseQueues() const;
    /// 未验证驱动保留 GPU 完成等待；显式异步只要求 signal 及依赖已提交。
    bool NeedsSubmittedBeforePresent() const { return !async_present; }

    /// gow3: 桌面 swapchain 由 in-game 菜单的 vsync 设置驱动重建；Bionic 下节拍在 app 侧
    /// （GOW3_VSYNC），这里只保留接口兼容，不重建。
    void UpdateVsync() {}

    /// 帧生成开关/倍率变化：强制 FIFO（MODE 消息）并按新缓冲数重建（RESET+新缓冲集）。
    /// multiplier=0 关闭：回到直通缓冲数与 vsync 语义。
    void SetFrameGeneration(u32 lsfg_multiplier);

private:
    struct Buffer {
        AHardwareBuffer* ahb{};
        vk::Image image{};
        vk::DeviceMemory memory{};
        vk::ImageView view{};
        int release_fd{-1}; ///< the app's release fence of the last frame, until it is waited on
        bool acquire_imported{false}; ///< release_fd was imported into image_acquired[index]
        Framegen::BufferLease lease;
    };

    void Destroy();
    void CreateBuffer(u32 index);
    void HandleRelease(Gow3Present::Release& release);
    void PeerGone() const;
    int ExportPresentReady();
    bool ImportAcquire(u32 index, bool nonblocking = false);
    void DrainReleases();

private:
    const Instance& instance;
    const Frontend::WindowSDL& window;
    Gow3Present::Client client;
    vk::SurfaceFormatKHR surface_format{vk::Format::eR8G8B8A8Unorm, vk::ColorSpaceKHR::eSrgbNonlinear};
    vk::Extent2D extent{};
    std::vector<Buffer> buffers;
    std::vector<vk::Semaphore> image_acquired;
    std::vector<vk::Semaphore> present_ready;
    std::deque<u32> free_queue; ///< buffers the game may render into, in release order
    u32 width = 0;
    u32 height = 0;
    u32 image_count = 3;
    u32 image_index = 0;
    u32 generation = 0;
    u64 frame_counter = 0;
    bool zero_extent = false;
    bool needs_recreation = true;
    bool sync_import_ok = true; ///< importing sync files as semaphores works (else poll them)
    bool async_present = Framegen::UseAsync(Framegen::SyncModeFromEnv("GOW3_AHB_SYNC"));
};

} // namespace Vulkan
#endif // __ANDROID__
