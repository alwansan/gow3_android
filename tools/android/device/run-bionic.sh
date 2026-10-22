#!/system/bin/sh
# gow3port: Bionic 运行脚本，在 app 沙箱里跑（由 Android 侧以 /system/bin/sh 启动）：游戏进程
# gow3-probe + libgow3gpu/libgow3cpu（NDK/Bionic 构建），来自 app 的数据目录。用手机自己的
# Vulkan 驱动，或 adrenotools 风格的驱动（files/drivers/<name>/：app 为这块 GPU 内置的驱动，
# 由 DriverManager 自动挑选，或玩家安装的）。
#
# 没有 rootfs、没有 glibc bundle、没有 X server；帧经 app 上屏（GOW3_PRESENT_SOCKET）。
# 游戏文件在设备侧由准备逻辑处理（D4：Python 链迁移中，POC 阶段先用构建期预编译产物）。
# 只用 Android 自己的工具（toybox：sed/tail/cat/cp/mkdir/printf/rm/taskset）。
# 归因：BB tools/android/run-bionic.sh 的 gow3 移植；去掉 LSFG，设置名换 gow3 的。
# usage: run-bionic.sh [game|vulkan]   env from the app: GOW3_SCREEN_W/SCREEN_H、GOW3_GAME_DIR、
#        GOW3_APP0_DIR、GOW3_ANDROID_INPUT=1
set -eu
files=${FXD_FILES:-/data/user/0/com.gow3.android.bionic/files}
base="$files/gow3port"
bionic="$base/bionic"
export FXD_FILES="$files"
export PATH=/system/bin
export HOME="$files/home/gow3port"
export TMPDIR="$files/tmp" XDG_RUNTIME_DIR="$files/tmp"
# FEXCore（libgow3cpu）从这里读 Config.json（它只跑 guest 的代码：没有 x86 rootfs、loader
# 或它自己的 syscall）。
export FEX_APP_CONFIG_LOCATION="$HOME/.fex-emu/"
export FEX_APP_DATA_LOCATION="$HOME/.fex-emu/"
export FEX_APP_CACHE_LOCATION="$HOME/.cache/fex-emu/"
mkdir -p "$HOME/.fex-emu" "$HOME/.cache/fex-emu" "$TMPDIR"

# ---- 设置：gow3.ini（app 的设置界面写它）变成 GOW3_* 变量 ----
export GOW3_CONFIG="${GOW3_CONFIG:-$base/gow3.ini}"
[ -f "$GOW3_CONFIG" ] || cp "$base/gow3.default.ini" "$GOW3_CONFIG"

# 存档与日志落在稳定目录（app 传 GOW3_DATA_DIR=files/gow3data），不在 $base 下：RuntimeInstaller
# 升级时整体替换 gow3port，写在 gow3port 里的存档会被丢掉。默认值与 run-gow3.sh 一致。
data_root=${GOW3_DATA_DIR:-$files/gow3data}
export GOW3_DATA_DIR="$data_root"
export GOW3_USER_DIR="${GOW3_USER_DIR:-$data_root/user}"
export GOW3_LOG_DIR="${GOW3_LOG_DIR:-$data_root/logs}"
mkdir -p "$data_root" "$GOW3_USER_DIR" "$GOW3_LOG_DIR" "$GOW3_LOG_DIR/failed-shaders"

