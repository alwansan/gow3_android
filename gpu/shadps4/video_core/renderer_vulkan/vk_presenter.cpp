// SPDX-FileCopyrightText: Copyright 2024-2026 shadPS4 Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include "common/debug.h"
#include "common/elf_info.h"
#include "common/io_file.h"
#include "common/path_util.h"
#include "common/singleton.h"
#include "core/debug_state.h"
#include "core/emulator_settings.h"
#include "sdl_window.h"
#include "video_core/buffer_cache/buffer.h"
#include "video_core/renderdoc.h"
#include "video_core/renderer_vulkan/vk_breadcrumbs.h"
#include "video_core/renderer_vulkan/vk_platform.h"
#include "gow3_overlay.h"
#include "gow3_settings.h"
#include "gow3_graphics.h"
#include "gow3_mobile_policy.h"
#include "video_core/renderer_vulkan/vk_temporal_upscaler.h"
#include "video_core/renderer_vulkan/vk_framegen_barriers.h"
#include "video_core/renderer_vulkan/vk_presenter.h"
#ifdef __ANDROID__
#include "gow3_lsfg.h" // 帧生成引擎宿主（change add-native-frame-generation）
#endif
#include "video_core/renderer_vulkan/vk_warmup_inbox.h"
#include "video_core/renderer_vulkan/vk_rasterizer.h"
#include "video_core/texture_cache/image.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cctype>
#include <chrono>
#include <cmath>
#include <csetjmp>
#include <cstring>
#include <cstdlib>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <limits>
#include <memory>
#include <span>
#include <sstream>
#include <system_error>
#include <vector>
#include <vk_mem_alloc.h>
#ifdef MemoryBarrier
#undef MemoryBarrier // gow3: winnt.h macro (through fmt), clashes with vk::MemoryBarrier
#endif

