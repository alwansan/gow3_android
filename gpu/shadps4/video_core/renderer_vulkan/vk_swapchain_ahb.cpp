// SPDX-License-Identifier: GPL-2.0-or-later
// gow3port: see vk_swapchain_ahb.h.
#ifdef __ANDROID__
#include <android/hardware_buffer.h>
#include <poll.h>
#include <unistd.h>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include "common/assert.h"
#include "common/logging/log.h"
#include "imgui/renderer/imgui_core.h"
#include "sdl_window.h"
#include "video_core/renderer_vulkan/vk_instance.h"
#include "video_core/renderer_vulkan/vk_swapchain_ahb.h"
#include "video_core/renderer_vulkan/sync_file_poll.h"

namespace Vulkan {

namespace {

constexpr u32 AhbFormat = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
constexpr u64 AhbUsage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT |
                         AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                         AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;

/// GOW3_PRESENT_BUFFERS: the number of buffers (3 by default: one shown, one pending, one rendered).
/// gow3port: 无 Lossless Scaling（LSFG），缓冲数固定 3 或环境变量指定。
u32 BufferCount(u32 lsfg_multiplier = 0) {
    // 帧生成：倍率+3（BB 同值——一个在显示、一个待提交、倍率-1 个排队加真实的余量）。
    if (lsfg_multiplier >= 2) {
        return lsfg_multiplier + 3;
    }
    if (const char* value = std::getenv("GOW3_PRESENT_BUFFERS")) {
        const long count = std::strtol(value, nullptr, 10);
        if (count >= 2 && count <= static_cast<long>(8)) {
            return static_cast<u32>(count);
        }
    }
    return 3;
}

} // namespace

u32 Swapchain::MaxImageCount() {
    return BufferCount();
}

Swapchain::Swapchain(const Instance& instance_, const Frontend::WindowSDL& window_,
                     u32 lsfg_multiplier)
    : instance{instance_}, window{window_} {
    image_count = BufferCount(lsfg_multiplier);
    const auto sync_mode = Framegen::SyncModeFromEnv("GOW3_AHB_SYNC");
    // Turnip 交给 sync-fd 传递完成依赖；complete 可回退逐帧 CPU 等待。
    async_present = Framegen::UseAsync(sync_mode, instance.GetDriverID() == vk::DriverId::eMesaTurnip);
    LOG_INFO(Render_Vulkan, "AHB sync: {} ({})", async_present ? "async" : "complete",
             sync_mode != Framegen::SyncMode::Auto ? "explicit override" :
                 async_present ? "Turnip sync-fd policy" : "conservative driver policy");
    const vk::PhysicalDeviceExternalSemaphoreInfo semaphore_info{
        .handleType = vk::ExternalSemaphoreHandleTypeFlagBits::eSyncFd,
    };
    const auto semaphore_props = instance.GetPhysicalDevice().getExternalSemaphoreProperties(semaphore_info);
    ASSERT_MSG(
        (semaphore_props.externalSemaphoreFeatures & vk::ExternalSemaphoreFeatureFlagBits::eExportable) &&
            (semaphore_props.externalSemaphoreFeatures & vk::ExternalSemaphoreFeatureFlagBits::eImportable),
        "The Vulkan driver cannot export and import sync-file semaphores (SYNC_FD): the AHB "
        "swapchain cannot synchronize with the app");
    if (Gow3Present::Client::Configured()) {
        if (!client.Connect(window.GetWidth(), window.GetHeight(), AhbFormat, image_count,
                            Gow3Present::Client::VsyncRequested())) {
            std::printf("Present: no connection to the app: frames are rendered but not shown\n");
        }
    } else {
        std::printf("Present: GOW3_PRESENT_SOCKET is not set: frames are rendered but not shown\n");
    }
    Create(window.GetWidth(), window.GetHeight());
    ImGui::Core::Initialize(instance, window, image_count, surface_format.format);
}

Swapchain::~Swapchain() {
    Destroy();
    client.Disconnect();
}

std::pair<u32, u32> Swapchain::AcquireQueues() const {
    return {VK_QUEUE_FAMILY_FOREIGN_EXT, instance.GetGraphicsQueueFamilyIndex()};
}

std::pair<u32, u32> Swapchain::ReleaseQueues() const {
    return {instance.GetGraphicsQueueFamilyIndex(), VK_QUEUE_FAMILY_FOREIGN_EXT};
}

void Swapchain::Create(u32 width_, u32 height_) {
    width = width_;
    height = height_;
    needs_recreation = false;
    // A 0x0 output (no screen size) has no buffers; they come when the size is back.
    if (width == 0 || height == 0) {
        zero_extent = true;
        needs_recreation = true;
        return;
    }
    zero_extent = false;
    Destroy();
    extent = vk::Extent2D{width, height};
    ++generation;
    if (client.Connected()) {
        if (!client.BeginGeneration(generation, width, height, AhbFormat, image_count)) {
            PeerGone();
        }
    }
    const vk::Device device = instance.GetDevice();
    buffers.resize(image_count);
    image_acquired.resize(image_count);
    present_ready.resize(image_count);
    for (u32 i = 0; i < image_count; ++i) {
        CreateBuffer(i);
        auto [acquired_result, acquired] = device.createSemaphore({});
        ASSERT_MSG(acquired_result == vk::Result::eSuccess, "Failed to create semaphore: {}",
                   vk::to_string(acquired_result));
        image_acquired[i] = acquired;
        // The present-ready semaphore is exported as a sync file after its signal is submitted.
        const vk::ExportSemaphoreCreateInfo export_info{
            .handleTypes = vk::ExternalSemaphoreHandleTypeFlagBits::eSyncFd,
        };
        auto [ready_result, ready] = device.createSemaphore({.pNext = &export_info});
        ASSERT_MSG(ready_result == vk::Result::eSuccess, "Failed to create exportable semaphore: {}",
                   vk::to_string(ready_result));
        present_ready[i] = ready;
        SetObjectName(device, image_acquired[i], "AHB swapchain image_acquired {}", i);
        SetObjectName(device, present_ready[i], "AHB swapchain present_ready {}", i);
        free_queue.push_back(i);
        if (client.Connected() &&
            !client.SendBuffer(generation, i, width, height, AhbFormat, buffers[i].ahb)) {
            PeerGone();
        }
    }
    image_index = 0;
    LOG_INFO(Render_Vulkan, "AHB swapchain: {} buffers {}x{} (generation {}, {})", image_count, width,
             height, generation, client.Connected() ? "app connected" : "no app");
}

void Swapchain::CreateBuffer(u32 index) {
    const vk::Device device = instance.GetDevice();
    Buffer& buffer = buffers[index];

    // 扩展没启用时 Vulkan-Hpp 的 dispatch 里这些入口是 nullptr（vkGetDeviceProcAddr 对未启用的
    // 扩展返回 NULL）：无条件调用会跳到地址 0，即 SIGSEGV at RIP 0。先查，和 vk_instance.cpp 的
    // pipeline-binary "callable" 检查同一道闸，报出可读的错误而不是空指针跳转。
    const auto& dispatch = VULKAN_HPP_DEFAULT_DISPATCHER;
    ASSERT_MSG(dispatch.vkGetAndroidHardwareBufferPropertiesANDROID,
               "VK_ANDROID_external_memory_android_hardware_buffer is not enabled on this device: "
               "the AHardwareBuffer swapchain cannot import the app's buffers");
    ASSERT_MSG(dispatch.vkImportSemaphoreFdKHR,
               "VK_KHR_external_semaphore_fd is not enabled on this device: the AHardwareBuffer "
               "swapchain cannot import the app's release fences");

    const AHardwareBuffer_Desc desc{
        .width = width,
        .height = height,
        .layers = 1,
        .format = AhbFormat,
        .usage = AhbUsage,
    };
    const int allocated = AHardwareBuffer_allocate(&desc, &buffer.ahb);
    ASSERT_MSG(allocated == 0 && buffer.ahb, "AHardwareBuffer_allocate {}x{} failed: {}", width,
               height, allocated);

    auto [props_result, props_chain] = device.getAndroidHardwareBufferPropertiesANDROID<
        vk::AndroidHardwareBufferPropertiesANDROID, vk::AndroidHardwareBufferFormatPropertiesANDROID>(
        *buffer.ahb);
    ASSERT_MSG(props_result == vk::Result::eSuccess,
               "vkGetAndroidHardwareBufferPropertiesANDROID failed: {}", vk::to_string(props_result));
    const auto& props = props_chain.get<vk::AndroidHardwareBufferPropertiesANDROID>();
    const auto& format_props = props_chain.get<vk::AndroidHardwareBufferFormatPropertiesANDROID>();
    ASSERT_MSG(format_props.format != vk::Format::eUndefined,
               "The driver maps the AHardwareBuffer to no Vulkan format (external format {:#x})",
               format_props.externalFormat);
    const auto required = vk::FormatFeatureFlagBits::eColorAttachment | vk::FormatFeatureFlagBits::eBlitDst;
    ASSERT_MSG((format_props.formatFeatures & required) == required,
               "The driver cannot render/blit into the AHardwareBuffer format {} (features {})",
               vk::to_string(format_props.format), vk::to_string(format_props.formatFeatures));
    surface_format.format = format_props.format;

    vk::ImageUsageFlags usage =
        vk::ImageUsageFlagBits::eColorAttachment | vk::ImageUsageFlagBits::eTransferDst;
    if (format_props.formatFeatures & vk::FormatFeatureFlagBits::eTransferSrc) {
        usage |= vk::ImageUsageFlagBits::eTransferSrc;
    }
    if (format_props.formatFeatures & vk::FormatFeatureFlagBits::eSampledImage) {
        usage |= vk::ImageUsageFlagBits::eSampled;
    }
    const vk::ExternalMemoryImageCreateInfo external_info{
        .handleTypes = vk::ExternalMemoryHandleTypeFlagBits::eAndroidHardwareBufferANDROID,
    };
    const vk::ImageCreateInfo image_info{
        .pNext = &external_info,
        .imageType = vk::ImageType::e2D,
        .format = format_props.format,
        .extent = {width, height, 1},
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = vk::SampleCountFlagBits::e1,
        .tiling = vk::ImageTiling::eOptimal,
        .usage = usage,
        .sharingMode = vk::SharingMode::eExclusive,
        .initialLayout = vk::ImageLayout::eUndefined,
    };
    auto [image_result, image] = device.createImage(image_info);
    ASSERT_MSG(image_result == vk::Result::eSuccess, "Failed to create the AHB image: {}",
               vk::to_string(image_result));
    buffer.image = image;

    const auto memory_properties = instance.GetPhysicalDevice().getMemoryProperties();
    u32 memory_type = ~0u;
    for (u32 i = 0; i < memory_properties.memoryTypeCount; ++i) {
        if (!(props.memoryTypeBits & (1u << i))) {
            continue;
        }
        if (memory_type == ~0u) {
            memory_type = i;
        }
        if (memory_properties.memoryTypes[i].propertyFlags & vk::MemoryPropertyFlagBits::eDeviceLocal) {
            memory_type = i;
            break;
        }
    }
    ASSERT_MSG(memory_type != ~0u, "No memory type for the AHardwareBuffer (bits {:#x})",
               props.memoryTypeBits);
    const vk::MemoryDedicatedAllocateInfo dedicated{.image = image};
    const vk::ImportAndroidHardwareBufferInfoANDROID import_info{
        .pNext = &dedicated,
        .buffer = buffer.ahb,
    };
    auto [memory_result, memory] = device.allocateMemory({
        .pNext = &import_info,
        .allocationSize = props.allocationSize,
        .memoryTypeIndex = memory_type,
    });
    ASSERT_MSG(memory_result == vk::Result::eSuccess, "Failed to import the AHardwareBuffer: {}",
               vk::to_string(memory_result));
    buffer.memory = memory;
    const auto bind_result = device.bindImageMemory(image, memory, 0);
    ASSERT_MSG(bind_result == vk::Result::eSuccess, "Failed to bind the AHB image: {}",
               vk::to_string(bind_result));

    auto [view_result, view] = device.createImageView({
        .image = image,
        .viewType = vk::ImageViewType::e2D,
        .format = format_props.format,
        .subresourceRange = {.aspectMask = vk::ImageAspectFlagBits::eColor,
                             .levelCount = 1,
                             .layerCount = 1},
    });
    ASSERT_MSG(view_result == vk::Result::eSuccess, "Failed to create the AHB image view: {}",
               vk::to_string(view_result));
    buffer.view = view;
    SetObjectName(device, image, "AHB swapchain image {}", index);
    SetObjectName(device, view, "AHB swapchain view {}", index);
}

void Swapchain::SetFrameGeneration(u32 lsfg_multiplier) {
    if (client.Connected() &&
        !client.SetFifo(Gow3Present::Client::VsyncRequested() || lsfg_multiplier >= 2)) {
        PeerGone();
    }
    const u32 count = BufferCount(lsfg_multiplier);
    if (count != image_count) {
        image_count = count;
        Recreate(width != 0 ? width : window.GetWidth(), height != 0 ? height : window.GetHeight());
    }
}

void Swapchain::Recreate(u32 width_, u32 height_) {
    LOG_DEBUG(Render_Vulkan, "Recreate the AHB swapchain: width={} height={}", width_, height_);
    Create(width_, height_);
}

void Swapchain::Destroy() {
    vk::Device device = instance.GetDevice();
    const auto wait_result = device.waitIdle();
    if (wait_result != vk::Result::eSuccess) {
        LOG_WARNING(Render_Vulkan, "Failed to wait for device to become idle: {}",
                    vk::to_string(wait_result));
    }
    for (Buffer& buffer : buffers) {
        device.destroyImageView(buffer.view);
        device.destroyImage(buffer.image);
        device.freeMemory(buffer.memory);
        // The app and the compositor keep the memory while they use it (reference counted).
        if (buffer.ahb) {
            AHardwareBuffer_release(buffer.ahb);
        }
        if (buffer.release_fd >= 0) {
            close(buffer.release_fd);
        }
    }
    buffers.clear();
    for (const auto sem : image_acquired) {
        device.destroySemaphore(sem);
    }
    for (const auto sem : present_ready) {
        device.destroySemaphore(sem);
    }
    image_acquired.clear();
    present_ready.clear();
    free_queue.clear();
}

void Swapchain::PeerGone() const {
    // The app is the game's screen: without it (closed, killed) the process ends, as when the
    // window of the glibc build is closed. Say it on stderr, not only through the logger: this is
    // the last thing the process prints, and stderr is what the launcher's log file captures.
    std::fprintf(stderr, "AHB: the app closed the frame connection; the game exits\n");
    std::fflush(stdout);
    std::_Exit(0);
}

void Swapchain::HandleRelease(Gow3Present::Release& release) {
    if (release.generation != generation || release.index >= buffers.size()) {
        if (release.fence_fd >= 0) {
            close(release.fence_fd); // a buffer of a set that is gone
        }
        return;
    }
    Buffer& buffer = buffers[release.index];
    if (!buffer.lease.Release()) {
        if (release.fence_fd >= 0) close(release.fence_fd);
        return;
    }
    if (buffer.release_fd >= 0) {
        close(buffer.release_fd);
    }
    buffer.release_fd = release.fence_fd;
    free_queue.push_back(release.index);
}

bool Swapchain::AcquireNextImage() {
    if (zero_extent || buffers.empty()) {
        needs_recreation = true;
        return false;
    }
    DrainReleases();
    Gow3Present::Release release;
    // 真实帧保留既有阻塞获取语义。
    const auto wait_start = std::chrono::steady_clock::now();
    bool warned = false;
    while (free_queue.empty()) {
        const int got = client.WaitRelease(release, 1000);
        if (got < 0) {
            PeerGone();
        }
        if (got > 0) {
            HandleRelease(release);
        } else if (!warned && std::chrono::steady_clock::now() - wait_start > std::chrono::seconds(5)) {
            warned = true;
            LOG_WARNING(Render_Vulkan, "AHB swapchain: no buffer released by the app for 5 s");
        }
    }
    image_index = free_queue.front();
    free_queue.pop_front();
    const auto token = buffers[image_index].lease.Reserve(generation, image_index);
    ASSERT(token.serial != 0);
    return ImportAcquire(image_index) && !needs_recreation;
}

void Swapchain::DrainReleases() {
    Gow3Present::Release release;
    while (client.Connected()) {
        const int got = client.WaitRelease(release, 0);
        if (got < 0) {
            PeerGone();
        }
        if (got == 0) {
            break;
        }
        HandleRelease(release);
    }
}

Swapchain::Reservation Swapchain::CurrentReservation() const {
    if (image_index >= buffers.size() || !buffers[image_index].lease.reserved) return {};
    return {generation, image_index, buffers[image_index].lease.serial};
}

bool Swapchain::Select(Reservation token) {
    if (token.index >= buffers.size() ||
        !buffers[token.index].lease.Matches(token, generation)) return false;
    image_index = token.index;
    return true;
}

void Swapchain::Cancel(Reservation token) {
    if (token.index < buffers.size() && buffers[token.index].lease.Cancel(token, generation)) {
        // 尚未消费的临时导入保留在 buffer 上，下一次实际提交继续使用。
        free_queue.push_back(token.index);
    }
}

bool Swapchain::TryReserve(Reservation& token) {
    token = {};
    if (zero_extent || needs_recreation || buffers.empty()) return false;
    DrainReleases();
    const size_t candidates = free_queue.size();
    for (size_t i = 0; i < candidates; ++i) {
        const u32 index = free_queue.front();
        free_queue.pop_front();
        if (!ImportAcquire(index, true)) {
            free_queue.push_back(index);
            continue;
        }
        token = buffers[index].lease.Reserve(generation, index);
        ASSERT(token.serial != 0);
        return true;
    }
    return false;
}

bool Swapchain::ImportAcquire(u32 index, bool nonblocking) {
    Buffer& buffer = buffers[index];
    if (buffer.acquire_imported) return true;
    const int fd = buffer.release_fd;
    if (fd < 0) {
        return true;
    }
    // GOW3_AHB_NO_IMPORT=1 takes the CPU-wait path for every release fence: the sync file is waited
    // on with poll() instead of imported as a semaphore. Isolates a fault in
    // vkImportSemaphoreFdKHR (the app's fence, produced by the compositor) from the rest of the
    // hand-over. Costs a CPU wait per acquired buffer.
    static const bool no_import = [] {
        const char* value = std::getenv("GOW3_AHB_NO_IMPORT");
        return value && *value && std::strcmp(value, "1") == 0;
    }();
    if (sync_import_ok && !no_import) {
        const vk::ImportSemaphoreFdInfoKHR import_info{
            .semaphore = image_acquired[index],
            .flags = vk::SemaphoreImportFlagBits::eTemporary,
            .handleType = vk::ExternalSemaphoreHandleTypeFlagBits::eSyncFd,
            .fd = fd,
        };
        const auto result = instance.GetDevice().importSemaphoreFdKHR(import_info);
        if (result == vk::Result::eSuccess) {
            buffer.release_fd = -1;
            buffer.acquire_imported = true; // the driver owns the fd now
            return true;
        }
        LOG_WARNING(Render_Vulkan,
                    "Importing the release fence failed ({}): waiting for fences on the CPU instead",
                    vk::to_string(result));
        sync_import_ok = false;
    }
    if (nonblocking && Framegen::PollSyncFile(fd, 0) != 1) return false;
    while (!nonblocking) {
        const int result = Framegen::PollSyncFile(fd, 1000);
        ASSERT_MSG(result >= 0, "Invalid release fence for buffer {}: {}", index, result);
        if (result == 1) break;
        LOG_WARNING(Render_Vulkan, "AHB swapchain: release fence of buffer {} still pending", index);
    }
    close(fd);
    buffer.release_fd = -1;
    return true;
}

int Swapchain::ExportPresentReady() {
    const auto [result, fd] = instance.GetDevice().getSemaphoreFdKHR({
        .semaphore = present_ready[image_index],
        .handleType = vk::ExternalSemaphoreHandleTypeFlagBits::eSyncFd,
    });
    if (result != vk::Result::eSuccess) {
        // Without the fence the app would read a half-drawn buffer: wait for the GPU here.
        static bool logged = false;
        if (!logged) {
            logged = true;
            LOG_ERROR(Render_Vulkan,
                      "Exporting the present-ready semaphore as a sync file failed ({}): falling "
                      "back to waiting for the GPU on every frame",
                      vk::to_string(result));
        }
        const auto idle = instance.GetDevice().waitIdle();
        ASSERT_MSG(idle == vk::Result::eSuccess, "waitIdle failed: {}", vk::to_string(idle));
        // 失败导出没有转移 payload；设备空闲后重建，避免再次 signal 旧的 binary payload。
        instance.GetDevice().destroySemaphore(present_ready[image_index]);
        const vk::ExportSemaphoreCreateInfo export_info{
            .handleTypes = vk::ExternalSemaphoreHandleTypeFlagBits::eSyncFd};
        present_ready[image_index] = Check(instance.GetDevice().createSemaphore({.pNext = &export_info}));
        async_present = false;
        return -1;
    }
    return fd; // -1 is possible too: the semaphore was already signaled
}

bool Swapchain::Present() {
    // GOW3_AHB_NO_EXPORT=1 keeps everything this thread does (barriers, clear, blit, overlay,
    // submission) but stops at handing the frame to the app: no semaphore export, no SendFrame.
    // Bisects "the device dies on our submission" against "the device dies on the export or on the
    // socket write". With no frames sent the app never releases a buffer, so the expected outcome
    // is a stall in AcquireNextImage, not a crash.
    static const int send_mode = [] {
        const char* value = std::getenv("GOW3_AHB_SEND");
        if (!value || !*value) return 0;                  // fence: export, then send
        return std::strcmp(value, "nofence") == 0 ? 1     // send, no fence
                     : std::strcmp(value, "none") == 0 ? 2  // neither (bisect)
                                                      : 0;
    }();
    if (send_mode == 2) {
        ++frame_counter;
        return true;
    }
    const int fence_fd = send_mode == 1 ? -1 : ExportPresentReady();
    const bool handed = buffers[image_index].lease.HandOff(CurrentReservation(), generation);
    ASSERT(handed);
    ++frame_counter;
    if (client.Connected()) {
        if (!client.SendFrame(generation, image_index, frame_counter, width, height, fence_fd)) {
            PeerGone();
        }
        // The buffer is the app's until its release.
    } else {
        // Nobody shows it: the sync file is dropped, the buffer is free at once.
        free_queue.push_back(image_index);
        buffers[image_index].lease.Release();
        buffers[image_index].release_fd = -1;
    }
    if (fence_fd >= 0) {
        close(fence_fd);
    }
    buffers[image_index].acquire_imported = false;
    return !needs_recreation;
}

} // namespace Vulkan
#endif // __ANDROID__