# ---- 派生运行参数（launcher 启动前写入 launch.env，与 launch_env.py --shell 同格式）----
# 与 run-gow3.sh 同一来源：DMEM/VBlank/补丁集是 compile_plan() 从实际补丁和设备预算推导出来的，
# 不能再由启动脚本猜。launch.env 缺失时退回下面的默认值。
launch_env="$base/data/launch.env"
derived() { sed -n "s/^export $1=//p" "$launch_env" 2>/dev/null | tail -n 1 | tr -d "'"; }
if [ -f "$launch_env" ]; then
    # GOW3_READBACK_LINEAR among them: launch_env marks it an Android-safe default, but the
    # built-in default is off, and without it the runtime reads textures with the wrong tiling and
    # the image comes out in corrupted bands (seen on the language screen). The graphics options
    # (upscaler, preset, resolution, fps) are deliberately not imported: those belong to the ini the
    # player set in the launcher, and launch_env does not know them.
    for key in GOW3_DMEM_MB GOW3_VBLANK_HZ GOW3_ENGINE_FPS GOW3_EBOOT_SHA256 \
               GOW3_GAME_TITLE GOW3_PATCH_STATUS GOW3_PATCHES \
               GOW3_DEFERRED_READBACK GOW3_STALE_READBACK GOW3_ASYNC_SHADERS \
               GOW3_READBACK_LINEAR; do
        value=$(derived "$key")
        [ -n "$value" ] && export "$key=$value"
    done
    echo "Derived patches: ${GOW3_PATCH_STATUS:-unknown}; ${GOW3_PATCHES:-none}; DMEM ${GOW3_DMEM_MB:-?}MiB, vblank ${GOW3_VBLANK_HZ:-?}Hz"
fi
setting() { sed -n "s/^$1=//p" "$GOW3_CONFIG" 2>/dev/null | tail -n 1; }
# app 的 pad 状态文件（PadBridge.java）与文字请求（角色名：它的触屏键盘）。
export GOW3_ANDROID_INPUT=1
export GOW3_PAD_FILE="$base/android-pad.state" GOW3_PAD_QUIET=1
# 震动回程：游戏把缩放后的强度写此文件(atomic rename，见 runtime_pad.c pad_vibration)，
# PadBridge 以 40ms 轮询并驱动 InputDevice 振动；缺失=无震动，不影响其余桥。
export GOW3_PAD_RUMBLE_FILE="$base/android-pad.rumble"
export GOW3_IME_FILE="$base/ime.request"
# 存档/读档/删除列表的请求文件(design: launcher 以原生对话框接管呈现,不再由 overlay 自绘)。
export GOW3_SAVE_DIALOG_FILE="$base/save-dialog.request"
# 渲染尺寸由 gow3.ini 的 render_resolution（分辨率补丁，准备阶段打进 eboot）决定；
# 输出 output_res（1920x1080、1280x720 或 854x480；缺省 720p）是 UI/呈现的最终尺寸。
output=$(setting output_res)
# 超分硬关闭（change replace-android-fsr3-with-gsr2 收尾决定）：时间域路径在 Android 尚未
# 接通（UI 合成钩子未验证），ini 的 upscaler 取值一律忽略。ini 默认与一次性迁移均为 off，
# 这里的硬关闭保证任何来源的取值都不会启用超分。接通后恢复白名单透传 off|gsr2|fsr3。
upscaler=off
case $output in 1920x1080|1280x720|854x480) ;; *) output=1280x720 ;; esac
export GOW3_FULLSCREEN=1 GOW3_UPSCALER=$upscaler
# GOW3_RENDER_RES 必须描述【真实补丁场景尺寸】（render_resolution 分辨率补丁打进 eboot 的
# scene 常量），供运行时 RenderTarget() 匹配游戏渲染目标（ui_draw/合成判定）与 camera
# 常量就绪前的场景回退。映射取 patches/*.xml 各补丁 Note 的权威尺寸，与
# SettingsDialog.SCENE_SIZES 同源；unknown/native → 游戏原生 1920x1080。
render_res=1920x1080
case $(setting render_resolution) in
    480p) render_res=854x480 ;; 540p) render_res=960x540 ;; 720p) render_res=1280x720 ;;
    900p) render_res=1600x900 ;; 1080p) render_res=1920x1080 ;; 1440p) render_res=2560x1440 ;;
    1800p) render_res=3200x1800 ;; 4K) render_res=3840x2160 ;;
