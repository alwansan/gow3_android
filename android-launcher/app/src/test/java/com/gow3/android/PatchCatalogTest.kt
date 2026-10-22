package com.gow3.android

import com.gow3.android.core.settings.Gow3Settings
import com.gow3.android.data.CatalogProblem
import com.gow3.android.data.PatchCatalog
import com.gow3.android.data.PatchCatalogException
import com.gow3.android.data.PatchSource
import com.gow3.android.data.toNativeInput
import com.gow3.android.data.model.LaunchParams
import com.gow3.android.data.model.MobilePresets

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PatchCatalogTest {
    @get:Rule val temp = TemporaryFolder()
    private val catalogs = File(requireNotNull(System.getProperty("gow3.patchCatalogDir")))
    private fun catalog(title: String = "CUSA01740", version: String = "01.00") =
        File(catalogs, "God_of_War_III_Remastered_$title.xml").inputStream().use { PatchCatalog.parse(it, title, version) }
    private val native = mapOf("render_resolution" to "native", "engine_fps" to "60")

    @Test fun `1740 includes all fourteen patches without forced defaults`() {
        val c = catalog()
        assertEquals(14, c.patches.size)
        assertTrue(c.selected(native).isEmpty())
        assertEquals(setOf("Resolution Patch - 720p"), c.selected(native + ("render_resolution" to "720p")))
    }

    @Test fun `resolution switches are exclusive and turning off returns to native`() {
        val c = catalog()
        val p720 = c.patches.first { it.resolution == "720p" }
        val p540 = c.patches.first { it.resolution == "540p" }
        var ini = native + c.toggle(native, p720, true)
        ini += c.toggle(ini, p540, true)
        assertEquals(setOf(p540.name), c.selected(ini))
        ini += c.toggle(ini, p540, false)
        assertEquals("native", ini["render_resolution"])
        assertTrue(c.selected(ini).isEmpty())
    }

    @Test fun `effects and optional startup fix persist and can be cleared`() {
        val c = catalog()
        val bloom = c.patches.first { it.name == "Disable Bloom" }
        val fix = c.patches.first { it.name == "Bug Fix - Startup Black Screen" }
        var ini = native + c.toggle(native, bloom, true)
        ini += c.toggle(ini, fix, true)
        val file = temp.newFile("gow3.ini")
        assertTrue(Gow3Settings.update(file, ini))
        assertEquals(setOf(bloom.name, fix.name), c.selected(Gow3Settings.read(file)))
        ini += c.toggle(ini, bloom, false)
        ini += c.toggle(ini, fix, false)
        assertEquals("", ini[c.selectionKey])
        assertTrue(c.selected(ini).isEmpty())
    }

    @Test fun `saved empty selection overrides legacy defaults and always on`() {
        val c = catalog("CUSA01715", "01.02")
        assertFalse(c.selected(native).isEmpty())
        assertTrue(c.selected(native + (c.selectionKey to "")).isEmpty())
        assertFalse(c.selected(native + ("patches_CUSA01740_01_00" to "")).isEmpty())
        assertFalse(c.selected(native + ("patches_CUSA01715_01_00" to "")).isEmpty())
    }

    @Test fun `skip animations toggle enables and disables both skip patches`() {
        val c = catalog()
        assertEquals(setOf("Skip Intro", "Skip Any Video With X Button"), c.skipVideoPatches.map { it.name }.toSet())
        var ini = native + c.toggleAll(native, c.skipVideoPatches, true)
        assertEquals(c.skipVideoPatches.map { it.name }.toSet(), c.selected(ini))
        assertEquals("0", ini["restart_unconfirmed"])
        ini += c.toggleAll(ini, c.skipVideoPatches, false)
        assertTrue(c.selected(ini).isEmpty())
    }

    @Test fun `skip animations toggle keeps unrelated patches and re-enables both after partial off`() {
        val c = catalog()
        val bloom = c.patches.first { it.name == "Disable Bloom" }
        var ini = native + c.toggle(native, bloom, true)
        ini += c.toggleAll(ini, c.skipVideoPatches, true)
        assertEquals(setOf(bloom.name) + c.skipVideoPatches.map { it.name }.toSet(), c.selected(ini))
        // 玩家在补丁页单独关掉其一 → 开关回落为关；重新打开则两个一起启用。
        ini += c.toggle(ini, c.patches.first { it.name == "Skip Intro" }, false)
        assertFalse(c.skipVideoPatches.all { it.name in c.selected(ini) })
        ini += c.toggleAll(ini, c.skipVideoPatches, true)
        assertEquals(setOf(bloom.name) + c.skipVideoPatches.map { it.name }.toSet(), c.selected(ini))
    }

    @Test fun `texture fix and resolution replace each other and fps switches stay exclusive`() {
        val c = catalog("CUSA01715", "01.02")
        val texture = c.patches.first { it.name == "Bug Fix - Texture Corruption Fix" }
        val resolution = c.patches.first { it.resolution == "720p" }
        val fps120 = c.patches.first { it.fps == "120" }
        val fps240 = c.patches.first { it.fps == "240" }
        var ini = native + c.toggle(native, resolution, true)
        assertFalse(texture.name in c.selected(ini))
        ini += c.toggle(ini, texture, true)
        assertFalse(resolution.name in c.selected(ini))
        ini += c.toggle(ini, fps120, true)
        ini += c.toggle(ini, fps240, true)
        assertFalse(fps120.name in c.selected(ini))
        assertTrue(fps240.name in c.selected(ini))
        ini += c.toggle(ini, fps240, false)
        assertEquals("60", ini["engine_fps"])
    }

    @Test fun `home graphics changes override saved graphics patch names`() {
        val c = catalog()
        val ini = native + mapOf(c.selectionKey to "Resolution Patch - 540p;Disable Bloom", "render_resolution" to "720p")
        assertEquals(setOf("Resolution Patch - 720p", "Disable Bloom"), c.selected(ini))
        val updates = c.toggle(ini, c.patches.first { it.name == "Skip Intro" }, true)
        assertEquals("0", updates["restart_unconfirmed"])
    }

    @Test fun `1740 home offers only catalog resolutions and original engine fps`() {
        val params = LaunchParams.forCatalog(catalog()).associateBy { it.key }
        assertEquals(listOf("native", "480p", "540p", "720p", "900p", "1080p"),
            params.getValue("render_resolution").options.map { it.value })
        assertEquals(listOf("60"), params.getValue("engine_fps").options.map { it.value })
        assertTrue(params.getValue("render_resolution").enabled)
        assertFalse(params.keys.any { it in setOf("skip_intro", "skip_network_choice", "debug_camera") })
        assertEquals(listOf("30", "60", "120", "240", "0"), params.getValue("fps_limit").options.map { it.value })
    }

    @Test fun `missing catalog disables only patch settings`() {
        for (param in LaunchParams.forCatalog(null)) {
            assertEquals(!LaunchParams.isPatchSetting(param.key), param.enabled)
        }
    }

    @Test fun `every offered 1740 resolution round trips between home and patches`() {
        val c = catalog()
        val resolution = LaunchParams.forCatalog(c).first { it.key == "render_resolution" }
        val file = temp.newFile("shared.ini")
        for (option in resolution.options) {
            assertTrue(Gow3Settings.update(file, native + ("render_resolution" to option.value)))
            val ini = Gow3Settings.read(file)
            val patch = c.patches.firstOrNull { it.resolution == option.value }
            assertEquals(setOfNotNull(patch?.name), c.selected(ini))
            if (patch != null) {
                assertTrue(Gow3Settings.update(file, c.toggle(ini, patch, false)))
                assertEquals("native", LaunchParams.selectedOption(Gow3Settings.read(file), resolution)?.value)
                assertTrue(Gow3Settings.update(file, c.toggle(Gow3Settings.read(file), patch, true)))
                assertEquals(option.value, LaunchParams.selectedOption(Gow3Settings.read(file), resolution)?.value)
            }
        }
    }

    @Test fun `unsupported saved graphics values remain visible as unavailable`() {
        val params = LaunchParams.forCatalog(catalog()).associateBy { it.key }
        for ((key, value) in listOf("render_resolution" to "800p", "engine_fps" to "120")) {
            val ini = mapOf(key to value)
            assertEquals(value, LaunchParams.valueOf(ini, params.getValue(key)))
            assertNull(LaunchParams.selectedOption(ini, params.getValue(key)))
        }
    }

    private fun game(name: String, title: String, version: String): File {
        val dir = temp.newFolder(name)
        File(dir, "eboot.bin").writeText("synthetic executable")
        File(dir, "sce_sys").mkdir()
        val keys = "TITLE_ID\u0000APP_VER\u0000".toByteArray()
        val titleBytes = "$title\u0000".toByteArray()
        val versionBytes = "$version\u0000".toByteArray()
        val dataOffset = 20 + 32 + keys.size
        val b = ByteBuffer.allocate(dataOffset + titleBytes.size + versionBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0x46535000).putInt(0x101).putInt(52).putInt(dataOffset).putInt(2)
        b.putShort(0).putShort(0x204).putInt(titleBytes.size).putInt(titleBytes.size).putInt(0)
        b.putShort(9).putShort(0x204).putInt(versionBytes.size).putInt(versionBytes.size).putInt(titleBytes.size)
        b.put(keys).put(titleBytes).put(versionBytes)
        File(dir, "sce_sys/param.sfo").writeBytes(b.array())
        return dir
    }

    @Test fun `selection uses sfo identity rather than the folder name`() {
        val game = game("CUSA01715", "CUSA01740", "01.00")
        val loaded = PatchCatalog.load(game, null) { File(catalogs, it).inputStream() }
        assertEquals("CUSA01740", loaded.titleId)
        assertEquals(14, loaded.patches.size)
    }

    @Test fun `update version is authoritative and unrelated updates are rejected`() {
        val base = game("base", "CUSA01740", "01.00")
        val newer = game("update", "CUSA01740", "01.03")
        val wrong = game("wrong", "CUSA01715", "01.02")
        for ((update, expected) in listOf(newer to CatalogProblem.VERSION, wrong to CatalogProblem.INVALID_UPDATE)) {
            val error = assertThrows(PatchCatalogException::class.java) {
                PatchCatalog.load(base, update) { File(catalogs, it).inputStream() }
            }
            assertEquals(expected, error.problem)
        }
        assertEquals(CatalogProblem.NO_GAME, assertThrows(PatchCatalogException::class.java) {
            PatchCatalog.load(null, null) { File(catalogs, it).inputStream() }
        }.problem)
    }

    @Test fun `catalog rejects another title or an unsupported version`() {
        assertThrows(IllegalArgumentException::class.java) {
            File(catalogs, "God_of_War_III_Remastered_CUSA01740.xml").inputStream().use {
                PatchCatalog.parse(it, "CUSA01715", "01.00")
            }
        }
        assertThrows(IllegalArgumentException::class.java) { catalog(version = "01.03") }
    }

    @Test fun `1740 update 0102 keeps all patches and mobile profiles available`() {
        val base = game("CUSA01740", "CUSA01740", "01.00")
        val update = game("CUSA01740-patch", "CUSA01740", "01.02")
        val loaded = PatchCatalog.load(base, update) { File(catalogs, it).inputStream() }
        assertEquals("01.02", loaded.appVersion)
        assertEquals(14, loaded.patches.size)
        assertEquals(catalog().patches.map { it.name }, loaded.patches.map { it.name })
        assertTrue(loaded.patches.all { it.validation == "offline-matched" })
        assertEquals(5, com.gow3.android.data.model.MobilePresets.available(loaded).size)
        assertTrue(loaded.selected(native + ("patches_CUSA01740_01_00" to "Skip Intro")).isEmpty())
        assertEquals(setOf("Skip Intro"), loaded.selected(native + (loaded.selectionKey to "Skip Intro")))
    }

    private fun modWithPatch(name: String, xml: String): File {
        val folder = File(temp.root, "mods/$name/patches")
        folder.mkdirs()
        File(folder, "$name.xml").writeText(xml)
        File(temp.root, "mods.json").writeText("""{"order":["$name"],"disabled":[]}""")
        return File(temp.root, "mods")
    }

    private fun patchXml(title: String, vararg metas: String) =
        "<Patch><TitleID><ID>$title</ID></TitleID>${metas.joinToString("")}</Patch>"

    private fun metadata(name: String, version: String = "01.00") =
        """<Metadata Name="$name" AppVer="$version" AppElf="eboot.bin">
            <PatchList><Line Type="bytes" Address="0x00000000" Value="00" Pattern="00" At="0"/></PatchList>
        </Metadata>"""

    @Test fun `enabled mod patches merge into the catalog and replace built-in names`() {
        val game = game("CUSA01740", "CUSA01740", "01.00")
        val mods = modWithPatch("ModX",
            patchXml("CUSA01740", metadata("Mod Custom Patch"), metadata("Disable Bloom")))
        val loaded = PatchCatalog.load(game, null, mods, File(temp.root, "mods.json"), true) { File(catalogs, it).inputStream() }
        assertEquals(15, loaded.patches.size)
        assertEquals(PatchSource.MOD, loaded.patches.first { it.name == "Disable Bloom" }.source)
        assertTrue(loaded.patches.any { it.name == "Mod Custom Patch" && it.source == PatchSource.MOD })
        assertTrue(loaded.toNativeInput().contains("Mod Custom Patch"))
    }

    @Test fun `master switch off excludes mod patches`() {
        val game = game("CUSA01740", "CUSA01740", "01.00")
        val mods = modWithPatch("ModX", patchXml("CUSA01740", metadata("Mod Custom Patch")))
        val loaded = PatchCatalog.load(game, null, mods, File(temp.root, "mods.json"), false) { File(catalogs, it).inputStream() }
        assertEquals(14, loaded.patches.size)
        assertFalse(loaded.patches.any { it.name == "Mod Custom Patch" })
        assertFalse(loaded.toNativeInput().contains("Mod Custom Patch"))
    }

    @Test fun `mod patch for another title or version is filtered`() {
        val game = game("CUSA01740", "CUSA01740", "01.00")
        val mods = modWithPatch("ModX", patchXml("CUSA01623", metadata("Wrong Title"), metadata("Other Version", "01.02")))
        val loaded = PatchCatalog.load(game, null, mods, File(temp.root, "mods.json"), true) { File(catalogs, it).inputStream() }
        assertEquals(14, loaded.patches.size)
        assertFalse(loaded.patches.any { it.name == "Wrong Title" })
    }

    @Test fun `broken mod patch is skipped without blocking the catalog`() {
        val game = game("CUSA01740", "CUSA01740", "01.00")
        val folder = File(temp.root, "mods/ModX/patches")
        folder.mkdirs()
        File(folder, "broken.xml").writeText("this is not xml")
        File(temp.root, "mods.json").writeText("""{"order":["ModX"],"disabled":[]}""")
        val loaded = PatchCatalog.load(game, null, File(temp.root, "mods"), File(temp.root, "mods.json"), true) { File(catalogs, it).inputStream() }
        assertEquals(14, loaded.patches.size)
        assertTrue(loaded.patches.all { it.source == PatchSource.BUILTIN })
    }
}
