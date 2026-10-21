package com.gow3.android.data.model

import com.gow3.android.data.PatchCatalog

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LaunchParamsTest {
    private fun catalog(): PatchCatalog = File(requireNotNull(System.getProperty("gow3.patchCatalogDir")),
        "God_of_War_III_Remastered_CUSA01740.xml").inputStream().use {
        PatchCatalog.parse(it, "CUSA01740", "01.00")
    }

    @Test fun `categories follow a stable order and every param belongs to exactly one`() {
        // GENERAL（原 STORAGE，承载目录行与语言入口的功能卡 slot，本身没有参数）
        // 排在第一位，声明顺序即 Settings 分类栏顺序。
        assertEquals(
            listOf(LaunchCategory.GENERAL, LaunchCategory.GRAPHICS, LaunchCategory.PERFORMANCE, LaunchCategory.CONTROLS, LaunchCategory.AUDIO, LaunchCategory.DRIVER),
            LaunchParams.categories(),
        )
        // 每个参数都有类别，参数键不重复，且「类别+键」组合不重复（归属唯一）。
        assertTrue(LaunchParams.all.all { it.category in LaunchCategory.entries })
        assertEquals(LaunchParams.all.size, LaunchParams.all.map { it.key }.distinct().size)
        assertEquals(LaunchParams.all.size, LaunchParams.all.map { it.category to it.key }.distinct().size)
    }

    /** rememberSaveable 只存枚举名：有效名原样恢复，改名前的旧值/空值/脏值一律回落默认。 */
    @Test fun `fromSaved resolves persisted names and falls back to the default category`() {
        assertEquals(LaunchCategory.GENERAL, LaunchCategory.fromSaved("GENERAL"))
        assertEquals(LaunchCategory.GRAPHICS, LaunchCategory.fromSaved("GRAPHICS"))
        assertEquals(LaunchCategory.GENERAL, LaunchCategory.fromSaved("STORAGE"))
        assertEquals(LaunchCategory.GENERAL, LaunchCategory.fromSaved(null))
        assertEquals(LaunchCategory.GENERAL, LaunchCategory.fromSaved(""))
        assertEquals(LaunchCategory.GENERAL, LaunchCategory.fromSaved("whatever"))
        assertEquals(LaunchCategory.GENERAL, LaunchCategory.DEFAULT)
    }

    @Test fun `graphics category holds the image quality params`() {
        assertEquals(
            // upscaler 行已随超分收尾移除（时间域路径未接通，run-bionic 硬性 off）
            setOf("render_resolution", "anisotropy", "sharpen", "sharpness", "display_stretch"),
            LaunchParams.forCategory(LaunchCategory.GRAPHICS).map { it.key }.toSet(),
        )
    }

    @Test fun `performance and controls categories hold the remaining params`() {
        assertEquals(
            // show_fps 已随引擎 FPS 显示退役移出可编辑集（change design D7）
            setOf("fps_limit", "engine_fps", "vsync", "frames_queued"),
            LaunchParams.forCategory(LaunchCategory.PERFORMANCE).map { it.key }.toSet(),
        )
        assertEquals(
            setOf("vibration"),
            LaunchParams.forCategory(LaunchCategory.CONTROLS).map { it.key }.toSet(),
        )
    }

    @Test fun `audio driver and general categories hold no inline params`() {
        assertTrue(LaunchParams.forCategory(LaunchCategory.AUDIO).isEmpty())
        assertTrue(LaunchParams.forCategory(LaunchCategory.DRIVER).isEmpty())
        // 常规/驱动分类的内容由功能卡 slot 提供（SettingsEntryTest 覆盖），不占用参数轨道。
        assertTrue(LaunchParams.forCategory(LaunchCategory.GENERAL).isEmpty())
    }

    /** 配置页只收录运行链有消费方的键；死键一旦回流即测试失败。 */
    @Test fun `unconsumed keys are never exposed`() {
        val keys = LaunchParams.all.map { it.key }.toSet()
        for (dead in setOf(
            "effect_chromatic_aberration", "effect_dof", "effect_motion_blur", "effect_ssao",
            "effect_game_aa", "effect_dynamic_shadows", "effect_ssr",
            "skip_intro", "mouse_look", "debug_camera", "debug_menu", "overlay_mouse",
            // preset 在 Android 已无消费方（change D9）；output_res 只由游戏内抽屉的
            // 「超分输出」组写，不进设置页参数轨道。
            "preset", "output_res", "display_mode",
            // upscaler 随超分收尾从设置页移除（时间域路径未接通，运行链硬性 off）
            "upscaler",
            // show_fps 已随引擎 FPS 显示退役（change design D7）：引擎叠加层由 launcher HUD 取代
            "show_fps",
        )) {
            assertFalse("no UI for unconsumed key $dead", dead in keys)
        }
    }

    @Test fun `mobile optimized defaults are not regressed`() {
        val empty = emptyMap<String, String>()
        val byKey = LaunchParams.all.associateBy { it.key }
        // upscaler 不在设置页参数轨道（超分硬关闭）；ini 默认与迁移保证 off
        assertFalse("upscaler" in byKey)
        assertEquals("720p", LaunchParams.valueOf(empty, byKey.getValue("render_resolution")))
        assertEquals("0", LaunchParams.valueOf(empty, byKey.getValue("anisotropy")))
        assertEquals("0", LaunchParams.valueOf(empty, byKey.getValue("sharpen")))
        assertEquals("0.50", LaunchParams.valueOf(empty, byKey.getValue("sharpness")))
        assertEquals("0", LaunchParams.valueOf(empty, byKey.getValue("display_stretch")))
        assertEquals("60", LaunchParams.valueOf(empty, byKey.getValue("fps_limit")))
        assertEquals("60", LaunchParams.valueOf(empty, byKey.getValue("engine_fps")))
        assertEquals("1", LaunchParams.valueOf(empty, byKey.getValue("vsync")))
        assertEquals("2", LaunchParams.valueOf(empty, byKey.getValue("frames_queued")))
        assertEquals("100", LaunchParams.valueOf(empty, byKey.getValue("vibration")))
    }

    @Test fun `explicit frame queue preferences survive the mobile default`() {
        val param = LaunchParams.all.first { it.key == "frames_queued" }
        for (value in listOf("0", "1", "2")) {
            assertEquals(value, LaunchParams.valueOf(mapOf("frames_queued" to value), param))
        }
    }

    @Test fun `sharpness offers the discrete clamp-domain tiers`() {
        val sharpness = LaunchParams.all.first { it.key == "sharpness" }
        assertEquals(listOf("0.00", "0.25", "0.50", "0.75", "1.00", "1.50", "2.00"),
            sharpness.options.map { it.value })
    }

    @Test fun `patch settings are gated without a catalog and filtered with one`() {
        val noCatalog = LaunchParams.forCatalog(null).associateBy { it.key }
        assertFalse(noCatalog.getValue("render_resolution").enabled)
        assertFalse(noCatalog.getValue("engine_fps").enabled)
        assertEquals(setOf("native"), noCatalog.getValue("render_resolution").options.map { it.value }.toSet())
        assertEquals(setOf("60"), noCatalog.getValue("engine_fps").options.map { it.value }.toSet())
        assertTrue(LaunchParams.isPatchSetting("render_resolution"))
        assertTrue(LaunchParams.isPatchSetting("engine_fps"))
        assertFalse(LaunchParams.isPatchSetting("upscaler"))
        assertFalse(LaunchParams.isPatchSetting("sharpen"))

        // CUSA01740：有 480p..1080p 分辨率补丁，但没有帧率补丁。
        val c = catalog()
        val gated = LaunchParams.forCatalog(c).associateBy { it.key }
        assertTrue(gated.getValue("render_resolution").enabled)
        assertEquals(setOf("native", "480p", "540p", "720p", "900p", "1080p"),
            gated.getValue("render_resolution").options.map { it.value }.toSet())
        assertEquals(setOf("60"), gated.getValue("engine_fps").options.map { it.value }.toSet())
        // 非补丁参数不受 catalog 影响（含无目录时保持可编辑的 display_stretch）。
        assertTrue(noCatalog.getValue("display_stretch").enabled)
        assertFalse("upscaler" in gated)
        assertTrue(gated.getValue("sharpness").enabled)
        assertTrue(gated.getValue("display_stretch").enabled)

        // CUSA01715：带 120/240 帧率补丁，档位应随目录出现。
        val c1715 = File(File(requireNotNull(System.getProperty("gow3.patchCatalogDir"))),
            "God_of_War_III_Remastered_CUSA01715.xml").inputStream().use {
            PatchCatalog.parse(it, "CUSA01715", "01.02")
        }
        val gated1715 = LaunchParams.forCatalog(c1715).associateBy { it.key }
        assertEquals(setOf("60", "120", "240"), gated1715.getValue("engine_fps").options.map { it.value }.toSet())
    }

    @Test fun `fps_limit tiers are capped by the current engine_fps`() {
        val byKey = LaunchParams.all.associateBy { it.key }
        val fpsLimit = byKey.getValue("fps_limit")
        fun tiers(engineFps: String?): List<String> =
            LaunchParams.effectiveOptions(
                fpsLimit,
                if (engineFps == null) emptyMap() else mapOf("engine_fps" to engineFps),
            ).map { it.value }

        assertEquals(listOf("30", "60", "0"), tiers("60"))            // 60：上限 60，0=不限
        assertEquals(listOf("30", "60", "120", "0"), tiers("120"))    // 120：上限 120
        assertEquals(listOf("30", "60", "120", "240", "0"), tiers("240"))
        assertEquals(listOf("30", "60", "0"), tiers(null))            // 缺失：按默认 60
        assertEquals(listOf("30", "60", "0"), tiers("abc"))           // 非法：按默认 60
        // 非 fps_limit 参数原样返回。
        val engine = byKey.getValue("engine_fps")
        assertEquals(engine.options, LaunchParams.effectiveOptions(engine, mapOf("engine_fps" to "120")))
    }
}