esac
# 有效上屏面（GOW3_SCREEN_W/H 与 GOW3_OUTPUT_RES）：超分开启且 output_res 两方向都不小于
# 场景尺寸时取 output_res（超分放大输出），否则回落场景尺寸 1:1。上屏面不得小于场景——
# 「场景 > 上屏面」的降采样/重定向合成路径在 Android 未验证（1080p 黑屏，
# change android-present-surface-follows-render-resolution）。
ge_size() { # $1=a $2=b；a 的宽高都不小于 b 时返回 0
    [ "${1%x*}" -ge "${2%x*}" ] && [ "${1#*x}" -ge "${2#*x}" ]
}
screen=$render_res
case $upscaler in
    gsr2|fsr3)
        if ge_size "$output" "$render_res"; then
            case $output in 1920x1080|1280x720|854x480) screen=$output ;; esac
        fi
        ;;
esac
# 运行期调试通道：`preset [output [upscaler]]` 每秒轮询热切换（OnFrameStart 消费）；
# 开关位掩码 250ms 轮询热生效（位含义见 gpu/shim/gow3_toggles.h）。
# 都放 GOW3_DATA_DIR（gow3data，玩家数据目录）：runtime 升级重展开 gow3port 不会清掉。
export GOW3_PRESET_FILE="$data_root/gow3.preset"
export GOW3_TOGGLE_FILE="$data_root/gow3.toggles"
export GOW3_WINDOW_SIZE=$screen
export GOW3_OUTPUT_RES=$screen GOW3_LIVE_RES=0
# scaled_session 标记 + camera 常量就绪前的场景尺寸回退（真实值见上方 render_res 映射）。
# 桌面 live 路径在 Android 不可达（本脚本恒导出 GOW3_RENDER_RES）。
export GOW3_RENDER_RES=$render_res
# GPU 内存是手机的系统 RAM，和游戏的 DMEM 及 Android 共享；纹理收集器按预算工作，
# 空闲的游戏内存拷贝延后回收。
# DMEM 基线与 launch_env.py --base-dmem 7104 一致（launcher 同样用 7104）（release 补丁要求 7104MiB）；运行前派生出的
# patch_status.json effective.dmem_mb 是权威值，启动方应显式传 GOW3_DMEM_MB 覆盖。
export GOW3_DMEM_MB=${GOW3_DMEM_MB:-7104}
export GOW3_GC_BUDGET_MB=${GOW3_GC_BUDGET_MB:-4096} GOW3_VRAM_IDLE_SECONDS=${GOW3_VRAM_IDLE_SECONDS:-20}
# VSync（vsync=1）：app 每帧至少显示一个刷新周期、按序（app 里的 FIFO：libgow3gpu 在 HELLO 里
# 声明，gow3_present_proto.h）；0：显示最新帧，一个刷新周期里完成的帧被跳过。
if [ "$(setting vsync)" = 1 ]; then export GOW3_VSYNC=1; else export GOW3_VSYNC=0; fi

# ---- 帧生成（change add-native-frame-generation）：gow3_lsfg 引擎（libbloodframe.so）----
# gow3.ini 的 framegen_* 键在启动期种子到 gow3data/framegen.state（游戏内 250ms watcher
# 的热应用通道；抽屉 Apply 在游戏运行中重写同一文件）。DLL 查找顺序：游戏目录同级 →
# 应用私有 gow3port/（与 Home 卡片的供给契约一致）。libgow3gpu 仅在倍率≥2 时 dlopen
# libbloodframe.so；DLL/引擎缺失或能力不支持时一律回直通并在日志给出原因。
framegen_requested=$(setting framegen_multiplier)
case $framegen_requested in 2|3|4) [ "$(setting framegen_enabled)" = 1 ] || framegen_requested=0 ;; *) framegen_requested=0 ;; esac
if [ "$framegen_requested" != 0 ] && [ "$GOW3_VSYNC" != 1 ]; then
    echo "Frame generation needs FIFO (vsync=1): off"
    framegen_requested=0
fi
unset GOW3_LSFG_STATE_FILE GOW3_LSFG_DLL GOW3_LSFG_LIB
state_file="${GOW3_DATA_DIR:-$data_root}/framegen.state" # gow3data（与 launcher HUD 读取路径一致）
flow=$(setting framegen_flow_scale)
# 数值校验（0.25–1.00 连续合法；引擎/launcher 夹紧语义一致），非法回退默认 0.80
flow_ok=$(awk -v v="$flow" 'BEGIN{exit !(v >= 0.25 && v <= 1.0)}') || flow=0.80
printf 'multiplier=%s\nflow=%s\nperformance=1\nfp16=1\n' "$framegen_requested" "$flow" \
    > "$state_file.tmp" && mv "$state_file.tmp" "$state_file"
