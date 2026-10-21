// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once

#include <cerrno>
#include <poll.h>

namespace Vulkan::Framegen {

// 非阻塞分支连 EINTR 都交回调用者，避免可选生成路径形成隐式忙等。
inline int PollSyncFile(int fd, int timeout_ms) {
    pollfd descriptor{fd, POLLIN, 0};
    int result;
    do {
        result = poll(&descriptor, 1, timeout_ms);
    } while (result < 0 && errno == EINTR && timeout_ms != 0);
    if (result < 0) return errno == EINTR ? 0 : -errno;
    if (descriptor.revents & POLLNVAL) return -EBADF;
    if (descriptor.revents & (POLLERR | POLLHUP)) return -EIO;
    return result > 0 && (descriptor.revents & POLLIN) ? 1 : 0;
}

} // namespace Vulkan::Framegen
