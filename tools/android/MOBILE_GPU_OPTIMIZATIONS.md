# Android GPU 优化与对照开关

本轮将 `gow3_android_daedalus_fork` 的移动端优化适配到当前渲染器，
其中原生 SRT、精确拷贝区间和驱动缓存源自 `239b7df`。
保留当前树的 renderpass 配对保护、逐信号量等待阶段、回读依赖和升级包输入修复。
代码、宿主测试和 APK 构建通过，不代表已经恢复同场景稳定 60 FPS。

## 默认行为

| 路径 | 行为 | 启动时对照开关 |
| --- | --- | --- |
| 空回读队列 | 不等待 CPU 上传，仍回收已完成回读 | `GOW3_READBACK_EMPTY_FAST=0` 恢复原等待 |
| GPU 提交 | Android 最多领先 8 次提交，取消提交后的无条件轮询 | `GOW3_VK_POLL_EACH_SUBMIT=1` 恢复原策略 |
| AHB 呈现 | Turnip 导出已提交的异步 sync-fd；其他驱动保持保守策略 | `GOW3_AHB_SYNC=complete` 回退，`async` 显式启用 |
| 绘制 | Android 默认直通，不创建 draw-pipe | `GOW3_DRAW_PIPE=1` 恢复流水绘制 |
| 预备绘制 | Android 默认 0 个推测 worker，不启动 scanner | `GOW3_PREP_WORKERS` 沿用原覆盖范围 |
| Shader 编译 | Android 编译 2、capture 1；capture 为低优先级 | `GOW3_GPU_COMPILER_WORKERS=1..6` |
| 缓存预热 | Android 最多默认 2 个 worker，桌面默认不变 | `GOW3_GPU_WARMUP_WORKERS=1..6`；前台仍需原有 `GOW3_PARALLEL_WARMUP` |
| 排队帧 | Android 缺省为 2，桌面缺省仍为 1 | `frames_queued=0/1/2`、`GOW3_FRAMES_AHEAD` 保持优先 |
| SRT 遍历 | ARM64 将可移植字节码编译为本机指令，故障后回退解释器 | `GOW3_SRT_NATIVE=0` |
| HLE 拷贝 | 保留 64 KiB 分组与 MultiCopy，只同步实际区间，保留空洞 | `GOW3_COPY_EXACT_RANGES=0` |
| 驱动缓存 | 无 pipeline-binary 持久化时使用传统 VkPipelineCache，约每 30 秒保存 | `GOW3_DRIVER_CACHE=0` |
| FSR1/RCAS | Android 按需创建及调用；直通不创建无用管线 | 原有 FSR1/RCAS 设置仍有效 |

这些覆盖必须在游戏进程启动前设置。已有 ini 中的排队帧 `0` 或 `1`
不会因升级被自动改成 `2`，需要测试 2 帧时应明确选择该值。
AHB 导出失败会等待设备空闲、重建未转移 payload 的信号量，并回退保守模式。
传统驱动缓存按 GPU、驱动版本和 UUID 分目录，读取时校验长度、身份和校验和；
损坏或驱动拒绝时使用空缓存，不删除现有 shader 缓存。

## 回归验证

```sh
python3 -m unittest discover -s tests
ninja -C out/bionic/gpu gow3gpu -j4
android-launcher/gradlew -p android-launcher :app:testDebugUnitTest \
  --tests com.gow3.android.data.model.LaunchParamsTest \
  --tests com.gow3.android.data.GameUpdateViewTest :app:assembleDebug --offline
```

`test_android_mobile_optimizations.py` 直接执行生产辅助逻辑及抽取的实际方法，
覆盖队列边界、回退开关、默认值与显式设置、页锁顺序、缓存损坏和检查点退出。
ARM64 指令验证使用 `unicorn==2.1.4`，建议安装在临时虚拟环境中；
缺少该包时该项跳过。macOS 的沙箱可能限制 Unicorn JIT，验证须取得相应执行权限。

真机对照必须保持同一设备、场景、存档、540p、驱动、补丁选择、温度与超分/插帧状态，
每次只覆盖一项策略，比较稳定区间帧时间。不能用菜单或后台吞吐量推断游戏帧率。
升级包 eboot 和去特效补丁的实际命中应另行核验，不因本轮优化重置补丁配置。