export GOW3_LSFG_STATE_FILE="$state_file"
dll=""
# 查找顺序与 gow3_lsfg_state.cpp FindDll 一致：游戏目录同级（持久位）→ 应用私有。
for d in "${GOW3_GAME_DIR%/}/.." "$files/gow3port"; do
    [ -n "$d" ] && [ -f "$d/Lossless.dll" ] && { dll="$d/Lossless.dll"; break; }
done
[ -n "$dll" ] && export GOW3_LSFG_DLL="$dll"
[ -f "$bionic/lib/libbloodframe.so" ] && export GOW3_LSFG_LIB="$bionic/lib/libbloodframe.so"
if [ "$framegen_requested" != 0 ]; then
    [ -n "$dll" ] || echo "Frame generation: no Lossless.dll (game folder or $files/gow3port): off"
    [ -f "$bionic/lib/libbloodframe.so" ] || echo "Frame generation: libbloodframe.so missing: off"
fi

# ---- GPU 驱动（gow3.ini gpu_driver）----
# "system"：手机自带驱动，经它的 Vulkan loader（GOW3_VULKAN_DRIVER 未设置）；一个 id 是
# files/drivers/<name>/（DriverManager.java 生成，driver.lib 命名库文件，adrenotools 风格的
# Turnip / PanVK for Android），直接加载。空/"auto"/已消失的 id：自动选择。
gpu_driver=$(setting gpu_driver)
gpu_auto=0
case $gpu_driver in
    ''|auto) gpu_auto=1; gpu_driver= ;;
    .*|*/*|system) gpu_driver= ;;
    *) if [ ! -f "$files/drivers/$gpu_driver/driver.lib" ]; then
           echo "GPU driver $gpu_driver is gone: automatic choice"; gpu_auto=1; gpu_driver=
       fi ;;
esac
unset GOW3_VULKAN_DRIVER
if [ -n "$gpu_driver" ]; then
    driver_lib=$(cat "$files/drivers/$gpu_driver/driver.lib" 2>/dev/null || true)
    case $driver_lib in
        ''|*/*|.*) echo "GPU driver $gpu_driver is not installed: system driver" ;;
        *)
            if [ -f "$files/drivers/$gpu_driver/$driver_lib" ]; then
                export GOW3_VULKAN_DRIVER="$files/drivers/$gpu_driver/$driver_lib"
                echo "GPU driver: $gpu_driver (installed from a zip)"
            else
                echo "GPU driver $gpu_driver has no library: system driver"
            fi ;;
    esac
fi
[ -n "${GOW3_VULKAN_DRIVER:-}" ] || echo 'GPU driver: system (stock)'

# ---- 声音和画面 ----
# mixer 把 s16 立体声 48 kHz 写进 app 播放的 FIFO（AudioBridge.java），只在 app 建了它时：
# 没人读的 FIFO 会填满。SDL disk 驱动是文件 sink（不是播放设备）：512 帧周期、s16 立体声，
# 与 gow3 的 android_disk 音频路径一致（runtime_audio.c）。libgow3gpu 连 app 的 socket 上屏。
if [ -p "$base/audio.fifo" ] && [ -f "$base/audio.fifo.ready" ]; then
    export SDL_AUDIO_DRIVER=disk SDL_AUDIO_DISK_OUTPUT_FILE="$base/audio.fifo"
    export SDL_AUDIO_DISK_TIMESCALE=0 SDL_AUDIO_DEVICE_SAMPLE_FRAMES=512
else
    echo "Android audio bridge is not ready (expected $base/audio.fifo.ready)" >&2
    exit 12