namespace Vulkan {

bool CanBlitToSwapchain(const vk::PhysicalDevice physical_device, vk::Format format) {
    const vk::FormatProperties props{physical_device.getFormatProperties(format)};
    return static_cast<bool>(props.optimalTilingFeatures & vk::FormatFeatureFlagBits::eBlitDst);
}

[[nodiscard]] vk::ImageSubresourceLayers MakeImageSubresourceLayers() {
    return vk::ImageSubresourceLayers{
        .aspectMask = vk::ImageAspectFlagBits::eColor,
        .mipLevel = 0,
        .baseArrayLayer = 0,
        .layerCount = 1,
    };
}

[[nodiscard]] vk::ImageBlit MakeImageBlit(s32 frame_width, s32 frame_height, s32 dst_width,
                                          s32 dst_height, s32 offset_x, s32 offset_y) {
    return vk::ImageBlit{
        .srcSubresource = MakeImageSubresourceLayers(),
        .srcOffsets =
            std::array{
                vk::Offset3D{
                    .x = 0,
                    .y = 0,
                    .z = 0,
                },
                vk::Offset3D{
                    .x = frame_width,
                    .y = frame_height,
                    .z = 1,
                },
            },
        .dstSubresource = MakeImageSubresourceLayers(),
        .dstOffsets =
            std::array{
                vk::Offset3D{
                    .x = offset_x,
                    .y = offset_y,
                    .z = 0,
                },
                vk::Offset3D{
                    .x = offset_x + dst_width,
                    .y = offset_y + dst_height,
                    .z = 1,
                },
            },
    };
}

[[nodiscard]] vk::ImageBlit MakeImageBlitStretch(s32 frame_width, s32 frame_height,
                                                 s32 swapchain_width, s32 swapchain_height) {
    return MakeImageBlit(frame_width, frame_height, swapchain_width, swapchain_height, 0, 0);
}

static vk::Rect2D FitImage(s32 frame_width, s32 frame_height, s32 swapchain_width,
                           s32 swapchain_height) {
    float frame_aspect = static_cast<float>(frame_width) / frame_height;
    float swapchain_aspect = static_cast<float>(swapchain_width) / swapchain_height;

    u32 dst_width = swapchain_width;
    u32 dst_height = swapchain_height;

    if (frame_aspect > swapchain_aspect) {
        dst_height = static_cast<s32>(swapchain_width / frame_aspect);
    } else {
        dst_width = static_cast<s32>(swapchain_height * frame_aspect);
    }

    const s32 offset_x = (swapchain_width - dst_width) / 2;
    const s32 offset_y = (swapchain_height - dst_height) / 2;

    return vk::Rect2D{{offset_x, offset_y}, {dst_width, dst_height}};
}

[[nodiscard]] vk::ImageBlit MakeImageBlitFit(s32 frame_width, s32 frame_height, s32 swapchain_width,
                                             s32 swapchain_height) {
    const auto& dst_rect = FitImage(frame_width, frame_height, swapchain_width, swapchain_height);

    return MakeImageBlit(frame_width, frame_height, dst_rect.extent.width, dst_rect.extent.height,
                         dst_rect.offset.x, dst_rect.offset.y);
}

static void RecordFrameTransfer(vk::CommandBuffer cmd, vk::Image source, vk::Format source_format,
                                u32 width, u32 height, vk::Image destination,
                                vk::Format destination_format, vk::Extent2D extent) {
    static const bool fast_copy = Framegen::Enabled("GOW3_PRESENT_FAST_COPY");
    const auto rectangle = FitImage(width, height, extent.width, extent.height);
    const bool full = Framegen::FullCoverage(
        rectangle.offset.x, rectangle.offset.y, rectangle.extent.width, rectangle.extent.height,
        extent.width, extent.height);
    const vk::ImageSubresourceRange range{vk::ImageAspectFlagBits::eColor, 0, 1, 0, 1};
    if (!fast_copy || !full) {
        cmd.clearColorImage(destination, vk::ImageLayout::eTransferDstOptimal,
                            vk::ClearColorValue{std::array<float, 4>{0, 0, 0, 1}}, range);
        const vk::MemoryBarrier cleared{.srcAccessMask = vk::AccessFlagBits::eTransferWrite,
                                        .dstAccessMask = vk::AccessFlagBits::eTransferWrite};
        cmd.pipelineBarrier(vk::PipelineStageFlagBits::eTransfer,
                            vk::PipelineStageFlagBits::eTransfer, {}, cleared, {}, {});
    }
    if (fast_copy && Framegen::DirectCopy(width, height, extent.width, extent.height,
                                          source_format == destination_format, full)) {
        cmd.copyImage(source, vk::ImageLayout::eTransferSrcOptimal, destination,
                      vk::ImageLayout::eTransferDstOptimal,
                      vk::ImageCopy{.srcSubresource = MakeImageSubresourceLayers(),
                                    .dstSubresource = MakeImageSubresourceLayers(),
                                    .extent = {width, height, 1}});
    } else {
        cmd.blitImage(source, vk::ImageLayout::eTransferSrcOptimal, destination,
                      vk::ImageLayout::eTransferDstOptimal,
                      MakeImageBlitFit(width, height, extent.width, extent.height),
                      vk::Filter::eLinear);
    }
}

// gow3: screenshot capture and ImGui overlays removed; the port presents directly.

Presenter::Presenter(Frontend::WindowSDL& window_, AmdGpu::Liverpool* liverpool_)
    : window{window_}, liverpool{liverpool_},
      instance{window, EmulatorSettings.GetGpuId(), EmulatorSettings.IsVkValidationEnabled(),
               EmulatorSettings.IsVkCrashDiagnosticEnabled()},
      draw_scheduler{instance, true}, present_scheduler{instance}, flip_scheduler{instance},
      lsfg_requested{Gow3LsfgState::Initial()}, lsfg_watch{lsfg_requested},
      lsfg{Lsfg::Create(instance, lsfg_requested)},
      swapchain{instance, window, lsfg ? lsfg->Multiplier() : 0},
      runtime{instance, draw_scheduler},
      rasterizer{std::make_unique<Rasterizer>(instance, draw_scheduler, runtime, liverpool)},
      texture_cache{rasterizer->GetTextureCache()} {
#ifdef __ANDROID__
    // 防御：无论成员声明/初始化顺序如何，确保交换链的缓冲数与 FIFO 跟随帧生成配置。
    ApplyLsfgOutputMode();
#endif
    const u32 num_images = swapchain.GetImageCount();
    const vk::Device device = instance.GetDevice();

    // Create presentation frames.
    present_frames.resize(num_images);
    for (u32 i = 0; i < num_images; i++) {
        Frame& frame = present_frames[i];
        frame.id = i;
        auto fence = Check<"create present done fence">(
            device.createFence({.flags = vk::FenceCreateFlagBits::eSignaled}));
        frame.present_done = fence;
        free_queue.push(&frame);
    }

#ifndef __ANDROID__
    fsr_pass.Create(device, instance.GetAllocator(), num_images);
    fsr_created = true;
#endif
    pp_pass.Create(device, swapchain.GetSurfaceFormat().format);
    Gow3Overlay::Init(instance, swapchain.GetSurfaceFormat().format, num_images);

    const bool binary_cache = EmulatorSettingsImpl::Flag("GOW3_PIPELINE_BINARIES", true) &&
                              EmulatorSettings.IsPipelineCacheEnabled();
    const bool capture_only = EmulatorSettingsImpl::Flag("GOW3_PIPELINE_BINARY_ISOLATED", false) &&
                              EmulatorSettingsImpl::Flag("GOW3_PIPELINE_CAPTURE_ONLY", false);
    if (UseOnDemandPipelines(binary_cache, instance.GetPipelineBinaryGlobalKey().keySize != 0,
                            capture_only)) {
        rasterizer->GetPipelineCache().StartOnDemand();
        return;
    }

    // gow3: by default the cached pipelines load while the game runs (a corner note shows the
    // progress). Otherwise rebuilding them takes up to minutes before the first game frame:
    // show what is happening instead of a black window.
    const char* background_warmup = std::getenv("GOW3_BACKGROUND_WARMUP");
    if ((!background_warmup || *background_warmup != '0') &&
        rasterizer->GetPipelineCache().StartBackgroundWarmUp()) {
        return;
    }
    Gow3Overlay::SetLoading(true, 0, 0);
    PresentLoadingFrame();
    auto last = std::chrono::steady_clock::now();
    rasterizer->GetPipelineCache().WarmUp([&](u32 done, u32 total) {
        const auto now = std::chrono::steady_clock::now();
        if (now - last < std::chrono::milliseconds(250)) {
            return;
        }
        last = now;
        Gow3Overlay::SetLoading(true, done, total);
        PresentLoadingFrame();
    });
    Gow3Overlay::SetLoading(false, 0, 0);

}

Presenter::~Presenter() {

    draw_scheduler.Finish();
    present_scheduler.Finish();
    flip_scheduler.Finish();
    Check(draw_scheduler.CommandBuffer().reset());
    Check(present_scheduler.CommandBuffer().reset());
    Check(flip_scheduler.CommandBuffer().reset());

    const vk::Device device = instance.GetDevice();
    for (auto& frame : present_frames) {
        vmaDestroyImage(instance.GetAllocator(), frame.image, frame.allocation);
        device.destroyImageView(frame.image_view);
        device.destroyFence(frame.present_done);
    }
}

bool Presenter::IsVideoOutSurface(const AmdGpu::ColorBuffer& color_buffer) const {
    return std::ranges::find(vo_buffers_addr, color_buffer.Address()) != vo_buffers_addr.cend();
}

void Presenter::RecreateFrame(Frame* frame, u32 width, u32 height) {
    const vk::Device device = instance.GetDevice();
    if (frame->image_view) {
        device.destroyImageView(frame->image_view);
    }
    if (frame->image) {
        vmaDestroyImage(instance.GetAllocator(), frame->image, frame->allocation);
    }

    const vk::Format format = swapchain.GetSurfaceFormat().format;
    const vk::ImageCreateInfo image_info = {
        .flags = vk::ImageCreateFlagBits::eMutableFormat,
        .imageType = vk::ImageType::e2D,
        .format = format,
        .extent = {width, height, 1},
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = vk::SampleCountFlagBits::e1,
        .usage = vk::ImageUsageFlagBits::eColorAttachment | vk::ImageUsageFlagBits::eTransferDst |
                 vk::ImageUsageFlagBits::eTransferSrc | vk::ImageUsageFlagBits::eSampled,
    };

    const VmaAllocationCreateInfo alloc_info = {
        .flags = VMA_ALLOCATION_CREATE_WITHIN_BUDGET_BIT,
        .usage = VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE,
        .requiredFlags = 0,
        .preferredFlags = 0,
        .pool = VK_NULL_HANDLE,
        .pUserData = nullptr,
    };

    VkImage unsafe_image{};
    VkImageCreateInfo unsafe_image_info = static_cast<VkImageCreateInfo>(image_info);

    VkResult result = vmaCreateImage(instance.GetAllocator(), &unsafe_image_info, &alloc_info,
                                     &unsafe_image, &frame->allocation, nullptr);
    if (result != VK_SUCCESS) [[unlikely]] {
        LOG_CRITICAL(Render_Vulkan, "Failed allocating texture with error {}",
                     vk::to_string(vk::Result{result}));
        UNREACHABLE();
    }
    frame->image = vk::Image{unsafe_image};
    SetObjectName(device, frame->image, "Frame image #{}", frame->id);

    const vk::ImageViewCreateInfo view_info = {
        .image = frame->image,
        .viewType = vk::ImageViewType::e2D,
        .format = format,
        .subresourceRange{
            .aspectMask = vk::ImageAspectFlagBits::eColor,
            .baseMipLevel = 0,
            .levelCount = 1,
            .baseArrayLayer = 0,
            .layerCount = 1,
        },
    };
    auto view = Check<"create frame image view">(device.createImageView(view_info));
    frame->image_view = view;
    frame->width = width;
    frame->height = height;

    frame->is_hdr = swapchain.GetHDR();
}

// 等待 present 相关的 fence；超过 10 秒说明 GPU 无进展，先输出一次 breadcrumbs 卡死报告
// （只打一次）再继续等，等待语义不变。device lost 时先给 breadcrumbs 出报告再返回错误。
static vk::Result WaitPresentFence(vk::Device device, vk::Fence fence) {
    vk::Result result{};
    while (true) {
        result = device.waitForFences(fence, vk::True, 10'000'000'000ull);
        if (result == vk::Result::eTimeout) {
            Breadcrumbs::ReportStuck("waiting for a frame");
            continue;
        }
        break;
    }
    if (result == vk::Result::eErrorDeviceLost) {
        Breadcrumbs::ReportDeviceLost("waiting for a frame");
    }
    return result;
}

Frame* Presenter::PrepareLastFrame() {
    if (last_submit_frame == nullptr) {
        return nullptr;
    }

    Frame* frame = last_submit_frame;

    // 等待上一帧的 present fence；超过 10 秒说明 GPU 无进展，先输出一次 breadcrumbs
    // 卡死报告再继续等（报告只打一次），等待语义不变。
    while (true) {
        vk::Result result = instance.GetDevice().waitForFences(frame->present_done, false,
                                                               10'000'000'000ull);
        if (result == vk::Result::eSuccess) {
            break;
        }
        if (result == vk::Result::eTimeout) {
            Breadcrumbs::ReportStuck("waiting for a frame");
            continue;
        }
        if (result == vk::Result::eErrorDeviceLost) {
            Breadcrumbs::ReportDeviceLost("waiting for a frame");
        }
        ASSERT_MSG(result != vk::Result::eErrorDeviceLost,
                   "Device lost during waiting for a frame");
    }

    auto& scheduler = flip_scheduler;
    scheduler.EndRendering();
    const auto cmdbuf = scheduler.CommandBuffer();

    const auto frame_subresources = vk::ImageSubresourceRange{
        .aspectMask = vk::ImageAspectFlagBits::eColor,
        .baseMipLevel = 0,
        .levelCount = 1,
        .baseArrayLayer = 0,
        .layerCount = VK_REMAINING_ARRAY_LAYERS,
    };

    const auto pre_barrier =
        vk::ImageMemoryBarrier2{.srcStageMask = vk::PipelineStageFlagBits2::eColorAttachmentOutput,
                                .srcAccessMask = vk::AccessFlagBits2::eColorAttachmentRead,
                                .dstStageMask = vk::PipelineStageFlagBits2::eColorAttachmentOutput,
                                .dstAccessMask = vk::AccessFlagBits2::eColorAttachmentWrite,
                                .oldLayout = vk::ImageLayout::eShaderReadOnlyOptimal,
                                .newLayout = vk::ImageLayout::eGeneral,
                                .image = frame->image,
                                .subresourceRange{frame_subresources}};

    cmdbuf.pipelineBarrier2(vk::DependencyInfo{
        .imageMemoryBarrierCount = 1,
        .pImageMemoryBarriers = &pre_barrier,
    });

    // Flush frame creation commands.
    frame->ready_semaphore = scheduler.GetWorkSemaphore()->Handle();
    frame->ready_tick = scheduler.CurrentTick();
    SubmitInfo info{};
    scheduler.Flush(info);
    return frame;
}

static vk::Format GetFrameViewFormat(const Libraries::VideoOut::PixelFormat format) {
    switch (format) {
    case Libraries::VideoOut::PixelFormat::A8B8G8R8Srgb:
        return vk::Format::eR8G8B8A8Srgb;
    case Libraries::VideoOut::PixelFormat::A8R8G8B8Srgb:
        return vk::Format::eB8G8R8A8Srgb;
    case Libraries::VideoOut::PixelFormat::A2R10G10B10:
    case Libraries::VideoOut::PixelFormat::A2R10G10B10Srgb:
    case Libraries::VideoOut::PixelFormat::A2R10G10B10Bt2020Pq:
        return vk::Format::eA2R10G10B10UnormPack32;
    default:
        break;
    }
    UNREACHABLE_MSG("Unknown format={}", static_cast<u32>(format));
    return {};
}

// gow3: raw copy of an image for GOW3_FRAME_DUMP_TRIGGER, written once the GPU is done.
static void DumpRaw(const Instance& instance, Scheduler& scheduler, vk::CommandBuffer cmdbuf,
                    vk::Image image, vk::ImageLayout layout, u32 w, u32 h, const char* name,
                    int index) {
    const VkBufferCreateInfo buffer_ci{.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
                                       .size = VkDeviceSize(w) * h * 4,
                                       .usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT};
    const VmaAllocationCreateInfo alloc_ci{
        .flags = VMA_ALLOCATION_CREATE_MAPPED_BIT | VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT,
        .usage = VMA_MEMORY_USAGE_AUTO_PREFER_HOST};
    VkBuffer buffer{};
    VmaAllocation allocation{};
    VmaAllocationInfo info{};
    if (vmaCreateBuffer(instance.GetAllocator(), &buffer_ci, &alloc_ci, &buffer, &allocation,
                        &info) != VK_SUCCESS) {
        return;
    }
    cmdbuf.copyImageToBuffer(image, layout, buffer,
                             vk::BufferImageCopy{.imageSubresource = {vk::ImageAspectFlagBits::eColor, 0, 0, 1},
                                                 .imageExtent = {w, h, 1}});
    const char* dir = std::getenv("GOW3_DUMP_DIR");
    char path[512];
    std::snprintf(path, sizeof(path), "%s/%s_%03d_%ux%u.raw", dir && *dir ? dir : "out/dump", name,
                  index, w, h);
    scheduler.DeferPriorityOperation([allocator = instance.GetAllocator(), buffer, allocation, info,
                                      size = buffer_ci.size, file = std::string{path}] {
        vmaInvalidateAllocation(allocator, allocation, 0, VK_WHOLE_SIZE);
        if (FILE* f = std::fopen(file.c_str(), "wb")) {
            std::fwrite(info.pMappedData, 1, size, f);
            std::fclose(f);
        }
        vmaDestroyBuffer(allocator, buffer, allocation);
    });
}

Frame* Presenter::PrepareFrame(const Libraries::VideoOut::BufferAttributeGroup& attribute,
                               VAddr cpu_address) {
    // gow3: GOW3_FRAME_DUMP_TRIGGER=<file>: when the file exists it is removed and this frame's
    // guest display buffer and presented image are written to GOW3_DUMP_DIR (default out/dump)
    // as raw 32-bit pixels (menus and movies included; the upscaler dumps scene frames only).
    static const char* frame_dump_trigger = std::getenv("GOW3_FRAME_DUMP_TRIGGER");
    static int frame_dump_index = 0;
    const bool frame_dump = frame_dump_trigger && std::remove(frame_dump_trigger) == 0;
    // gow3: scaled upscaler presets: the output-size display buffer drawn by the port.
    TemporalUpscaler::Display display{};
    const bool upscaled = rasterizer->GetUpscaler().DisplayOverride(
        cpu_address, display, GetFrameViewFormat(attribute.attrib.pixel_format));
    VideoCore::ImageId image_id{};
    if (!upscaled) {
        auto desc = VideoCore::TextureCache::ImageDesc{attribute, cpu_address};
        image_id = texture_cache.FindImage(desc);
        texture_cache.UpdateImage(image_id);
    }

    Frame* frame = GetRenderFrame();

    const auto frame_subresources = vk::ImageSubresourceRange{
        .aspectMask = vk::ImageAspectFlagBits::eColor,
        .baseMipLevel = 0,
        .levelCount = 1,
        .baseArrayLayer = 0,
        .layerCount = VK_REMAINING_ARRAY_LAYERS,
    };

    const auto pre_barrier = vk::ImageMemoryBarrier2{
        .srcStageMask = vk::PipelineStageFlagBits2::eColorAttachmentOutput,
        .srcAccessMask = vk::AccessFlagBits2::eColorAttachmentRead,
        .dstStageMask = vk::PipelineStageFlagBits2::eColorAttachmentOutput,
        .dstAccessMask = vk::AccessFlagBits2::eColorAttachmentWrite,
        .oldLayout = vk::ImageLayout::eUndefined,
        .newLayout = vk::ImageLayout::eColorAttachmentOptimal,
        .image = frame->image,
        .subresourceRange{frame_subresources},
    };

    draw_scheduler.EndRendering();
    const auto cmdbuf = draw_scheduler.CommandBuffer();
    cmdbuf.pipelineBarrier2(vk::DependencyInfo{
        .imageMemoryBarrierCount = 1,
        .pImageMemoryBarriers = &pre_barrier,
    });

    VideoCore::ImageViewInfo view_info{};
    view_info.format = GetFrameViewFormat(attribute.attrib.pixel_format);
    // Exclude alpha from output frame to avoid blending with UI.
    view_info.mapping.a = vk::ComponentSwizzle::eOne;

    vk::ImageView image_view{};
    vk::Extent2D image_size{};
    if (upscaled) {
        const auto device = instance.GetDevice();
        image_view = display.view;
        if (!image_view) {
            image_view = Check(device.createImageView({
                .image = display.image,
                .viewType = vk::ImageViewType::e2D,
                .format = view_info.format,
                .components = {vk::ComponentSwizzle::eIdentity, vk::ComponentSwizzle::eIdentity,
                               vk::ComponentSwizzle::eIdentity, vk::ComponentSwizzle::eOne},
                .subresourceRange = {vk::ImageAspectFlagBits::eColor, 0, 1, 0, 1},
            }));
            draw_scheduler.DeferOperation([device, image_view] { device.destroyImageView(image_view); });
        }
        image_size = vk::Extent2D{display.width, display.height};
        const vk::ImageMemoryBarrier2 to_read{
            .srcStageMask = vk::PipelineStageFlagBits2::eColorAttachmentOutput,
            .srcAccessMask = vk::AccessFlagBits2::eColorAttachmentWrite,
            .dstStageMask = vk::PipelineStageFlagBits2::eAllCommands,
            .dstAccessMask = vk::AccessFlagBits2::eShaderRead,
            .oldLayout = vk::ImageLayout::eGeneral,
            .newLayout = vk::ImageLayout::eShaderReadOnlyOptimal,
            .image = display.image,
            .subresourceRange = {vk::ImageAspectFlagBits::eColor, 0, 1, 0, 1},
        };
        cmdbuf.pipelineBarrier2({.imageMemoryBarrierCount = 1, .pImageMemoryBarriers = &to_read});
    } else {
        auto& image = texture_cache.GetImage(image_id);
        if (frame_dump) {
            runtime.Transit(&image, vk::ImageLayout::eTransferSrcOptimal,
                            vk::PipelineStageFlagBits2::eTransfer, vk::AccessFlagBits2::eTransferRead);
            runtime.FlushBarriers();
            DumpRaw(instance, draw_scheduler, cmdbuf, image.GetImage(),
                    vk::ImageLayout::eTransferSrcOptimal, image.info.size.width,
                    image.info.size.height, "display", frame_dump_index);
        }
        image_view = *image.FindView(view_info).image_view;
        image_size = vk::Extent2D{image.info.size.width, image.info.size.height};
        runtime.Transit(&image, vk::ImageLayout::eShaderReadOnlyOptimal,
                        vk::PipelineStageFlagBits2::eFragmentShader,
                        vk::AccessFlagBits2::eShaderRead);
        runtime.FlushBarriers();
    }
    expected_ratio = static_cast<float>(image_size.width) / static_cast<float>(image_size.height);

    // Android 使用启动器的有效开关门控旧后处理，不能被历史 rcas=1 静默重新开启。
    const auto& graphics = Gow3Settings::Get();
    const bool android = Gow3Mobile::Android();
    const bool upscaler_enabled = graphics.upscaler != Gow3Settings::UpscalerOff;
    const bool sharpen_enabled = graphics.sharpen;
    const auto legacy_fsr = Gow3Graphics::ResolveLegacyFsrOptions(
        android, upscaler_enabled, sharpen_enabled, graphics.fsr1, graphics.rcas);
    fsr_settings.enable = legacy_fsr.fsr1;
    fsr_settings.use_rcas = legacy_fsr.rcas;
    fsr_settings.rcas_attenuation = Gow3Graphics::RcasAttenuation(graphics.rcas_strength);
    if (android) {
        static const bool logged = [&] {
            std::printf("Android postprocess: initial FSR1=%d RCAS=%d (upscaler=%s sharpen=%d)\n",
                        int(legacy_fsr.fsr1), int(legacy_fsr.rcas),
                        Gow3Settings::UpscalerName(graphics.upscaler), int(sharpen_enabled));
            return true;
        }();
        (void)logged;
    }
    Gow3Settings::Get().image_width = int(image_size.width);
    Gow3Settings::Get().image_height = int(image_size.height);
    Gow3Settings::Get().window_width = int(frame->width);
    Gow3Settings::Get().window_height = int(frame->height);
    const bool fsr_runs = Gow3Graphics::FsrPassRuns(
        fsr_settings.enable, fsr_settings.use_rcas, int(image_size.width), int(image_size.height),
        int(frame->width), int(frame->height));
    Gow3Settings::Get().rcas_applied = fsr_settings.use_rcas && fsr_runs;
    DebugState.is_using_fsr = fsr_runs;
    if (fsr_runs) {
        // Android 直通默认不创建无用的 EASU/RCAS 管线；启用时仍支持动态初始化。
        if (!fsr_created) {
            fsr_pass.Create(instance.GetDevice(), instance.GetAllocator(), swapchain.GetImageCount());
            fsr_created = true;
        }
        image_view = fsr_pass.Render(cmdbuf, image_view, image_size, {frame->width, frame->height},
                                     fsr_settings, frame->is_hdr);
    }

    // Vulkan has no sRGB variant of the 10-bit format, so an A2R10G10B10Srgb buffer reaches
    // the post process pass still sRGB encoded and has to be decoded there instead.
    pp_settings.srgb_input =
        attribute.attrib.pixel_format == Libraries::VideoOut::PixelFormat::A2R10G10B10Srgb;
    // GPU breadcrumbs：登记后处理 pass（此处已在 direct mode，闭包立即执行）。
    draw_scheduler.RecordCrumb({.kind = Breadcrumbs::Kind::Pass, .name = "post process"},
                               [&](vk::CommandBuffer rec_cmdbuf) {
                                   pp_pass.Render(rec_cmdbuf, image_view, image_size, *frame,
                                                  pp_settings);
                               });
    if (frame_dump) {
        // The presented image as the swapchain blit reads it (General after the pass).
        const vk::MemoryBarrier2 done{.srcStageMask = vk::PipelineStageFlagBits2::eAllCommands,
                                      .srcAccessMask = vk::AccessFlagBits2::eMemoryWrite,
                                      .dstStageMask = vk::PipelineStageFlagBits2::eTransfer,
                                      .dstAccessMask = vk::AccessFlagBits2::eTransferRead};
        cmdbuf.pipelineBarrier2({.memoryBarrierCount = 1, .pMemoryBarriers = &done});
        DumpRaw(instance, draw_scheduler, cmdbuf, frame->image, vk::ImageLayout::eGeneral,
                frame->width, frame->height, "output", frame_dump_index);
        ++frame_dump_index;
    }

    // Flush frame creation commands.
    frame->ready_semaphore = draw_scheduler.GetWorkSemaphore()->Handle();
    frame->ready_tick = draw_scheduler.CurrentTick();
    SubmitInfo info{};
    draw_scheduler.Flush(info);

    // gow3: the GPU command thread runs at most GOW3_FRAMES_AHEAD (default 1) guest frames
    // ahead of the GPU: it waits here for the frame that many flips back. When the GPU is the
    // bottleneck it finishes frames at an even rate; without this bound the command thread ran
    // ahead and then blocked wherever a resource ran out, so flips (and the guest's frame
    // timing) came in bursts: 12.5/25 ms alternation at 80 FPS. 0 turns it off.
    // The in-game menu changes it live (Gow3Settings::frames_queued; GOW3_FRAMES_AHEAD at start).
    const u32 frames_ahead = u32(std::max(0, Gow3Settings::Get().frames_queued.load()));
    if (!frames_ahead) {
        recent_frame_ticks.clear();
    } else {
        recent_frame_ticks.push_back(frame->ready_tick);
        while (recent_frame_ticks.size() > frames_ahead) {
            const u64 tick = recent_frame_ticks.front();
            recent_frame_ticks.pop_front();
            if (recent_frame_ticks.size() == frames_ahead) {
                draw_scheduler.Wait(tick);
            }
        }
    }
    return frame;
}

Frame* Presenter::PrepareBlankFrame(bool present_thread) {
    // Request a free presentation frame.
    Frame* frame = GetRenderFrame();

    auto& scheduler = present_thread ? present_scheduler : draw_scheduler;
    scheduler.EndRendering();

    const auto cmdbuf = scheduler.CommandBuffer();

    constexpr vk::ImageSubresourceRange simple_subresource = {
        .aspectMask = vk::ImageAspectFlagBits::eColor,
        .levelCount = 1,
        .layerCount = 1,
    };
    const auto pre_barrier = vk::ImageMemoryBarrier2{
        .srcStageMask = vk::PipelineStageFlagBits2::eColorAttachmentOutput,
        .srcAccessMask = vk::AccessFlagBits2::eColorAttachmentRead,
        .dstStageMask = vk::PipelineStageFlagBits2::eColorAttachmentOutput,
        .dstAccessMask = vk::AccessFlagBits2::eColorAttachmentWrite,
        .oldLayout = vk::ImageLayout::eUndefined,
        .newLayout = vk::ImageLayout::eColorAttachmentOptimal,
        .image = frame->image,
        .subresourceRange = simple_subresource,
    };

    const auto post_barrier = vk::ImageMemoryBarrier2{
        .srcStageMask = vk::PipelineStageFlagBits2::eColorAttachmentOutput,
        .srcAccessMask = vk::AccessFlagBits2::eColorAttachmentWrite,
        .dstStageMask = vk::PipelineStageFlagBits2::eFragmentShader,
        .dstAccessMask = vk::AccessFlagBits2::eShaderRead,
        .oldLayout = vk::ImageLayout::eColorAttachmentOptimal,
        .newLayout = vk::ImageLayout::eGeneral,
        .image = frame->image,
        .subresourceRange = simple_subresource,
    };

    const vk::RenderingAttachmentInfo attachment = {
        .imageView = frame->image_view,
        .imageLayout = vk::ImageLayout::eColorAttachmentOptimal,
        .loadOp = vk::AttachmentLoadOp::eClear,
        .storeOp = vk::AttachmentStoreOp::eStore,
    };
    const vk::RenderingInfo rendering_info = {
        .renderArea =
            {
                .extent = {frame->width, frame->height},
            },
        .layerCount = 1,
        .colorAttachmentCount = 1u,
        .pColorAttachments = &attachment,
    };

    cmdbuf.pipelineBarrier2(vk::DependencyInfo{
        .imageMemoryBarrierCount = 1,
        .pImageMemoryBarriers = &pre_barrier,
    });

    cmdbuf.beginRendering(rendering_info);
    cmdbuf.endRendering();

    cmdbuf.pipelineBarrier2(vk::DependencyInfo{
        .imageMemoryBarrierCount = 1,
        .pImageMemoryBarriers = &post_barrier,
    });

    // Flush frame creation commands.
    frame->ready_semaphore = scheduler.GetWorkSemaphore()->Handle();
    frame->ready_tick = scheduler.CurrentTick();
    SubmitInfo info{};
    scheduler.Flush(info);
    return frame;
}

namespace {
// The AHB swapchain hands image ownership to the Android compositor across queue families
// (VK_QUEUE_FAMILY_FOREIGN_EXT). Whether that is correct on a given driver is worth being able to
// A/B without a rebuild: GOW3_AHB_OWNERSHIP=0 keeps the layout transition but leaves both queue
// families IGNORED, which is what the WSI swapchain does. A GPU fault that disappears with it
// points at the ownership transfer rather than at the game.
bool AhbOwnershipEnabled() {
    static const bool enabled = [] {
        const char* value = std::getenv("GOW3_AHB_OWNERSHIP");
        return !(value && *value && std::strcmp(value, "0") == 0);
    }();
    return enabled;
}
} // namespace

void Presenter::PresentLoadingFrame() {
    if (window.GetWidth() == 0 || window.GetHeight() == 0) {
        return;
    }
    swapchain.UpdateVsync();
    if (window.GetWidth() != swapchain.GetWidth() || window.GetHeight() != swapchain.GetHeight()) {
        swapchain.Recreate(window.GetWidth(), window.GetHeight());
    }
    if (!swapchain.AcquireNextImage()) {
        return;
    }
    const vk::Image image = swapchain.Image();
    const vk::Extent2D extent = swapchain.GetExtent();
    const auto cmdbuf = present_scheduler.CommandBuffer();
    const vk::ImageSubresourceRange range{
        .aspectMask = vk::ImageAspectFlagBits::eColor,
        .levelCount = 1,
        .layerCount = VK_REMAINING_ARRAY_LAYERS,
    };
    // Queue families default to IGNORED (a plain layout change inside this queue); the AHB
    // swapchain passes the real pair so the image is actually handed over to / taken back from
    // the Android compositor (VK_QUEUE_FAMILY_FOREIGN_EXT), which VK_EXT_queue_family_foreign
    // provides and vk_instance.cpp enables.
    const auto barrier = [&](vk::ImageLayout from, vk::ImageLayout to, vk::AccessFlags src,
                             vk::AccessFlags dst, u32 src_family = VK_QUEUE_FAMILY_IGNORED,
                             u32 dst_family = VK_QUEUE_FAMILY_IGNORED) {
        cmdbuf.pipelineBarrier(vk::PipelineStageFlagBits::eAllCommands,
                               vk::PipelineStageFlagBits::eAllCommands, {}, {}, {},
                               vk::ImageMemoryBarrier{
                                   .srcAccessMask = src,
                                   .dstAccessMask = dst,
                                   .oldLayout = from,
                                   .newLayout = to,
                                   .srcQueueFamilyIndex = src_family,
                                   .dstQueueFamilyIndex = dst_family,
                                   .image = image,
                                   .subresourceRange = range,
                               });
    };
    // Taking the image back from the app: it comes in PresentLayout(), owned by the FOREIGN queue
    // family, and has to end up usable here - TransferDst for the loading frame's clear,
    // ColorAttachmentOptimal for the frame that renders.
    const auto acquire_from_app = [&](vk::ImageLayout to) {
        const auto [src_family, dst_family] = AhbOwnershipEnabled() ? swapchain.AcquireQueues()
                                                                   : std::pair<u32, u32>{
                                                                         VK_QUEUE_FAMILY_IGNORED,
                                                                         VK_QUEUE_FAMILY_IGNORED};
        barrier(swapchain.PresentLayout(), to, vk::AccessFlagBits::eNone,
                vk::AccessFlagBits::eColorAttachmentWrite, src_family, dst_family);
    };
    // Handing the finished image over: layout and ownership both change, so the app's compositor
    // sees it as SHARED and waits on the present-ready fence.
    const auto release_to_app = [&] {
        const auto [src_family, dst_family] = AhbOwnershipEnabled() ? swapchain.ReleaseQueues()
                                                                   : std::pair<u32, u32>{
                                                                         VK_QUEUE_FAMILY_IGNORED,
                                                                         VK_QUEUE_FAMILY_IGNORED};
        barrier(vk::ImageLayout::eColorAttachmentOptimal, swapchain.PresentLayout(),
                vk::AccessFlagBits::eColorAttachmentWrite, vk::AccessFlagBits::eNone, src_family,
                dst_family);
    };
    acquire_from_app(vk::ImageLayout::eTransferDstOptimal);
    cmdbuf.clearColorImage(image, vk::ImageLayout::eTransferDstOptimal,
                           vk::ClearColorValue{std::array<float, 4>{0.0f, 0.0f, 0.0f, 1.0f}},
                           range);
    barrier(vk::ImageLayout::eTransferDstOptimal, vk::ImageLayout::eColorAttachmentOptimal,
            vk::AccessFlagBits::eTransferWrite, vk::AccessFlagBits::eColorAttachmentWrite);
    // GPU breadcrumbs：登记 overlay pass（present_scheduler 非线程化，闭包立即执行）。
    present_scheduler.RecordCrumb({.kind = Breadcrumbs::Kind::Pass, .name = "overlay"},
                                  [&](vk::CommandBuffer rec_cmdbuf) {
                                      Gow3Overlay::Render(rec_cmdbuf, swapchain.ImageView(),
                                                          extent);
                                  });
    release_to_app();
    SubmitInfo info{};
    info.AddWait(swapchain.GetImageAcquiredSemaphore());
    info.AddSignal(swapchain.GetPresentReadySemaphore());
    // Same reason as in Present(): the AHB export in Swapchain::Present() needs the present-ready
    // signal to be no longer pending, so give the submission a fence and wait on it below.
    vk::Fence present_fence;
    if (swapchain.NeedsSubmittedBeforePresent()) {
        auto [fence_result, fence] = instance.GetDevice().createFence({});
        ASSERT_MSG(fence_result == vk::Result::eSuccess, "Loading frame fence creation failed: {}",
                   vk::to_string(fence_result));
        present_fence = fence;
        info.AddSignal(present_fence);
    }
    present_scheduler.Flush(info);
    if (present_fence) {
        const auto waited = WaitPresentFence(instance.GetDevice(), present_fence);
        instance.GetDevice().destroyFence(present_fence, nullptr);
        ASSERT_MSG(waited == vk::Result::eSuccess,
                   "Waiting before the AHB present export failed: {}", vk::to_string(waited));
    }
    {
        std::scoped_lock submit_lock{Scheduler::submit_mutex};
        swapchain.Present();
    }
    // A few frames a second during start-up: waiting keeps semaphores and the swapchain simple.
    present_scheduler.Finish();
}

void Presenter::Present(Frame* frame, bool is_reusing_frame, bool is_game_frame) {
    // Free the frame for reuse
    const auto free_frame = [&] {
        if (!is_reusing_frame) {
            last_submit_frame = frame;
            std::scoped_lock fl{free_mutex};
            free_queue.push(frame);
            free_cv.notify_one();
        }
    };

    // gow3: a minimized window has no pixels on Windows (0x0); keep the swapchain and skip
    // presenting until the window is restored.
    if (window.GetWidth() == 0 || window.GetHeight() == 0) {
        free_frame();
        return;
    }

#ifdef __ANDROID__
    // 帧生成：app 侧设置变更在两帧之间应用（BB 同位）。
    PollLsfgState();
#endif

    // Recreate the swapchain if the window was resized.
    swapchain.UpdateVsync();
    if (window.GetWidth() != swapchain.GetWidth() || window.GetHeight() != swapchain.GetHeight()) {
        swapchain.Recreate(window.GetWidth(), window.GetHeight());
    }

    bool real_reserved = false;
#ifdef __ANDROID__
    // 帧生成（gow3_lsfg.h）：先交生成帧，再交真实帧。仅接受与输出同尺寸的游戏帧
    // （首帧在输出尺寸已知前生成的不算：上下文按该尺寸建一次）。
    if (lsfg && is_game_frame && !is_reusing_frame) {
        const vk::Extent2D extent = swapchain.GetExtent();
        SetExpectedGameSize(s32(extent.width), s32(extent.height));
        if (frame->width == expected_frame_width && frame->height == expected_frame_height) {
            real_reserved = PresentWithLsfg(frame);
        }
        if (!lsfg) {
            ApplyLsfgOutputMode(); // 引擎失败关闭：恢复正常缓冲数与呈现模式
        }
    }
#endif


    if (!real_reserved && !swapchain.AcquireNextImage()) {
        swapchain.Recreate(window.GetWidth(), window.GetHeight());
        if (!swapchain.AcquireNextImage()) {
            // User resizes the window too fast and GPU can't keep up. Skip this frame.
            LOG_WARNING(Render_Vulkan, "Skipping frame!");
            free_frame();
            return;
        }
    }

    // Reset fence for queue submission. Do it here instead of GetRenderFrame() because we may
    // skip frame because of slow swapchain recreation. If a frame skip occurs, we skip signal
    // the frame's present fence and future GetRenderFrame() call will hang waiting for this frame.
    const auto reset_result = instance.GetDevice().resetFences(frame->present_done);
    ASSERT_MSG(reset_result == vk::Result::eSuccess,
               "Unexpected error resetting present done fence: {}", vk::to_string(reset_result));

    // gow3: the game frame is blitted (letterboxed) straight into the swapchain image.
    const vk::Image swapchain_image = swapchain.Image();
    auto& scheduler = present_scheduler;
    const auto cmdbuf = scheduler.CommandBuffer();

    if (EmulatorSettings.IsVkHostMarkersEnabled()) {
        cmdbuf.beginDebugUtilsLabelEXT(vk::DebugUtilsLabelEXT{
            .pLabelName = "Present",
        });
    }

    {
        const vk::Extent2D extent = swapchain.GetExtent();
        SetExpectedGameSize(s32(extent.width), s32(extent.height));
        const vk::ImageSubresourceRange color_range{
            .aspectMask = vk::ImageAspectFlagBits::eColor,
            .baseMipLevel = 0,
            .levelCount = 1,
            .baseArrayLayer = 0,
            .layerCount = VK_REMAINING_ARRAY_LAYERS,
        };
        // The swapchain image comes back from its consumer before this frame: on the AHB path the
        // app's compositor left it in PresentLayout() and owns it in the FOREIGN queue family, so
        // take it here (layout and ownership). A VkSwapchainKHR hands out a fresh image instead,
        // and its AcquireQueues/PresentLayout answer with IGNORED / ePresentSrcKHR.
        const auto [acquire_src, acquire_dst] = AhbOwnershipEnabled()
                                                 ? swapchain.AcquireQueues()
                                                 : std::pair<u32, u32>{VK_QUEUE_FAMILY_IGNORED,
                                                                       VK_QUEUE_FAMILY_IGNORED};
        const std::array pre_barriers{
            vk::ImageMemoryBarrier{
                .srcAccessMask = vk::AccessFlagBits::eNone,
                .dstAccessMask = vk::AccessFlagBits::eTransferWrite,
                .oldLayout = swapchain.PresentLayout(),
                .newLayout = vk::ImageLayout::eTransferDstOptimal,
                .srcQueueFamilyIndex = acquire_src,
                .dstQueueFamilyIndex = acquire_dst,
                .image = swapchain_image,
                .subresourceRange = color_range,
            },
            vk::ImageMemoryBarrier{
                .srcAccessMask = vk::AccessFlagBits::eColorAttachmentWrite,
                .dstAccessMask = vk::AccessFlagBits::eTransferRead,
                .oldLayout = vk::ImageLayout::eGeneral,
                .newLayout = vk::ImageLayout::eTransferSrcOptimal,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = frame->image,
                .subresourceRange = color_range,
            },
        };
        cmdbuf.pipelineBarrier(vk::PipelineStageFlagBits::eAllCommands,
                               vk::PipelineStageFlagBits::eTransfer, vk::DependencyFlagBits::eByRegion,
                               {}, {}, pre_barriers);
        RecordFrameTransfer(cmdbuf, frame->image, swapchain.GetSurfaceFormat().format,
                            frame->width, frame->height, swapchain_image,
                            swapchain.GetSurfaceFormat().format, extent);
        // gow3: the settings menu / FPS counter over the frame, at display resolution.
        const bool overlay = Gow3Overlay::Visible();
        const std::array post_barriers{
            vk::ImageMemoryBarrier{
                .srcAccessMask = vk::AccessFlagBits::eTransferWrite,
                .dstAccessMask = overlay ? vk::AccessFlagBits::eColorAttachmentRead |
                                               vk::AccessFlagBits::eColorAttachmentWrite
                                         : vk::AccessFlagBits::eNone,
                .oldLayout = vk::ImageLayout::eTransferDstOptimal,
                .newLayout = overlay ? vk::ImageLayout::eColorAttachmentOptimal
                                     : swapchain.PresentLayout(),
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = swapchain_image,
                .subresourceRange = color_range,
            },
            vk::ImageMemoryBarrier{
                .srcAccessMask = vk::AccessFlagBits::eTransferRead,
                .dstAccessMask = vk::AccessFlagBits::eColorAttachmentWrite,
                .oldLayout = vk::ImageLayout::eTransferSrcOptimal,
                .newLayout = vk::ImageLayout::eGeneral,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = frame->image,
                .subresourceRange = color_range,
            },
        };
        cmdbuf.pipelineBarrier(vk::PipelineStageFlagBits::eTransfer,
                               vk::PipelineStageFlagBits::eAllCommands,
                               vk::DependencyFlagBits::eByRegion, {}, {}, post_barriers);
        if (overlay) {
            // GPU breadcrumbs：登记 overlay pass（present_scheduler 非线程化，闭包立即执行）。
            present_scheduler.RecordCrumb({.kind = Breadcrumbs::Kind::Pass, .name = "overlay"},
                                          [&](vk::CommandBuffer rec_cmdbuf) {
                                              Gow3Overlay::Render(rec_cmdbuf,
                                                                  swapchain.ImageView(), extent);
                                          });
            const auto [release_src, release_dst] = AhbOwnershipEnabled()
                                                      ? swapchain.ReleaseQueues()
                                                      : std::pair<u32, u32>{VK_QUEUE_FAMILY_IGNORED,
                                                                        VK_QUEUE_FAMILY_IGNORED};
            const vk::ImageMemoryBarrier to_present{
                .srcAccessMask = vk::AccessFlagBits::eColorAttachmentWrite,
                .dstAccessMask = vk::AccessFlagBits::eNone,
                .oldLayout = vk::ImageLayout::eColorAttachmentOptimal,
                .newLayout = swapchain.PresentLayout(),
                .srcQueueFamilyIndex = release_src,
                .dstQueueFamilyIndex = release_dst,
                .image = swapchain_image,
                .subresourceRange = color_range,
            };
            cmdbuf.pipelineBarrier(vk::PipelineStageFlagBits::eColorAttachmentOutput,
                                   vk::PipelineStageFlagBits::eBottomOfPipe,
                                   vk::DependencyFlagBits::eByRegion, {}, {}, to_present);
        } else {
            // Without the overlay the post barrier above already left the image in
            // PresentLayout(), but it did not hand ownership over. On the AHB path the image has
            // to be owned by the FOREIGN queue family before the app's compositor uses it, and a
            // queue-family release needs its own barrier (a release is always a separate write
            // after the layout change above).
            const auto [release_src, release_dst] = swapchain.ReleaseQueues();
            if (release_src != release_dst) {
                const vk::ImageMemoryBarrier hand_over{
                    .srcAccessMask = vk::AccessFlagBits::eTransferWrite,
                    .dstAccessMask = vk::AccessFlagBits::eNone,
                    .oldLayout = swapchain.PresentLayout(),
                    .newLayout = swapchain.PresentLayout(),
                    .srcQueueFamilyIndex = release_src,
                    .dstQueueFamilyIndex = release_dst,
                    .image = swapchain_image,
                    .subresourceRange = color_range,
                };
                cmdbuf.pipelineBarrier(vk::PipelineStageFlagBits::eAllCommands,
                                       vk::PipelineStageFlagBits::eAllCommands,
                                       vk::DependencyFlagBits::eByRegion, {}, {}, hand_over);
            }
        }
    }
    if (EmulatorSettings.IsVkHostMarkersEnabled()) {
        cmdbuf.endDebugUtilsLabelEXT();
    }

    // Flush vulkan commands.

    SubmitInfo info{};
    info.AddWait(swapchain.GetImageAcquiredSemaphore());
    info.AddWait(frame->ready_semaphore, frame->ready_tick);
    info.AddSignal(swapchain.GetPresentReadySemaphore());
    info.AddSignal(frame->present_done);
    scheduler.Flush(info);
    // sync-fd 允许导出已提交而未完成的 signal；未知驱动保留完成等待作为兼容策略。
    if (swapchain.NeedsSubmittedBeforePresent()) {
        const auto waited = WaitPresentFence(instance.GetDevice(), frame->present_done);
        ASSERT_MSG(waited == vk::Result::eSuccess, "Waiting before the AHB present export failed: {}",
                   vk::to_string(waited));
    }
    // gow3：vkQueuePresentKHR 处的 Vulkan layer（Lossless Scaling 插帧，GOW3_LSFG，由 run-gow3.sh
    // 配合玩家自备的 Lossless.dll 加载 lsfg-vk）会等待上面这次提交所 signal 的 present-ready
    // 信号量。GOW3 有意不在应用侧为它加等待：
    //   * Flush(info) -> SubmitExecution -> vkQueueSubmit 已经在本线程执行完（它持有 submit_mutex
    //     并等提交返回后才继续），所以提交及其 present-ready 信号在下面 Present() 之前就已进入队列；
    //   * vkQueuePresentKHR 在同一队列上排在其后，layer 对 present-ready 的等待由队列顺序满足，
    //     而不是靠 GPU 已经画完。
    // Bloodborne 的 present 路径之所以调 scheduler.WaitSubmitted(tick)，是因为它的 recording 线程
    // 是异步提交（BB_ASYNC_SUBMIT），layer 可能在提交进入队列前就抢到 present；GOW3 在本线程同步
    // 提交，因此这个保护不需要——而且 gow3 的无参 WaitSubmitted() 是「GPU 完成等待」（Wait(tick-1)），
    // 会在每帧无谓地卡住流水线。
    // （A/B 验证见 docs / OpenSpec harden-gow3-lsfg-integration 任务 5.3：LSFG 开启时不得在 present 前等 GPU 完成。）
    static const bool present_layer = std::getenv("GOW3_LSFG") != nullptr;
    // 只打一次，且线程安全：用局部静态初始化（C++11 起 magic static 的初始化是原子的）做一次性
    // 日志，避免可变 static bool 在多线程 Flip（GOW3_PRESENT_THREAD=0 时 RunPresenter 直接在工作
    // 线程执行）并发进入 Present() 时的 data race（见 4.4 复评）。
    static const bool present_layer_logged_once = [] {
        // present_layer 是静态存储期的局部变量，lambda 直接引用即可，无需捕获。
        LOG_INFO(Render_Vulkan,
                 "gow3: LSFG present path: {} (GOW3_LSFG={}); no app-side WaitSubmitted "
                 "(submission is already queued by Flush)",
                 present_layer ? "ENABLED" : "off", present_layer ? "1" : "(unset)");
        return true;
    }();
    (void)present_layer_logged_once;

    // Present to swapchain.
    {
        std::scoped_lock submit_lock{Scheduler::submit_mutex};
        if (!swapchain.Present() && window.GetWidth() != 0 && window.GetHeight() != 0) {
            swapchain.Recreate(window.GetWidth(), window.GetHeight());
        }
    }

    free_frame();
    if (!is_reusing_frame && is_game_frame) {
        DebugState.IncFlipFrameNum();
    }
}


#ifdef __ANDROID__
// ---- 帧生成（change add-native-frame-generation；结构对齐 BB，适配 gow3 的单体 Present）----

void Presenter::PollLsfgState() {
    Gow3LsfgState::State next;
    if (!lsfg_watch.Poll(next)) {
        WriteLsfgStats();
        return; // 无变化
    }
    if (next == lsfg_requested && (lsfg || !next.On())) {
        WriteLsfgStats();
        return; // 状态没变（或引擎该在而不在的请求已应用过）
    }
    ApplyLsfgState(next);
}

void Presenter::WriteLsfgStats() {
    const char* state_path = Gow3LsfgState::FilePath();
    if (!state_path || !*state_path) return; // env 未设（env-only 运行）：无遥测
    const auto now = std::chrono::steady_clock::now();
    const bool active = lsfg != nullptr;
    if (active) {
        if (now - lsfg_stats_ts < std::chrono::seconds(1)) return;
        lsfg_stats_ts = now;
        double src = 0, pres = 0;
        if (!lsfg->Rates(&src, &pres)) return; // 首窗未满：下个周期再写
        std::string path = state_path;
        path = path.substr(0, path.rfind('/') + 1) + "framegen.stats"; // 与 app 侧 FrameGenSettings.STATS_FILE_NAME 一致
        std::string tmp = path + ".tmp";
        std::FILE* f = std::fopen(tmp.c_str(), "w");
        if (!f) return;
        std::fprintf(f, "source_fps=%.1f\npresented_fps=%.1f\nmultiplier=%u\n",
                     src, pres, lsfg->Multiplier());
        std::fclose(f);
        std::rename(tmp.c_str(), path.c_str());
        lsfg_stats_active = true;
        lsfg_stats_cleared = false; // 引擎激活：下次关闭需再清一次
    } else if (!lsfg_stats_cleared) {
        // 关闭/引擎失败/从未激活：清零一次，HUD 回单一帧率（spec 语义）。不依赖本会话
        // 是否曾激活——上一会话残留的 framegen.stats（倍率≥2）也必须在此被清掉，
        // 否则 HUD 会把它当成实时插帧遥测显示「LSFG Nx」。
        std::string path = state_path;
        path = path.substr(0, path.rfind('/') + 1) + "framegen.stats";
        std::string tmp = path + ".tmp";
        std::FILE* f = std::fopen(tmp.c_str(), "w");
        if (!f) return;
        std::fprintf(f, "source_fps=0\npresented_fps=0\nmultiplier=0\n");
        std::fclose(f);
        std::rename(tmp.c_str(), path.c_str());
        lsfg_stats_active = false;
        lsfg_stats_cleared = true; // 本会话只清一次：之后每帧只有布尔判断，无 I/O
    }
}

void Presenter::ApplyLsfgState(const Gow3LsfgState::State& next) {
    lsfg_requested = next;
    // 帧边界：本函数跑在 present 线程两帧之间，没有循环持有的缓冲。引擎的 flow
    // scale/模型/生成帧数在启动时固定——任何“开”态变化都是重建 Lsfg：旧的等最后
    // 一批完成并析构，新的在下一个真实帧建上下文（头几帧只填时域历史）。
    lsfg.reset();
    if (next.On()) {
        lsfg = Lsfg::Create(instance, next);
    }
    // 交换链跟随实际运行的东西：倍率决定缓冲数，帧生成要求 FIFO——仅在开启时。
    ApplyLsfgOutputMode();
    if (lsfg) {
        LOG_INFO(Render_Vulkan, "LSFG: now x{}, flow {:.2f}, performance {}, fp16 {}",
                 next.multiplier, next.flow, next.performance ? 1 : 0, next.fp16 ? 1 : 0);
    } else {
        LOG_INFO(Render_Vulkan, "LSFG: off");
    }
}

void Presenter::ApplyLsfgOutputMode() {
    swapchain.SetFrameGeneration(lsfg ? lsfg->Multiplier() : 0);
    ApplyFramesCap();
}

void Presenter::ApplyFramesCap() {
    frames_cap.store(lsfg ? lsfg->FramesCap() : 0, std::memory_order_relaxed);
    free_cv.notify_all();
}

bool Presenter::PresentWithLsfg(Frame* frame) {
    using Clock = std::chrono::steady_clock;
    if (!swapchain.AcquireNextImage()) return false;
    const auto real = swapchain.CurrentReservation();
    std::array<Swapchain::Reservation, 3> reserved{};
    const u32 requested = lsfg->Multiplier() - 1;
    u32 available = 0;
    for (; available < requested; ++available) {
        if (!swapchain.TryReserve(reserved[available])) break;
    }
    const bool complete_batch = available == requested;
    if (!complete_batch) {
        for (u32 i = 0; i < available; ++i) swapchain.Cancel(reserved[i]);
        available = 0;
    }
    const auto plan = Framegen::PacingPlan::Create(
        Clock::now(), lsfg->FrameIntervalMs(), lsfg->Multiplier(), lsfg->Paced());
    const int generated =
        lsfg->ProcessFrame(present_scheduler, frame->image, frame->width, frame->height,
                           frame->ready_semaphore, frame->ready_tick, complete_batch);
    if (generated < 0) {
        for (u32 i = 0; i < available; ++i) swapchain.Cancel(reserved[i]);
        swapchain.Cancel(real);
        lsfg.reset(); // 本次运行内关闭（日志有原因）：本帧走常规路径
        return false;
    }
    for (int i = 0; i < generated; ++i) {
        if (plan.Expired(Clock::now()) || !swapchain.Select(reserved[i])) {
            for (u32 j = u32(i); j < u32(generated); ++j) {
                lsfg->DiscardOutput(present_scheduler, j);
                swapchain.Cancel(reserved[j]);
            }
            available = u32(i);
            break;
        }
        if (plan.Active()) std::this_thread::sleep_until(plan.Slot(u32(i)));
        const Lsfg::Output& output = lsfg->GetOutput(i);
        // 生成帧来自外部（EXTERNAL）队列族、GENERAL 布局：blit 进交换链缓冲时按
        // 外部来源取所有权，并等待该帧的 sync file（经 AddWait 的时间线信号量）。
        const vk::Image dst = swapchain.Image();
        const vk::Extent2D extent = swapchain.GetExtent();
        auto& scheduler = present_scheduler;
        const auto cmdbuf = scheduler.CommandBuffer();
        const vk::ImageSubresourceRange color_range{
            .aspectMask = vk::ImageAspectFlagBits::eColor,
            .baseMipLevel = 0,
            .levelCount = 1,
            .baseArrayLayer = 0,
            .layerCount = VK_REMAINING_ARRAY_LAYERS,
        };
        const auto [acquire_src, acquire_dst] = swapchain.AcquireQueues();
        // 引擎输出图的所有权在 VK_QUEUE_FAMILY_EXTERNAL（引擎侧交接，见 gow3_lsfg.cpp）；
        // FOREIGN 是 app↔game 的 AHB 交接语义，别混用。
        const std::array pre_barriers{
            vk::ImageMemoryBarrier{
                .srcAccessMask = vk::AccessFlagBits::eNone,
                .dstAccessMask = vk::AccessFlagBits::eTransferWrite,
                .oldLayout = swapchain.PresentLayout(),
                .newLayout = vk::ImageLayout::eTransferDstOptimal,
                .srcQueueFamilyIndex = acquire_src,
                .dstQueueFamilyIndex = acquire_dst,
                .image = dst,
                .subresourceRange = color_range,
            },
            vk::ImageMemoryBarrier{
                .srcAccessMask = vk::AccessFlagBits::eColorAttachmentWrite,
                .dstAccessMask = vk::AccessFlagBits::eTransferRead,
                .oldLayout = vk::ImageLayout::eGeneral,
                .newLayout = vk::ImageLayout::eTransferSrcOptimal,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL,
                .dstQueueFamilyIndex = instance.GetGraphicsQueueFamilyIndex(),
                .image = output.image,
                .subresourceRange = color_range,
            },
        };
        cmdbuf.pipelineBarrier(vk::PipelineStageFlagBits::eAllCommands,
                               vk::PipelineStageFlagBits::eTransfer,
                               vk::DependencyFlagBits::eByRegion, {}, {}, pre_barriers);
        RecordFrameTransfer(cmdbuf, output.image, lsfg->SharedFormat(), frame->width,
                            frame->height, dst, swapchain.GetSurfaceFormat().format, extent);
        const auto [release_src, release_dst] = swapchain.ReleaseQueues();
        const vk::ImageMemoryBarrier to_present{
            .srcAccessMask = vk::AccessFlagBits::eTransferWrite,
            .dstAccessMask = vk::AccessFlagBits::eNone,
            .oldLayout = vk::ImageLayout::eTransferDstOptimal,
            .newLayout = swapchain.PresentLayout(),
            .srcQueueFamilyIndex = release_src,
            .dstQueueFamilyIndex = release_dst,
            .image = dst,
            .subresourceRange = color_range,
        };
        cmdbuf.pipelineBarrier(vk::PipelineStageFlagBits::eTransfer,
                               vk::PipelineStageFlagBits::eBottomOfPipe,
                               vk::DependencyFlagBits::eByRegion, {}, {}, to_present);
        // 输出图所有权交回 EXTERNAL（引擎下一周期要用）。
        const auto output_release =
            Framegen::ReleaseOutput(output.image, instance.GetGraphicsQueueFamilyIndex());
        cmdbuf.pipelineBarrier(vk::PipelineStageFlagBits::eAllCommands,
                               vk::PipelineStageFlagBits::eBottomOfPipe,
                               vk::DependencyFlagBits::eByRegion, {}, {}, output_release);
        SubmitInfo info{};
        // app 的 release fence（导入为本缓冲的 acquire 信号量）：合成器可能仍在读
        // 该缓冲——不等待就是覆写竞态。null（首用无 fence）被 AddWait 丢弃。
        info.AddWait(swapchain.GetImageAcquiredSemaphore());
        if (output.wait) {
            info.AddWait(output.wait);
        }
        info.AddSignal(swapchain.GetPresentReadySemaphore());
        lsfg->PrepareOutput(u32(i));
        if (output.done) {
            info.AddSignal(output.done);
        }
        const u64 submitted_tick = scheduler.CurrentTick();
        scheduler.Flush(info);
        lsfg->OutputSubmitted(u32(i), submitted_tick);
        if (swapchain.NeedsSubmittedBeforePresent()) scheduler.Wait(submitted_tick);
        {
            std::scoped_lock submit_lock{Scheduler::submit_mutex};
            swapchain.Present(); // 交给 app（该缓冲被扣住直到 RELEASE）
        }
    }
    // 预热可能没有生成输出，归还额外预留；真实帧复用 Present 的同一条记录和 fence 路径。
    for (u32 i = u32(generated); i < available; ++i) swapchain.Cancel(reserved[i]);
    if (generated > 0 && plan.Active()) std::this_thread::sleep_until(plan.RealTarget());
    return swapchain.Select(real);
}
#endif // __ANDROID__


Frame* Presenter::GetRenderFrame() {
    // Wait for free presentation frames
    Frame* frame;
    {
        std::unique_lock lock{free_mutex};
#ifdef __ANDROID__
        // 帧生成开启时，游戏帧还要等 frames_cap 之外没有未呈现帧：present 线程每帧
        // 做的事更多，排队给它的帧就是延迟（BB 同语义）。
        free_cv.wait(lock, [this] {
            const u32 cap = frames_cap.load(std::memory_order_relaxed);
            return !free_queue.empty() && (cap == 0 || present_frames.size() - free_queue.size() < cap);
        });
#else
        free_cv.wait(lock, [this] { return !free_queue.empty(); });
#endif
        LOG_DEBUG(Render_Vulkan, "Got render frame, remaining {}", free_queue.size() - 1);

        // Take the frame from the queue
        frame = free_queue.front();
        free_queue.pop();
    }

    const vk::Device device = instance.GetDevice();
    vk::Result result{};

    const auto wait = [&]() {
        result = WaitPresentFence(device, frame->present_done);
        return result;
    };

    // Wait for the presentation to be finished so all frame resources are free
    while (wait() != vk::Result::eSuccess) {
        ASSERT_MSG(result != vk::Result::eErrorDeviceLost,
                   "Device lost during waiting for a frame");
        // Retry if the waiting times out
        if (result == vk::Result::eTimeout) {
            continue;
        }
    }

    if (frame->width != expected_frame_width || frame->height != expected_frame_height ||
        frame->is_hdr != swapchain.GetHDR()) {
        RecreateFrame(frame, expected_frame_width, expected_frame_height);
    }

    return frame;
}

void Presenter::SetExpectedGameSize(s32 width, s32 height) {
    if (width <= 0 || height <= 0) {
        return; // no surface (minimized window): keep the last frame size
    }
    const float ratio = (float)width / (float)height;

    expected_frame_height = height;
    expected_frame_width = width;
    if (ratio > expected_ratio) {
        expected_frame_width = static_cast<s32>(height * expected_ratio);
    } else {
        expected_frame_height = static_cast<s32>(width / expected_ratio);
    }
}

} // namespace Vulkan
