package com.gow3.android.data.model

import com.gow3.android.data.PatchCatalog


import com.gow3.android.R

/**
 * 启动参数的顶层类别，驱动 Settings 配置页顶部的分类 tab 栏。
 *
 * 每个枚举值带标题字符串资源 id 与稳定的声明顺序；新增类别只需追加枚举值
 * （如未来 FEX 的 TRANSLATOR），新增参数只需声明 [LaunchParam.category]，
 * 均不影响 Home 页展示。音频为预留分类，当前没有参数，UI 显示「暂无配置」占位；
 * 驱动由 DriverManagerCard 落位；常规（原存储分类，redesign-android-launcher-home）
 * 排在第一位，承载游戏目录与升级补丁目录行（StorageSection）与语言入口（LanguageCard），
 * 是首启之后目录与语言修改的常驻位置。
 */
internal enum class LaunchCategory(val titleRes: Int) {
    GENERAL(R.string.param_category_general),
    GRAPHICS(R.string.param_category_graphics),
    PERFORMANCE(R.string.param_category_performance),
    CONTROLS(R.string.param_category_controls),
    AUDIO(R.string.param_category_audio),
    DRIVER(R.string.param_category_driver),
    ;

    companion object {
        /** Settings 页的默认分类：首次打开 Settings、以及持久化值失效时的落点。 */
        val DEFAULT: LaunchCategory get() = GENERAL

        /**
         * 从 rememberSaveable 持久化的枚举名恢复分类。
         *
         * LauncherScreen 把分类名以字符串存进 saved instance state（Bundle 契约下
         * 最稳的形态），因此恢复时可能遇到改名前的旧值（如 STORAGE）、空值或脏值：
         * 一律回落 [DEFAULT]，绝不抛异常。声明顺序（[entries]）即分类栏渲染顺序。
         */
        fun fromSaved(name: String?): LaunchCategory =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * 一个可编辑的启动参数：其 gow3.ini 键、显示标签、类别以及 Android 运行时
 * 实际消费的取值域。`defaultValue` 是键缺失时 UI 显示的值。
 *
 * 显示标签、类别与说明使用字符串资源 id（labelRes / category / descriptionRes），
 * 由 UI 层通过 Context 解析，数据层保持纯 JVM 结构、不依赖 Android。
 */
internal data class LaunchParam(
    val key: String,
    val labelRes: Int,
    val category: LaunchCategory,
    val options: List<LaunchOption>,
    val defaultValue: String,
    val descriptionRes: Int? = null,
    val enabled: Boolean = true,
)

internal data class LaunchOption(val value: String, val labelRes: Int)

/**
 * launcher 编辑的启动参数，由 Bionic 运行链消费的 gow3.ini 键支撑。取值域以
 * `gpu/shim/gow3_settings.cpp` 的运行时解析器与 `tools/android/device/run-bionic.sh`
 * 的消费为准，只收录有实际消费方的键（`effect_*`、`skip_intro`、`mouse_look`、
 * `debug_camera`、`debug_menu` 等无消费方键不得加入）；当取值域不一致时
 * （fps_limit：Java 前端曾提供 40/45，但 `ParseFpsLimit` 把它们映射为 0）
 * 以运行时取值域为准，确保每个选项都实际生效。
 *
 * 各参数的默认值均为移动端优化值（render_resolution=720p、fps_limit=60、
 * engine_fps=60、sharpen=0、sharpness=0.50；display_mode 已从
 * 可编辑项移除，固定 fullscreen；show_fps 已退役——引擎 FPS 叠加层由 launcher
 * 侧性能 HUD 取代，不再属于启动参数）。这些默认值 MUST 与
 * `tools/android/config/gow3-default.ini`（首启落盘为 gow3.ini 的基准）保持一致，
 * 避免 UI 展示默认与实际运行默认漂移。分辨率维持既有 `render_resolution` 轨道，
 * 不暴露 `preset` / `output_res` 双轨（见 change design D3）。
 */
internal object LaunchParams {
    val all: List<LaunchParam> = listOf(
        // upscaler 行已移除（change replace-android-fsr3-with-gsr2 收尾）：时间域超分路径
        // 在 Android 尚未接通，设置页不再提供该选项；ini 默认与一次性迁移均为 off，
        // run-bionic.sh 硬性 upscaler=off 兜底。接通后把 LaunchParam 块加回即可。
        LaunchParam(
            key = "render_resolution", labelRes = R.string.param_resolution, category = LaunchCategory.GRAPHICS,
            options = listOf(
                LaunchOption("native", R.string.param_option_native),
                LaunchOption("480p", R.string.param_option_480p),
                LaunchOption("540p", R.string.param_option_540p),
                LaunchOption("720p", R.string.param_option_720p),
                LaunchOption("900p", R.string.param_option_900p),
                LaunchOption("1080p", R.string.param_option_1080p),
                LaunchOption("1440p", R.string.param_option_1440p),
                LaunchOption("1800p", R.string.param_option_1800p),
                LaunchOption("4K", R.string.param_option_4k),
            ),
            // 移动端默认 720p 内部分辨率，超分与锐化默认关闭。
            // 与 tools/android/config/gow3-default.ini 保持一致。
            defaultValue = "720p",
            descriptionRes = R.string.param_desc_render_patch,
        ),
        LaunchParam(
            key = "anisotropy", labelRes = R.string.param_anisotropy, category = LaunchCategory.GRAPHICS,
            options = listOf(
                LaunchOption("0", R.string.param_option_game_default),
                LaunchOption("2", R.string.param_option_2x),
                LaunchOption("4", R.string.param_option_4x),
                LaunchOption("8", R.string.param_option_8x),
                LaunchOption("16", R.string.param_option_16x),
            ),
            defaultValue = "0",
            descriptionRes = R.string.param_desc_anisotropy,
        ),
        LaunchParam(
            // FSR 后处理锐化开关；upscaler=off 时运行时安全忽略（不联动禁用，
            // 避免 UI 引入跨参数状态依赖，见 change design Risks）。
            key = "sharpen", labelRes = R.string.param_sharpen, category = LaunchCategory.GRAPHICS,
            options = listOf(
                LaunchOption("0", R.string.param_option_off),
                LaunchOption("1", R.string.param_option_on),
            ),
            defaultValue = "0",
            descriptionRes = R.string.param_desc_sharpen,
        ),
        LaunchParam(
            // RCAS 锐化强度，运行时 Clamp 到 0.0–2.0；离散档位与 ParamRow 下拉交互一致，
            // 连续 SeekBar 留待有真实需求再加（change design D3）。
            key = "sharpness", labelRes = R.string.param_sharpness, category = LaunchCategory.GRAPHICS,
            options = listOf(
                LaunchOption("0.00", R.string.param_option_sharpness_000),
                LaunchOption("0.25", R.string.param_option_sharpness_025),
                LaunchOption("0.50", R.string.param_option_sharpness_050),
                LaunchOption("0.75", R.string.param_option_sharpness_075),
                LaunchOption("1.00", R.string.param_option_sharpness_100),
                LaunchOption("1.50", R.string.param_option_sharpness_150),
                LaunchOption("2.00", R.string.param_option_sharpness_200),
            ),
            defaultValue = "0.50",
            descriptionRes = R.string.param_desc_sharpness,
        ),
        LaunchParam(
            // 显示几何（应用侧显示桥 GameActivity 启动 Gow3Present 时读取）三档：
            // 0=等比适配（留黑边，默认），1=拉伸铺满（不保持比例），2=裁切铺满（等比覆盖、裁掉溢出）。
            key = "display_stretch", labelRes = R.string.param_display_stretch, category = LaunchCategory.GRAPHICS,
            options = listOf(
                LaunchOption("0", R.string.param_option_display_fit),
                LaunchOption("1", R.string.param_option_display_stretch),
                LaunchOption("2", R.string.param_option_display_crop),
            ),
            defaultValue = "0",
            descriptionRes = R.string.param_desc_display_stretch,
        ),
        LaunchParam(
            key = "vibration", labelRes = R.string.param_vibration, category = LaunchCategory.CONTROLS,
            options = listOf(
                LaunchOption("0", R.string.param_option_off),
                LaunchOption("25", R.string.param_option_25_percent),
                LaunchOption("50", R.string.param_option_50_percent),
                LaunchOption("75", R.string.param_option_75_percent),
                LaunchOption("100", R.string.param_option_100_percent),
            ),
            defaultValue = "100",
            descriptionRes = R.string.param_desc_vibration,
        ),
        LaunchParam(
            key = "fps_limit", labelRes = R.string.param_fps_limit, category = LaunchCategory.PERFORMANCE,
            options = listOf(
                LaunchOption("30", R.string.param_option_30),
                LaunchOption("60", R.string.param_option_60),
                LaunchOption("120", R.string.param_option_120),
                LaunchOption("240", R.string.param_option_240),
                LaunchOption("0", R.string.param_option_unlimited),
            ),
            // 移动端优化默认值：以 60 封顶，避免无限帧率导致发热与耗电。
            // 与 tools/android/config/gow3-default.ini 保持一致。
            defaultValue = "60",
        ),
        LaunchParam(
            key = "engine_fps", labelRes = R.string.param_engine_fps, category = LaunchCategory.PERFORMANCE,
            options = listOf(
                LaunchOption("60", R.string.param_option_engine_60),
                LaunchOption("120", R.string.param_option_120),
                LaunchOption("240", R.string.param_option_240),
            ),
            // 移动端优化默认值：游戏原生引擎速度，最稳定、最省电。
            // 与 tools/android/config/gow3-default.ini 保持一致。
            defaultValue = "60",
            descriptionRes = R.string.param_desc_engine_fps,
        ),
        LaunchParam(
            key = "vsync", labelRes = R.string.param_vsync, category = LaunchCategory.PERFORMANCE,
            options = listOf(
                LaunchOption("0", R.string.param_option_off),
                LaunchOption("1", R.string.param_option_on),
            ),
            defaultValue = "1",
            descriptionRes = R.string.param_desc_vsync,
        ),
        LaunchParam(
            key = "frames_queued", labelRes = R.string.param_frames_queued, category = LaunchCategory.PERFORMANCE,
            options = listOf(
                LaunchOption("1", R.string.param_option_frames_1),
                LaunchOption("2", R.string.param_option_frames_2),
                LaunchOption("0", R.string.param_option_unlimited),
            ),
            defaultValue = "2",
            descriptionRes = R.string.param_desc_frames_queued,
        ),
        // show_fps（引擎自带 FPS 叠加层）已退役：帧率显示统一由 launcher 侧性能 HUD
        // （PerfHudOverlay）提供，历史残留的 show_fps=1 由 Gow3Data.prepare 归零，
        // 这里不再提供参数行（change design D7）。
    )

    /** Settings 顶部分类栏顺序：按枚举声明顺序取全集（含空分类）。 */
    fun categories(): List<LaunchCategory> = LaunchCategory.entries

    /** 某一分类下的参数（按声明顺序）；空分类返回空列表。 */
    fun forCategory(category: LaunchCategory): List<LaunchParam> = all.filter { it.category == category }

    /** 补丁类快捷入口只展示当前目录真正提供的档位；无目录时禁止修改。 */
    fun forCatalog(catalog: PatchCatalog?): List<LaunchParam> = all.map { param ->
        when (param.key) {
            "render_resolution" -> param.copy(
                options = param.options.filter { option ->
                    option.value == "native" || catalog?.patches?.any { it.resolution == option.value } == true
                },
                enabled = catalog != null,
            )
            "engine_fps" -> param.copy(
                options = param.options.filter { option ->
                    option.value == "60" || catalog?.patches?.any { it.fps == option.value } == true
                },
                enabled = catalog != null,
            )
            else -> param
        }
    }

    fun isPatchSetting(key: String): Boolean = key == "render_resolution" || key == "engine_fps"

    /** 参数的当前原始值；缺失或为空时返回其默认值。 */
    fun valueOf(ini: Map<String, String>, param: LaunchParam): String {
        val v = ini[param.key]?.trim()
        return if (v.isNullOrEmpty()) param.defaultValue else v
    }

    /** 引擎帧率默认值（移动端优化，与 fps_limit 档位约束共用）。 */
    const val DEFAULT_ENGINE_FPS = 60

    /**
     * 按当前配置求生效档位（change preset-resolution-only-fps-independent）：
     * `fps_limit`（帧率上限）的可用档位上限不超过当前 `engine_fps`（引擎帧率），
     * `0`（不限）除外；engine_fps 键缺失或非法时按默认 60 处理。其余参数原样返回。
     * 只约束可选项，不改变已保存的 `fps_limit` 值本身。
     */
    fun effectiveOptions(param: LaunchParam, values: Map<String, String>): List<LaunchOption> {
        if (param.key != "fps_limit") return param.options
        val cap = values["engine_fps"]?.toIntOrNull() ?: DEFAULT_ENGINE_FPS
        return param.options.filter { option ->
            option.value == "0" || (option.value.toIntOrNull() ?: 0) <= cap
        }
    }

    /** 当前值对应的选项；未知值返回 null（由 UI 层回退到原始值）。 */
    fun selectedOption(ini: Map<String, String>, param: LaunchParam): LaunchOption? {
        val v = valueOf(ini, param)
        return param.options.firstOrNull { it.value == v }
    }
}
