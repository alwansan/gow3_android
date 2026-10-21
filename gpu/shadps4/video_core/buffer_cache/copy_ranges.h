// SPDX-License-Identifier: GPL-2.0-or-later
// 改编自 gow3_android_daedalus_fork 239b7df (erickmendes22)。
#pragma once
#include <algorithm>
#include <cstdint>
#include <optional>
#include <span>
#include <utility>
#include <vector>

namespace VideoCore {
using CopyRange = std::pair<std::uint32_t, std::uint32_t>; // 相对偏移、字节数。
inline std::optional<std::vector<CopyRange>> NormalizeCopyRanges(
    std::uint32_t size, std::span<const CopyRange> input) {
    std::vector<CopyRange> result;
    result.reserve(input.size());
    for (auto [offset, length] : input) {
        if (offset > size || length > size - offset) return std::nullopt;
        if (length) result.emplace_back(offset, length);
    }
    std::sort(result.begin(), result.end());
    std::size_t used = 0;
    for (std::size_t index = 0; index < result.size(); ++index) {
        const auto range = result[index];
        if (used && std::uint64_t(result[used - 1].first) + result[used - 1].second >= range.first) {
            auto& previous = result[used - 1];
            previous.second = std::uint32_t(std::max(std::uint64_t(previous.first) + previous.second,
                std::uint64_t(range.first) + range.second) - previous.first);
        } else result[used++] = range;
    }
    result.resize(used);
    return result;
}
} // namespace VideoCore