fi
export GOW3_PRESENT_SOCKET="$files/gow3present.sock"
export GOW3_SCREEN_W="${GOW3_SCREEN_W:-${screen%x*}}" GOW3_SCREEN_H="${GOW3_SCREEN_H:-${screen#*x}}"
# libgow3gpu：驱动拒绝的 pipeline 的 SPIR-V 写在这里（vk_shader_util.cpp）。
export GOW3_FAILED_SHADER_DIR="$GOW3_LOG_DIR/failed-shaders"
cp "$base/fex-compatible.json" "$HOME/.fex-emu/Config.json"
# 本设备的本地覆盖（实验、调参）：最后 source，不是 runtime 的一部分。
[ -f "$base/bionic-local.sh" ] && . "$base/bionic-local.sh"
echo "Frame rate: limit $(setting fps_limit) (default 30), VSync ${GOW3_VSYNC}"
cd "$base"
[ -x "$bionic/bin/gow3-probe" ] || { echo "The Bionic runtime is missing: $bionic" >&2; exit 2; }

# ---- 游戏文件和补丁 ----
# 选中的游戏目录（GOW3_GAME_DIR），默认 runtime 自带的。补丁是 launcher 按 patches.py 的
# compile_plan()/publish_plan() 链路，在**运行前**（宿主机或设备）对玩家游戏生成单个
# G3PATCH2 patches.bin（probe 只消费这一个文件，不再拼接多个 --patches）。构建期只打包
# runtime 与补丁规则 XML（assets/God_of_War_III_Remastered_*.xml）。玩家游戏派生产物
# （boot-linked.bin、content.bin、patches.bin）单独部署到 base/data，不进 APK。
if [ "${1:-game}" = game ]; then
    # 启动器的升级合并视图同时用于编译与 app0；摘要不得回头校验基础游戏的 eboot。
    game_dir=${GOW3_APP0_DIR:-${GOW3_GAME_DIR:-$base/game}}
    [ -f "$game_dir/eboot.bin" ] || { echo "Game missing: $game_dir" >&2; exit 2; }
    app0_dir=$game_dir
    # launcher 启动前由 libgow3prepare 编译（compile_plan/publish_plan 的等价物）的单个补丁文件。
    [ -f "$base/data/patches.bin" ] || { echo "The patches are missing: $base/data/patches.bin (the launcher prepares them on launch)" >&2; exit 2; }
    # 身份校验：boot-linked.bin / content.bin / patches.bin 是针对某一个 eboot 派生的。换了游戏
    # 目录或游戏版本后旧产物仍会被原样加载（app0 却指向另一个版本），所以比对摘要不符即拒绝启动，
    # 而不是把 A 的映像配 B 的 app0 用。
    if [ -n "${GOW3_EBOOT_SHA256:-}" ]; then
        actual=$(sha256sum "$game_dir/eboot.bin" 2>/dev/null | cut -d' ' -f1)
        if [ "$actual" != "$GOW3_EBOOT_SHA256" ]; then
            echo "The derived runtime is for a different game build ($GOW3_EBOOT_SHA256) but" >&2
            echo "  $game_dir/eboot.bin is $actual" >&2
            echo "Re-run launch_env.py for this game before starting." >&2
            exit 2
        fi
    fi
    patch_args="--patches $base/data/patches.bin"
fi

# Bionic 库只在 runtime 目录：app 自己的环境没有库路径。
LD_LIBRARY_PATH="$bionic/lib"; export LD_LIBRARY_PATH
unset LD_PRELOAD
case ${1:-game} in
    vulkan)
        exec "$bionic/bin/gow3-probe" --vulkan-only
        ;;
    game)
        set -- "$bionic/bin/gow3-probe"
        # GOW3_THOR_CPUS：游戏可用的 CPU（taskset -c list），例如 3-7 只大核。
        [ -n "${GOW3_THOR_CPUS:-}" ] && set -- /system/bin/taskset -c "$GOW3_THOR_CPUS" "$@"
        set -- "$@" "$base/data/boot-linked.bin" $patch_args
        exec "$@" \
            --content-profile "$base/data/content.bin" \
            --app0 "$app0_dir" --user "$GOW3_USER_DIR" --timeout "${GOW3_TIMEOUT:-0}"
        ;;
    *) echo 'usage: run-bionic.sh game|vulkan' >&2; exit 2 ;;
esac
