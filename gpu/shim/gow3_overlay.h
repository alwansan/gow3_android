// SPDX-License-Identifier: GPL-2.0-or-later
// gow3: in-game settings menu (Dear ImGui), drawn by the presenter into the swapchain image
// after the game frame, at display resolution. Insert (keyboard) or R3+L2 (gamepad) opens it;
// while it is open the game gets no pad/keyboard input. Settings live in gow3_settings.h.

#pragma once

#include <string>
#include <vector>
#include "common/types.h"
#include "gow3_png.h"
#include "video_core/renderer_vulkan/vk_common.h"

union SDL_Event;
struct SDL_Window;

namespace Vulkan {
class Instance;
}

namespace Gow3Overlay {

/// Present thread, once: the ImGui context and its Vulkan backend.
void Init(const Vulkan::Instance& instance, vk::Format format, u32 image_count);

/// Window thread, for every SDL event: true when the menu consumed it.
bool HandleEvent(const SDL_Event& event);
/// Turns SDL text input on while the menu edits a value (window thread, once per poll).
void UpdateTextInput(SDL_Window* window);

/// Start-up loading screen (pipeline cache warm-up): `total` 0 shows no count.
void SetLoading(bool active, u32 done, u32 total);
/// Background cache warm-up: a small corner note while the game runs; `total` 0 hides it.
void SetBackgroundLoading(u32 done, u32 total);
/// 磁盘着色器预读（上游 b776be7）：任意线程；读入完成后再保持 2 秒；`total` 0 隐藏。
void SetShaderPrefetch(u32 done, u32 total);
/// 着色器翻译计数（上游 b776be7）：GPU 线程；`pending` 仍在翻译的数量、`started` 自上次调用
/// 新增的数量。任一在跑或结束后 2 秒内显示右下角角标。
void NoteShaderCompiles(u32 pending, u32 started);

/// True once the ImGui Vulkan backend came up. False after a failed Init: there is no ImGui
/// context then, so nothing may call ImGui::GetIO(), and a dialog the player would have to read
/// cannot be shown - callers must refuse instead of opening an invisible one.
bool Available();

/// Whether anything is drawn this frame (menu open or FPS counter on).
bool Visible();

/// Present thread: draws into `view` (layout ColorAttachmentOptimal).
void Render(vk::CommandBuffer cmdbuf, vk::ImageView view, vk::Extent2D extent);

/// The menu is open or a text entry is shown: the game's input is held neutral.
bool CapturesInput();

/// Window thread: the game's text dialog (sceImeDialog). Shown as a box on screen until
/// `active` is false; typing goes to the window (sdl_window), the box only displays it.
void SetTextEntry(bool active, const std::string& prompt, const std::string& text);

/// 游戏线程(scePadReadState):注入的 pad 文件按键,供下面的对话框使用。
void PublishPad(u32 buttons);
/// 上次发布的掩码中,自上次读取以来新按下的那些位。
u32 PadEdges();
/// Present thread: handles the Android/injected R3+L2 shortcut, which has no SDL events.
void PollMenuShortcut();
/// Present thread (Bionic only): feeds pad-state edges into the open menu as ImGui gamepad
/// keys (no SDL event stream; see gow3_overlay.cpp).
void PollMenuPad();

/// The game's save list: shown until the player chooses (arrows/D-pad, Enter/Cross) or
/// cancels (Esc/Circle). `pictures`: one per item (empty = none), drawn beside its text.
/// PollChoice: -1 while open, -2 cancelled, else the chosen index.
void BeginChoice(const std::string& title, const std::vector<std::string>& items,
                 std::vector<Gow3Png::Image> pictures, int focus);
int PollChoice();
/// 按取消关掉一个打开的列表,由 runtime 自己的超时触发。不关的话,游戏已经被告知对话框结束了,
/// 列表却还在绘制、还在扣住游戏的输入。
void CancelChoice();

} // namespace Gow3Overlay
