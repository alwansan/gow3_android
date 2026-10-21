// SPDX-License-Identifier: GPL-2.0-or-later
// 移植自 Bloodborne-Android（GPL-2.0）的 vk_breadcrumbs.cpp，按 gow3_pc 结构适配：
// 仅支持 VK_AMD_buffer_marker（缺失时明确降级并关闭，不做时间戳查询回退），
// 不含 guest 内存回读与着色器转储（gow3_pc 无对应的 runtime vma 接口）。

#include <array>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <vector>

#include "video_core/renderer_vulkan/vk_breadcrumbs.h"
#include "video_core/renderer_vulkan/vk_instance.h"

#include <vk_mem_alloc.h>

namespace Vulkan::Breadcrumbs {

namespace {

constexpr u32 MaxStreams = 16;
constexpr u32 StreamStride = 64; // 每个流的标记缓冲字节数（一条 cache line）
constexpr u64 RingSize = 1u << 15;
constexpr u32 ArgSlots = 4096;                        // indirect dispatch 参数，各 16 字节
constexpr u64 ArgsOffset = MaxStreams * StreamStride; // 参数区在缓冲内的起点

struct Entry {
    u64 id;
    Crumb crumb;
};

struct Stream {
    const char* name = nullptr;
    std::atomic<u64> next{1};
    std::unique_ptr<Entry[]> ring;
};

std::array<Stream, MaxStreams> streams;
std::atomic<u32> num_streams{0};
bool enabled = false;
std::once_flag init_once;
vk::Buffer marker_buffer{};
VmaAllocation marker_allocation{};
volatile u32* markers = nullptr;
volatile u32* args = nullptr;
std::atomic<u32> next_args_slot{0};

void Init(const Instance& instance) {
    // 默认关闭：breadcrumbs 是 GPU 挂死取证工具（每个 draw/dispatch 前后写 marker 命令 + CPU
    // 环形登记），是逐帧开销。性能对照实验（Android 540p 掉帧定位）确认默认开启时 GpuComm
    // 线程被拖慢；现在只有 GOW3_BREADCRUMBS=1 显式开启才启用，需要取证时再开。
    const char* env = std::getenv("GOW3_BREADCRUMBS");
    if (!(env && env[0] == '1')) {
        return;
    }
    if (!instance.IsBufferMarkerSupported()) {
        std::printf("GPU breadcrumbs: VK_AMD_buffer_marker unavailable, GPU hangs will not be "
                    "located (GOW3_BREADCRUMBS)\n");
        return;
    }
    const VkBufferCreateInfo buffer_ci = {
        .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
        .size = ArgsOffset + ArgSlots * 16,
        .usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
    };
    const VmaAllocationCreateInfo alloc_ci = {
        .flags = VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT,
        .usage = VMA_MEMORY_USAGE_AUTO_PREFER_HOST,
        .requiredFlags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
    };
    VkBuffer buffer{};
    VmaAllocationInfo info{};
    if (vmaCreateBuffer(instance.GetAllocator(), &buffer_ci, &alloc_ci, &buffer,
                        &marker_allocation, &info) != VK_SUCCESS ||
        !info.pMappedData) {
        std::printf("GPU breadcrumbs: no host-visible buffer, GPU hangs will not be located\n");
        return;
    }
    marker_buffer = vk::Buffer{buffer};
    markers = static_cast<volatile u32*>(info.pMappedData);
    for (u32 i = 0; i < (ArgsOffset + ArgSlots * 16) / sizeof(u32); ++i) {
        markers[i] = 0;
    }
    args = markers + ArgsOffset / sizeof(u32);
    enabled = true;
}

const char* KindName(Kind kind) {
    switch (kind) {
    case Kind::Pass:
        return "pass";
    case Kind::Draw:
        return "draw";
    case Kind::DrawIndexed:
        return "indexed draw";
    case Kind::DrawIndirect:
        return "indirect draw";
    case Kind::Dispatch:
        return "dispatch";
    case Kind::DispatchIndirect:
        return "indirect dispatch";
    }
    return "?";
}

void PrintEntry(const Stream& stream, u64 id, const char* label) {
    const Entry& entry = stream.ring[id & (RingSize - 1)];
    if (entry.id != id) {
        std::printf("    #%llu %s: no longer kept\n", (unsigned long long)id, label);
        return;
    }
    const Crumb& c = entry.crumb;
    std::printf("    #%llu %s: %s", (unsigned long long)id, label, KindName(c.kind));
    switch (c.kind) {
    case Kind::Pass:
        std::printf(" %s", c.name ? c.name : "?");
        if (c.count[0] != 0) {
            std::printf(" (%ux%u, %u colors, %llu layers, depth/stencil/clears %#llx)", c.count[0],
                        c.count[1], c.count[2], (unsigned long long)c.hash[1],
                        (unsigned long long)c.hash[0]);
        }
        break;
    case Kind::Draw:
    case Kind::DrawIndexed:
        std::printf(" vs %016llx ps %016llx, %u %s x %u instances", (unsigned long long)c.hash[0],
                    (unsigned long long)c.hash[1], c.count[0],
                    c.kind == Kind::Draw ? "vertices" : "indices", c.count[1]);
        break;
    case Kind::DrawIndirect:
        std::printf(" vs %016llx ps %016llx, up to %u draws, stride %u, arguments at %#llx",
                    (unsigned long long)c.hash[0], (unsigned long long)c.hash[1], c.count[0],
                    c.count[1], (unsigned long long)c.address);
        break;
    case Kind::Dispatch:
        std::printf(" cs %016llx, %ux%ux%u groups", (unsigned long long)c.hash[0], c.count[0],
                    c.count[1], c.count[2]);
        break;
    case Kind::DispatchIndirect:
        std::printf(" cs %016llx, arguments at %#llx", (unsigned long long)c.hash[0],
                    (unsigned long long)c.address);
        if (c.args_slot < ArgSlots) {
            const volatile u32* read = args + c.args_slot * 4;
            std::printf(" (the GPU read %ux%ux%u groups)", read[0], read[1], read[2]);
        }
        std::printf(c.count[0] ? ", in place" : ", a copy");
        break;
    }
    for (u32 i = 0; i < 2; ++i) {
        if (c.program[i]) {
            std::printf(", code %#llx", (unsigned long long)c.program[i]);
        }
    }
    std::printf("\n");
}

/// 某个流 GPU 已写到的最新标记值（0：什么都没写）。
u32 ReadMarker(u32 stream) {
    return markers[stream * StreamStride / sizeof(u32)];
}

void Report(const char* title, const char* where) {
    std::printf("GPU breadcrumbs %s (%s): what each command stream finished\n", title, where);
    const u32 count = std::min(num_streams.load(), MaxStreams);
    for (u32 s = 0; s < count; ++s) {
        const Stream& stream = streams[s];
        const u64 last = stream.next.load() - 1;
        const u32 marker = ReadMarker(s);
        std::printf("  stream %u (%s): %llu commands noted", s, stream.name,
                    (unsigned long long)last);
        if (last == 0) {
            std::printf("\n");
            continue;
        }
        if (marker == 0) {
            std::printf(", the GPU finished none\n");
            continue;
        }
        // 标记值是 2 * id + after 的低 32 位。
        const u64 newest = last * 2 + 1;
        const u64 value = newest - u32(u32(newest) - marker);
        const u64 id = value / 2;
        if (value & 1) {
            if (id == last) {
                std::printf(", the GPU finished all\n");
                continue;
            }
            std::printf(", the GPU finished #%llu but did not start #%llu: stuck in the work "
                        "recorded between them (copies, clears, barriers) or before it\n",
                        (unsigned long long)id, (unsigned long long)(id + 1));
            PrintEntry(stream, id, "finished");
            PrintEntry(stream, id + 1, "next");
        } else {
            std::printf(", the GPU started #%llu and did not finish it (%llu noted after it)\n",
                        (unsigned long long)id, (unsigned long long)(last - id));
            if (id > 1) {
                PrintEntry(stream, id - 1, "finished");
            }
            PrintEntry(stream, id, "STUCK");
        }
        for (u64 next = id + 2; next <= std::min(last, id + 4); ++next) {
            PrintEntry(stream, next, "later");
        }
    }
    std::fflush(stdout);
}

} // Anonymous namespace

u32 NewStream(const Instance& instance, const char* name) {
    std::call_once(init_once, [&] { Init(instance); });
    const u32 index = num_streams.fetch_add(1, std::memory_order_relaxed);
    if (index >= MaxStreams) {
        return MaxStreams;
    }
    streams[index].name = name;
    streams[index].ring = std::make_unique<Entry[]>(RingSize);
    return index;
}

bool Enabled() noexcept {
    return enabled;
}

u32 Note(u32 stream_index, const Crumb& crumb) {
    if (stream_index >= MaxStreams) {
        return 0;
    }
    Stream& stream = streams[stream_index];
    const u64 id = stream.next.fetch_add(1, std::memory_order_relaxed);
    Entry& entry = stream.ring[id & (RingSize - 1)];
    entry.crumb = crumb;
    entry.id = id;
    return u32(id);
}

void Mark(vk::CommandBuffer cmdbuf, u32 stream, u32 id, bool after) {
    if (!enabled || stream >= MaxStreams) {
        return;
    }
    cmdbuf.writeBufferMarkerAMD(vk::PipelineStageFlagBits::eBottomOfPipe, marker_buffer,
                                stream * StreamStride, id * 2 + (after ? 1 : 0));
}

u32 ArgsSlot() {
    return enabled ? next_args_slot.fetch_add(1, std::memory_order_relaxed) % ArgSlots : ~0u;
}

void CopyArgs(vk::CommandBuffer cmdbuf, vk::Buffer source, u64 offset, u32 slot) {
    if (!enabled || slot >= ArgSlots || !marker_buffer) {
        return;
    }
    cmdbuf.copyBuffer(source, marker_buffer,
                      vk::BufferCopy{offset, ArgsOffset + u64(slot) * 16, 3 * sizeof(u32)});
}

namespace {
void Report(const char* title, const char* where);
}

void ReportDeviceLost(const char* where) {
    static std::atomic_flag reported = ATOMIC_FLAG_INIT;
    if (enabled && !reported.test_and_set()) {
        Report("at device lost", where);
    }
}

void ReportStuck(const char* where) {
    static std::atomic_flag reported = ATOMIC_FLAG_INIT;
    if (enabled && !reported.test_and_set()) {
        Report("while the GPU makes no progress", where);
    }
}

} // namespace Vulkan::Breadcrumbs
