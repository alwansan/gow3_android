package com.gow3.android.data.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SaveArchiveTest {

    companion object {
        /** 真实设备上游戏 `param.sfo` 的 TITLE_ID（Android 版 dump 是 CUSA01740）。 */
        private const val TITLE = "CUSA01740"
        private const val OTHER_TITLE = "CUSA01623"
        /** 与 [TITLE] 互相兼容的另一个区域代号（spec：CUSA01715 ↔ CUSA01740 可互导）。 */
        private const val COMPATIBLE_TITLE = "CUSA01715"
    }

    private fun tempDir(): File {
        val dir = java.nio.file.Files.createTempDirectory("savearchive-test").toFile()
        dir.deleteOnExit()
        return dir
    }

    private fun makeSavedata(root: File, title: String = TITLE): File {
        val savedata = File(root, "savedata").apply { mkdirs() }
        val slot = File(savedata, "1/$title/AUTOSAVE").apply { mkdirs() }
        File(slot, "data.bin").writeText("v1")
        File(File(slot, "AUTOSAVE.sce_sys").apply { mkdirs() }, "param.bin").writeText("p")
        File(File(savedata, "1/$title.memory").apply { mkdirs() }, "memory.dat").writeText("m")
        return savedata
    }

    private fun zipBytes(entries: List<Pair<String, String?>>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                if (content != null) zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun listFiles(dir: File): Map<String, String> =
        dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).invariantSeparatorsPath to it.readText() }

    @Test
    fun `hasSaves is true for a non-empty savedata tree`() {
        assertTrue(SaveArchive.hasSaves(makeSavedata(tempDir())))
    }

    @Test
    fun `hasSaves is false for missing and empty directories`() {
        val dir = tempDir()
        assertFalse(SaveArchive.hasSaves(File(dir, "missing")))
        File(dir, "savedata").mkdirs()
        assertFalse(SaveArchive.hasSaves(File(dir, "savedata")))
    }

    @Test
    fun `pack then extract round-trips the savedata tree`() {
        val dir = tempDir()
        val savedata = makeSavedata(dir)
        val zip = File(dir, "export.zip")
        SaveArchive.pack(savedata, zip)
        assertTrue(zip.isFile)

        val staging = File(dir, "savedata.restoring").apply { mkdirs() }
        SaveArchive.extract(zip.inputStream(), staging)
        assertEquals(listFiles(savedata), listFiles(staging))
    }

    @Test
    fun `validate accepts an archive produced by pack`() {
        val dir = tempDir()
        val zip = File(dir, "export.zip")
        SaveArchive.pack(makeSavedata(dir), zip)
        assertNull(SaveArchive.validate(zip.inputStream(), TITLE))
    }

    @Test
    fun `validate rejects a file that is not a zip`() {
        val reason = SaveArchive.validate(ByteArrayInputStream("not a zip".toByteArray()), TITLE)
        assertNotNull(reason)
    }

    @Test
    fun `validate rejects zip-slip paths`() {
        val bytes = zipBytes(listOf("savedata/1/$TITLE/AUTOSAVE/../data.bin" to "x"))
        assertNotNull(SaveArchive.validate(ByteArrayInputStream(bytes), TITLE))
    }

    @Test
    fun `validate accepts a compatible region title and reports it`() {
        // CUSA01715（兼容集合内）的归档在期望 CUSA01740 时必须通过，且 check 报告实际代号。
        val bytes = zipBytes(listOf("savedata/1/$COMPATIBLE_TITLE/AUTOSAVE/data.bin" to "x"))
        val check = SaveArchive.check(ByteArrayInputStream(bytes), TITLE)
        assertNull(check.problem)
        assertEquals(COMPATIBLE_TITLE, check.title)
    }

    @Test
    fun `validate rejects a title outside the compatible set`() {
        // 完全未知的代号（不在兼容集合）仍然拒绝，报出期望代号。
        val bytes = zipBytes(listOf("savedata/1/$OTHER_TITLE/AUTOSAVE/data.bin" to "x"))
        val check = SaveArchive.check(ByteArrayInputStream(bytes), TITLE)
        assertTrue(check.problem is SaveArchiveProblem.UnsupportedTitle)
        assertEquals(TITLE, (check.problem as SaveArchiveProblem.UnsupportedTitle).expected)
        assertNull(check.title)
    }

    @Test
    fun `validate rejects an archive mixing two region titles`() {
        val bytes = zipBytes(
            listOf(
                "savedata/1/$TITLE/AUTOSAVE/data.bin" to "x",
                "savedata/1/$COMPATIBLE_TITLE/AUTOSAVE/data.bin" to "y",
            ),
        )
        assertNotNull(SaveArchive.check(ByteArrayInputStream(bytes), TITLE).problem)
    }

    @Test
    fun `extract rewrites a compatible region title to the target`() {
        val dir = tempDir()
        val bytes = zipBytes(
            listOf(
                "savedata/1/$COMPATIBLE_TITLE/AUTOSAVE/data.bin" to "x",
                "savedata/1/$COMPATIBLE_TITLE/AUTOSAVE.sce_sys/param.bin" to "p",
                "savedata/1/$COMPATIBLE_TITLE.memory/memory.dat" to "m",
            ),
        )
        val staging = File(dir, "savedata.restoring").apply { mkdirs() }
        SaveArchive.extract(ByteArrayInputStream(bytes), staging, TITLE)
        assertTrue(File(staging, "1/$TITLE/AUTOSAVE/data.bin").isFile)
        assertTrue(File(staging, "1/$TITLE/AUTOSAVE.sce_sys/param.bin").isFile)
        assertTrue(File(staging, "1/$TITLE.memory/memory.dat").isFile)
        assertFalse(File(staging, "1/$COMPATIBLE_TITLE").exists())
    }

    @Test
    fun `extract keeps the title unchanged when it already matches`() {
        val dir = tempDir()
        val bytes = zipBytes(listOf("savedata/1/$TITLE/AUTOSAVE/data.bin" to "x"))
        val staging = File(dir, "savedata.restoring").apply { mkdirs() }
        SaveArchive.extract(ByteArrayInputStream(bytes), staging, TITLE)
        assertTrue(File(staging, "1/$TITLE/AUTOSAVE/data.bin").isFile)
    }

    @Test
    fun `extract keeps the original title when no target is given`() {
        val dir = tempDir()
        val bytes = zipBytes(listOf("savedata/1/$COMPATIBLE_TITLE/AUTOSAVE/data.bin" to "x"))
        val staging = File(dir, "savedata.restoring").apply { mkdirs() }
        SaveArchive.extract(ByteArrayInputStream(bytes), staging)
        assertTrue(File(staging, "1/$COMPATIBLE_TITLE/AUTOSAVE/data.bin").isFile)
    }

    @Test
    fun `validate accepts the current game's title, not a fixed one`() {
        // 同一个归档在期望代号为 CUSA01623 时必须通过 —— 说明校验跟着 expectedTitle 走，
        // 而不是绑死在某个常量上。
        val bytes = zipBytes(listOf("savedata/1/$OTHER_TITLE/AUTOSAVE/data.bin" to "x"))
        assertNull(SaveArchive.validate(ByteArrayInputStream(bytes), OTHER_TITLE))
    }

    @Test
    fun `validate falls back to a shape check when the title cannot be derived`() {
        // game_dir 未配置或 param.sfo 不可读时 expectedTitle 为 null：接受任意非空标题目录名……
        val accepted = zipBytes(listOf("savedata/1/$OTHER_TITLE/AUTOSAVE/data.bin" to "x"))
        assertNull(SaveArchive.validate(ByteArrayInputStream(accepted), null))
        // ……但仍拒绝不安全路径与空归档。
        val unsafe = zipBytes(listOf("savedata/1/$OTHER_TITLE/../../escape.bin" to "x"))
        assertNotNull(SaveArchive.validate(ByteArrayInputStream(unsafe), null))
        val empty = zipBytes(listOf("savedata/" to null, "savedata/1/" to null, "savedata/1/$OTHER_TITLE/" to null))
        assertNotNull(SaveArchive.validate(ByteArrayInputStream(empty), null))
    }

    @Test
    fun `validate rejects a layout outside the savedata tree`() {
        val bytes = zipBytes(listOf("home/1/$TITLE/data.bin" to "x"))
        assertNotNull(SaveArchive.validate(ByteArrayInputStream(bytes), TITLE))
    }

    @Test
    fun `validate rejects an archive with no content files`() {
        val bytes = zipBytes(listOf("savedata/" to null, "savedata/1/" to null, "savedata/1/$TITLE/" to null))
        assertNotNull(SaveArchive.validate(ByteArrayInputStream(bytes), TITLE))
    }

    @Test
    fun `validate accepts the memory folder alongside the title dir`() {
        val bytes = zipBytes(
            listOf(
                "savedata/1/$TITLE/AUTOSAVE/data.bin" to "x",
                "savedata/1/$TITLE.memory/memory.dat" to "m",
            ),
        )
        assertNull(SaveArchive.validate(ByteArrayInputStream(bytes), TITLE))
    }

    @Test
    fun `backupName appends a counter when the stamp is taken`() {
        val root = tempDir()
        File(root, "20240101-120000").mkdirs()
        File(root, "20240101-120000-2").mkdirs()
        assertEquals("20240101-120000-3", SaveArchive.backupName(root, "20240101-120000"))
    }

    @Test
    fun `replaceStaging keeps the original when the swap fails`() {
        val dir = tempDir()
        val savedata = File(dir, "savedata").apply { mkdirs() }
        File(savedata, "data.bin").writeText("v1")
        // staging 不存在，因此最终重命名必然失败
        val staging = File(dir, "savedata.restoring")
        val failed = try {
            SaveArchive.replaceStaging(staging, savedata)
            false
        } catch (_: IllegalStateException) {
            true
        }
        assertTrue(failed)
        assertEquals("v1", File(savedata, "data.bin").readText())
        assertFalse(File(dir, "savedata.old").exists())
    }
}
