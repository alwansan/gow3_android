package com.gow3.android.data

import com.gow3.android.data.model.GameSfo
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Locale

/** 会话持有的只读游戏视图；仅清理自己创建的目录，不沿链接删除玩家文件。 */
class GameUpdateView private constructor(
    val directory: File,
    private val temporary: Boolean,
) : Closeable {
    @Synchronized
    override fun close() {
        if (temporary) removeView(directory)
    }

    companion object {
        @JvmStatic
        @Throws(IOException::class)
        fun create(base: File, update: File?, parent: File): GameUpdateView {
            val source = base.canonicalFile
            if (!File(source, "eboot.bin").isFile) throw IOException("The base game needs eboot.bin")
            if (update == null) return GameUpdateView(source, false)
            val patch = update.canonicalFile
            if (!File(patch, "eboot.bin").isFile) throw IOException("The update needs eboot.bin")
            val baseId = GameSfo.read(File(source, "sce_sys/param.sfo"))?.get("TITLE_ID")
            val updateId = GameSfo.read(File(patch, "sce_sys/param.sfo"))?.get("TITLE_ID")
            if (baseId == null || baseId != updateId) {
                throw IOException("Game and update are not the same title")
            }
            if (source.toPath().startsWith(patch.toPath()) || patch.toPath().startsWith(source.toPath())) {
                throw IOException("Choose separate folders for the game and its update")
            }
            val output = parent.canonicalFile
            if (output.toPath().startsWith(source.toPath()) || output.toPath().startsWith(patch.toPath())) {
                throw IOException("The merged view must be outside the game and update folders")
            }
            if (!output.isDirectory && !output.mkdirs()) throw IOException("Cannot create $output")
            val view = GameUpdateView(Files.createTempDirectory(output.toPath(), "game-update-").toFile(), true)
            try {
                merge(source, patch, view.directory, mutableSetOf())
                return view
            } catch (error: Exception) {
                try {
                    view.close()
                } catch (cleanup: IOException) {
                    error.addSuppressed(cleanup)
                }
                throw error
            }
        }

        private fun entries(directory: File): Map<String, File> {
            val files = directory.listFiles() ?: throw IOException("Cannot list $directory")
            val result = linkedMapOf<String, File>()
            for (file in files) {
                if (result.put(file.name.lowercase(Locale.ROOT), file) != null) {
                    throw IOException("Ambiguous case-insensitive filename in $directory")
                }
            }
            return result
        }

        private fun merge(base: File, update: File, target: File, active: MutableSet<Pair<String, String>>) {
            if (Thread.currentThread().isInterrupted) throw IOException("Game preparation interrupted")
            val pair = base.canonicalPath to update.canonicalPath
            if (active.size >= 128 || !active.add(pair)) throw IOException("Cyclic game/update directories")
            try {
                val lower = entries(base)
                val upper = entries(update)
                for (key in lower.keys + upper.keys) {
                    val old = lower[key]
                    val replacement = upper[key]
                    // 同名项沿用基础目录的大小写，和旧 Python overlay 的行为一致。
                    val destination = File(target, (old ?: replacement!!).name)
                    if (old?.isDirectory == true && replacement?.isDirectory == true) {
                        if (!destination.mkdir()) throw IOException("Cannot create $destination")
                        merge(old, replacement, destination, active)
                    } else {
                        val chosen = replacement ?: old!!
                        Files.createSymbolicLink(destination.toPath(), chosen.canonicalFile.toPath())
                    }
                }
            } finally {
                active.remove(pair)
            }
        }

        private fun removeView(file: File) {
            val path = file.toPath()
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
            if (!Files.isSymbolicLink(path) && file.isDirectory) {
                val children = file.listFiles() ?: throw IOException("Cannot list $file for cleanup")
                for (child in children) removeView(child)
            }
            Files.deleteIfExists(path)
        }
    }
}
