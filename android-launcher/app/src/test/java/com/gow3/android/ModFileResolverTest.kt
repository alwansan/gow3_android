package com.gow3.android

import com.gow3.android.data.GameUpdateView
import com.gow3.android.data.ModFileResolver
import com.gow3.android.data.ModGameView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 上游 scripts/mods.py 语义的 Kotlin 移植回归：布局判定、扁平 dvdroot_ps4、冲突、选择/预设、ZIP 与覆盖视图。 */
class ModFileResolverTest {
    @get:Rule val temp = TemporaryFolder()
    private val root: File get() = temp.root

    private fun game(name: String, vararg dataDirs: String): File {
        val dir = File(root, name)
        dir.mkdirs()
        File(dir, "eboot.bin").writeText("original executable")
        for (sub in dataDirs) File(dir, sub).mkdirs()
        return dir
    }

    private fun mod(name: String, path: String, contents: String = "mod"): File {
        val file = File(root, "mods/$name/$path")
        file.parentFile.mkdirs()
        file.writeText(contents)
        return File(root, "mods/$name")
    }

    private fun makeZip(name: String, entries: Map<String, String>) {
        val dir = File(root, "mods")
        dir.mkdirs()
        File(dir, name + ".zip").outputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                for ((path, data) in entries) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(data.toByteArray())
                    zip.closeEntry()
                }
            }
        }
    }

    @Test fun `EXEC and language folders are discovered and replace game files`() {
        val g = game("CUSA01623", "EXEC/WAD/PS4_LE", "PORTUGUESE/SOUNDS", "sce_sys")
        File(g, "EXEC/WAD/PS4_LE/R_UI.WAD").writeText("original ui")
        File(g, "PORTUGUESE/SOUNDS/X.XVAG").writeText("original voice")
        mod("UI", "exec/wad/ps4_le/r_ui.wad", "mod ui")
        mod("UI", "Portuguese/SOUNDS/X.XVAG", "mod voice")
        mod("UI", "readme.txt", "ignored")
        assertEquals(listOf("UI"), ModFileResolver.discover(File(root, "mods"), g))
        val view = GameUpdateView.create(g, null, root)
        val overlay = ModGameView.create(view, File(root, "mods"), null, true, root)
        try {
            val out = overlay.directory
            assertEquals("mod ui", File(out, "EXEC/WAD/PS4_LE/R_UI.WAD").readText())
            assertEquals("mod voice", File(out, "PORTUGUESE/SOUNDS/X.XVAG").readText())
            assertFalse(File(out, "readme.txt").exists())
            assertEquals("original ui", File(g, "EXEC/WAD/PS4_LE/R_UI.WAD").readText())
        } finally {
            overlay.close(); view.close()
        }
    }

    @Test fun `flat game drops the dvdroot_ps4 prefix and later mod wins`() {
        val g = game("CUSA01623", "data")
        File(g, "data/a.wad").writeText("original")
        mod("A", "dvdroot_ps4/data/a.wad", "A")
        mod("B", "dvdroot_ps4/data/a.wad", "B")
        val view = GameUpdateView.create(g, null, root)
        val overlay = ModGameView.create(view, File(root, "mods"), null, true, root)
        try {
            assertEquals("B", File(overlay.directory, "data/a.wad").readText())
            assertFalse(File(overlay.directory, "dvdroot_ps4").exists())
            assertEquals("original", File(g, "data/a.wad").readText())
        } finally {
            overlay.close(); view.close()
        }
    }

    @Test fun `system files are refused through the overlay`() {
        val g = game("CUSA01623", "data")
        mod("Sneaky", "dvdroot_ps4/eboot.bin", "bad")
        val view = GameUpdateView.create(g, null, root)
        val overlay = ModGameView.create(view, File(root, "mods"), null, true, root)
        try {
            // 拒绝系统替换：该模组不叠加任何文件，视图保持基础视图。
            assertEquals(view.directory, overlay.directory)
        } finally {
            overlay.close(); view.close()
        }
        assertEquals("original executable", File(g, "eboot.bin").readText())
    }

    @Test fun `conflicts match across layouts and case`() {
        mod("A", "EXEC/data/a.wad", "a")
        mod("B", "dvdroot_ps4/EXEC/data/a.wad", "b")
        mod("C", "EXEC/data/A.WAD", "c")
        val found = ModFileResolver.conflicts(File(root, "mods"), listOf("A", "B", "C"))
        assertEquals(1, found.size)
        assertEquals("EXEC/data/a.wad", found[0].path)
        assertEquals(listOf("A", "B", "C"), found[0].mods)
    }

    @Test fun `selection follows mods json and new mods append alphabetically`() {
        for (name in listOf("C", "B", "A")) mod(name, "EXEC/data/a.wad", name)
        val config = File(root, "mods.json")
        config.writeText("""{"order":["B","A","deleted"],"disabled":["A"]}""")
        assertEquals(listOf("B", "C"), ModFileResolver.selected(File(root, "mods"), config))
    }

    @Test fun `preset drops missing mods and appends new ones enabled`() {
        val preset = JSONObject("""{"order":["b","gone","a"],"disabled":["a","gone"]}""")
        val (order, disabled) = ModFileResolver.applyPreset(preset, listOf("a", "b", "c"))
        assertEquals(listOf("b", "a", "c"), order)
        assertEquals(listOf("a"), disabled)
        val bad = ModFileResolver.applyPreset(JSONObject("{}"), listOf("a"))
        assertEquals(listOf("a"), bad.first)
        assertTrue(bad.second.isEmpty())
    }

    @Test fun `zip is unpacked, recognised and re-unpacked when changed`() {
        makeZip("Ultra", mapOf("patches/u.xml" to "<Patch/>", "dvdroot_ps4/data/a.wad" to "zipped"))
        assertEquals(listOf("Ultra"), ModFileResolver.discover(File(root, "mods")))
        assertTrue(File(root, "mods/Ultra/patches/u.xml").isFile)
        assertTrue(File(root, "mods/Ultra/${ModFileResolver.ZIP_STAMP}").isFile)
        // zip 变更后重新解包，旧文件消失。
        val zip = File(root, "mods/Ultra.zip")
        zip.delete()
        makeZip("Ultra", mapOf("patches/new.xml" to "<Patch/>"))
        zip.setLastModified(zip.lastModified() + 2000)
        ModFileResolver.discover(File(root, "mods"))
        assertTrue(File(root, "mods/Ultra/patches/new.xml").isFile)
        assertFalse(File(root, "mods/Ultra/patches/u.xml").exists())
    }

    @Test fun `user-made folder is never replaced by a zip`() {
        mod("Ultra", "patches/mine.xml", "<Patch/>")
        makeZip("Ultra", mapOf("patches/zipped.xml" to "<Patch/>"))
        ModFileResolver.discover(File(root, "mods"))
        assertTrue(File(root, "mods/Ultra/patches/mine.xml").isFile)
        assertFalse(File(root, "mods/Ultra/${ModFileResolver.ZIP_STAMP}").exists())
    }

    @Test fun `unsafe zips are refused`() {
        makeZip("Slip", mapOf("../evil.txt" to "x"))
        makeZip("Absolute", mapOf("/abs.txt" to "x"))
        makeZip("Drive", mapOf("C:/drive.txt" to "x"))
        assertEquals(emptyList<String>(), ModFileResolver.discover(File(root, "mods")))
        assertFalse(File(root, "mods/Slip").exists())
        assertFalse(File(root, "mods/Absolute").exists())
        assertFalse(File(root, "mods/Drive").exists())
        assertFalse(File(root, "evil.txt").exists())
    }

    /** 把 zip 中央目录里某条目的 external attributes 直接改写为 unix 符号链接 mode（0o120777）。 */
    private fun markZipEntryAsSymlink(zip: File, entryName: String) {
        val bytes = zip.readBytes()
        fun leInt(offset: Int): Int = (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
        var eocd = -1
        for (i in bytes.size - 22 downTo 0) {
            if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() &&
                bytes[i + 2] == 0x05.toByte() && bytes[i + 3] == 0x06.toByte()) { eocd = i; break }
        }
        assertTrue("no EOCD", eocd >= 0)
        val cdOffset = leInt(eocd + 16)
        val cdSize = leInt(eocd + 12)
        var offset = cdOffset
        while (offset + 46 <= cdOffset + cdSize && leInt(offset) == 0x02014b50) {
            val nameLen = (bytes[offset + 28].toInt() and 0xFF) or ((bytes[offset + 29].toInt() and 0xFF) shl 8)
            val extraLen = (bytes[offset + 30].toInt() and 0xFF) or ((bytes[offset + 31].toInt() and 0xFF) shl 8)
            val commentLen = (bytes[offset + 32].toInt() and 0xFF) or ((bytes[offset + 33].toInt() and 0xFF) shl 8)
            val name = String(bytes, offset + 46, nameLen, Charsets.UTF_8)
            if (name == entryName) {
                val attrs = 0xA1FF0000.toInt() // unix 符号链接 mode 0o120777，高 16 位
                for (i in 0 until 4) bytes[offset + 38 + i] = ((attrs ushr (8 * i)) and 0xFF).toByte()
                break
            }
            offset += 46 + nameLen + extraLen + commentLen
        }
        zip.writeBytes(bytes)
    }

    @Test fun `a zip marked as a symbolic link is refused`() {
        val zip = File(root, "mods")
        zip.mkdirs()
        val link = File(zip, "Link.zip")
        link.outputStream().use { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("patches/u.xml"))
                z.write("target".toByteArray())
                z.closeEntry()
            }
        }
        markZipEntryAsSymlink(link, "patches/u.xml")
        val types = ModFileResolver.unixTypes(link)
        assertEquals(0xA1FF, types["patches/u.xml"])
        java.util.zip.ZipFile(link).use { archive ->
            assertThrows(IllegalArgumentException::class.java) {
                ModFileResolver.zipMembers(archive, types)
            }
        }
    }

    @Test fun `overlay cleanup never deletes player files`() {
        val g = game("CUSA01623", "data")
        File(g, "data/a.wad").writeText("original")
        mod("A", "dvdroot_ps4/data/a.wad", "A")
        val view = GameUpdateView.create(g, null, root)
        val overlay = ModGameView.create(view, File(root, "mods"), null, true, root)
        val overlayPath = overlay.directory
        assertTrue(overlayPath.isDirectory)
        overlay.close()
        assertFalse(overlayPath.exists())
        assertEquals("original", File(g, "data/a.wad").readText())
        assertTrue(File(g, "eboot.bin").isFile)
    }

    @Test fun `no enabled mods returns the base view`() {
        val g = game("CUSA01623", "data")
        val view = GameUpdateView.create(g, null, root)
        val off = ModGameView.create(view, File(root, "mods"), null, enabled = false, parent = root)
        assertEquals(view.directory, off.directory)
        off.close()
        // enabled 但无模组目录时同样回落基础视图。
        val empty = ModGameView.create(view, File(root, "no-mods"), null, enabled = true, parent = root)
        assertEquals(view.directory, empty.directory)
        empty.close()
        view.close()
    }
}
