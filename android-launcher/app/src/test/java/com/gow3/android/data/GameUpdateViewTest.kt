package com.gow3.android.data

import com.gow3.android.data.model.GameSfo
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class GameUpdateViewTest {
    @get:Rule val temp = TemporaryFolder()

    private fun game(name: String, version: String, title: String = "CUSA01740"): File {
        val root = temp.newFolder(name)
        File(root, "eboot.bin").writeText("$name executable")
        File(root, "sce_sys").mkdir()
        val pairs = listOf("TITLE_ID" to title, "APP_VER" to version)
        val keys = pairs.joinToString("") { "${it.first}\u0000" }.toByteArray()
        val values = pairs.map { "${it.second}\u0000".toByteArray() }
        val headerSize = 20 + 16 * pairs.size
        val buffer = ByteBuffer.allocate(headerSize + keys.size + values.sumOf { it.size })
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x46535000).putInt(0x101).putInt(headerSize)
            .putInt(headerSize + keys.size).putInt(pairs.size)
        var keyOffset = 0
        var valueOffset = 0
        for (i in pairs.indices) {
            buffer.putShort(keyOffset.toShort()).putShort(0x204.toShort())
                .putInt(values[i].size).putInt(values[i].size).putInt(valueOffset)
            keyOffset += pairs[i].first.length + 1
            valueOffset += values[i].size
        }
        buffer.put(keys)
        values.forEach { buffer.put(it) }
        File(root, "sce_sys/param.sfo").writeBytes(buffer.array())
        return root
    }

    private fun write(root: File, path: String, text: String): File =
        File(root, path).also { requireNotNull(it.parentFile).mkdirs(); it.writeText(text) }

    @Test fun `no update keeps base and close never removes it`() {
        val base = game("base", "01.00")
        val parent = File(temp.root, "unused")
        val view = GameUpdateView.create(base, null, parent)
        assertEquals(base.canonicalFile, view.directory)
        assertFalse(parent.exists())
        view.close()
        view.close()
        assertEquals("base executable", File(base, "eboot.bin").readText())
    }

    @Test fun `update supplies executable sfo modules and assets through one view`() {
        val base = game("base", "01.00")
        val update = game("update", "01.02")
        val baseModule = write(base, "sce_module/libSceFios2.prx", "base fios")
        write(base, "sce_module/libc.prx", "base libc")
        val updatedModule = write(update, "sce_module/libc.prx", "updated libc")
        val baseAsset = write(base, "dvdroot_ps4/large.wad", "base asset")
        write(base, "dvdroot_ps4/changed.wad", "old asset")
        val updatedAsset = write(update, "dvdroot_ps4/changed.wad", "updated asset")
        val parent = temp.newFolder("views")
        val view = GameUpdateView.create(base, update, parent)
        val effective = view.directory
        assertEquals(File(update, "eboot.bin").canonicalFile, File(effective, "eboot.bin").canonicalFile)
        assertEquals("01.02", GameSfo.read(File(effective, "sce_sys/param.sfo"))?.get("APP_VER"))
        assertEquals(updatedModule.canonicalFile, File(effective, "sce_module/libc.prx").canonicalFile)
        assertEquals(baseModule.canonicalFile, File(effective, "sce_module/libSceFios2.prx").canonicalFile)
        assertEquals(baseAsset.canonicalFile, File(effective, "dvdroot_ps4/large.wad").canonicalFile)
        assertEquals(updatedAsset.canonicalFile, File(effective, "dvdroot_ps4/changed.wad").canonicalFile)
        assertTrue(Files.isSymbolicLink(File(effective, "dvdroot_ps4/large.wad").toPath()))
        view.close()
        assertFalse(effective.exists())
        assertEquals("base executable", File(base, "eboot.bin").readText())
        assertEquals("old asset", File(base, "dvdroot_ps4/changed.wad").readText())
        assertEquals("updated asset", updatedAsset.readText())
    }

    @Test fun `nested merge is case insensitive and update wins file directory collisions`() {
        val base = game("base", "01.00")
        val update = game("update", "01.02")
        write(base, "dvdroot_ps4/data/a.wad", "old")
        write(update, "DVDROOT_PS4/DATA/A.WAD", "new")
        write(base, "file-to-directory", "old file")
        write(update, "file-to-directory/new.bin", "new child")
        write(base, "directory-to-file/old.bin", "old child")
        write(update, "directory-to-file", "new file")
        val view = GameUpdateView.create(base, update, temp.newFolder("views"))
        view.use {
            assertEquals("new", File(it.directory, "dvdroot_ps4/data/a.wad").readText())
            assertEquals("new child", File(it.directory, "file-to-directory/new.bin").readText())
            assertEquals("new file", File(it.directory, "directory-to-file").readText())
        }
        assertEquals("old", File(base, "dvdroot_ps4/data/a.wad").readText())
        assertEquals("old child", File(base, "directory-to-file/old.bin").readText())
    }

    @Test fun `invalid updates do not create a fallback view`() {
        val base = game("base", "01.00")
        val wrong = game("wrong", "01.02", "CUSA01715")
        val missing = game("missing", "01.02")
        File(missing, "eboot.bin").delete()
        val parent = temp.newFolder("views")
        for (update in listOf(base, wrong, missing, File(temp.root, "absent"))) {
            assertThrows(IOException::class.java) { GameUpdateView.create(base, update, parent) }
        }
        assertEquals(0, parent.listFiles()!!.size)
        assertEquals("base executable", File(base, "eboot.bin").readText())
    }

    @Test fun `view cannot be created inside player inputs`() {
        val base = game("base", "01.00")
        val update = game("update", "01.02")
        for (source in listOf(base, update)) {
            assertThrows(IOException::class.java) {
                GameUpdateView.create(base, update, File(source, "must-not-create"))
            }
            assertFalse(File(source, "must-not-create").exists())
        }
        val nested = File(base, "nested").also { it.mkdir() }
        write(nested, "eboot.bin", "nested executable")
        write(nested, "sce_sys/param.sfo", "")
        File(base, "sce_sys/param.sfo").copyTo(File(nested, "sce_sys/param.sfo"), overwrite = true)
        assertThrows(IOException::class.java) {
            GameUpdateView.create(base, nested, temp.newFolder("views"))
        }
    }

    @Test fun `old session cleanup does not remove another view or linked source tree`() {
        val base = game("base", "01.00")
        val update = game("update", "01.02")
        val source = write(base, "dvdroot_ps4/sub/asset.wad", "asset")
        val parent = temp.newFolder("views")
        val old = GameUpdateView.create(base, update, parent)
        val current = GameUpdateView.create(base, update, parent)
        old.close()
        old.close()
        assertTrue(current.directory.exists())
        assertEquals("asset", File(current.directory, "dvdroot_ps4/sub/asset.wad").readText())
        current.close()
        assertEquals("asset", source.readText())
        assertEquals(0, parent.listFiles()!!.size)
    }

    @Test fun `failed recursive overlay cleans only temporary links`() {
        val base = game("base", "01.00")
        val update = game("update", "01.02")
        Files.createSymbolicLink(File(base, "loop").toPath(), base.toPath())
        Files.createSymbolicLink(File(update, "loop").toPath(), update.toPath())
        val parent = temp.newFolder("views")
        try {
            assertThrows(IOException::class.java) { GameUpdateView.create(base, update, parent) }
            assertEquals(0, parent.listFiles()!!.size)
            assertTrue(File(base, "eboot.bin").isFile)
            assertTrue(File(update, "eboot.bin").isFile)
        } finally {
            Files.deleteIfExists(File(base, "loop").toPath())
            Files.deleteIfExists(File(update, "loop").toPath())
        }
    }
}
