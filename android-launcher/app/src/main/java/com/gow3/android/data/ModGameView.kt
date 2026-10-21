package com.gow3.android.data

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * 模组覆盖视图：在「基础 + 更新」视图之上把启用模组以符号链接叠加成只读视图（上游 `mods.py`
 * `build_overlay` 的 Kotlin 移植）。后置模组整文件胜出；扁平游戏去掉 dvdroot_ps4 前缀；
 * 玩家游戏、更新与模组文件永不被修改。清理只删除自己创建的链接目录，不沿链接删除玩家文件。
 */
class ModGameView private constructor(
    val directory: File,
    private val temporary: Boolean,
) : Closeable {
    @Synchronized
    override fun close() {
        if (temporary) removeView(directory)
    }

    companion object {
        /**
         * 按 mods.json 的启用模组构建覆盖视图；总开关关闭或无启用模组时直接返回基础视图（零成本）。
         * [parent] 是视图所在目录（应用私有数据目录，符号链接在此可用）。
         */
        @JvmStatic
        @Throws(IOException::class)
        fun create(base: GameUpdateView, modsDir: File, config: File?, enabled: Boolean, parent: File): ModGameView {
            if (!enabled) return ModGameView(base.directory, false)
            val layers = ModFileResolver.selected(modsDir, config, base.directory)
            if (layers.isEmpty()) return ModGameView(base.directory, false)
            return build(base.directory, layers.map { it to File(modsDir, it) }, parent)
        }

        private fun build(game: File, mods: List<Pair<String, File>>, out: File): ModGameView {
            val flat = !ModFileResolver.child(game, "dvdroot_ps4").isDirectory
            val replacements = LinkedHashMap<String, Pair<List<String>, File>>()
            for ((name, root) in mods) {
                var count = 0
                try {
                    for (file in ModFileResolver.modFiles(root, game)) {
                        val relative = ModFileResolver.gamePath(file.gamePath, flat)
                        replacements[relative.joinToString("/").lowercase()] = relative to file.source
                        count++
                    }
                } catch (error: Exception) {
                    // 单个模组损坏（如拒绝的系统替换）不影响其它模组叠加；此处由调用方决定是否记录。
                    println("Mods: $name: $error")
                    continue
                }
                println("Mods: $name: $count files")
            }
            if (replacements.isEmpty()) return ModGameView(game, false)
            if (!out.isDirectory && !out.mkdirs()) throw IOException("Cannot create $out")
            val overlay = Files.createTempDirectory(out.toPath(), "mod-game-").toFile()
            try {
                val entries = game.listFiles() ?: throw IOException("Cannot list $game")
                for (entry in entries) makeLink(File(overlay, entry.name), entry)
                var replaced = 0
                var added = 0
                for ((relative, source) in replacements.values) {
                    var parent = overlay
                    for (part in relative.dropLast(1)) {
                        parent = ModFileResolver.child(parent, part)
                        expand(parent)
                    }
                    val destination = ModFileResolver.child(parent, relative.last())
                    if (isLink(destination)) {
                        replaced++
                        removeLink(destination)
                    } else if (destination.exists()) {
                        throw IOException("File/directory conflict: ${relative.joinToString("/")}")
                    } else {
                        added++
                    }
                    makeLink(destination, source)
                }
                println("Mods: $replaced game files replaced, $added added")
                return ModGameView(overlay, true)
            } catch (error: Exception) {
                removeView(overlay)
                throw error
            }
        }

        private fun makeLink(link: File, target: File) {
            Files.createSymbolicLink(link.toPath(), target.canonicalFile.toPath())
        }

        private fun isLink(path: File): Boolean = Files.isSymbolicLink(path.toPath())

        private fun removeLink(path: File) {
            Files.delete(path.toPath())
        }

        /** 实例化一层目录：把目录链接拆成真实目录并逐条重新链接；绝不透过目录链接写入。 */
        private fun expand(directory: File) {
            if (isLink(directory)) {
                val target = directory.toPath().toRealPath()
                if (!Files.isDirectory(target)) throw IOException("File/directory conflict at ${directory.name}")
                removeLink(directory)
                if (!directory.mkdir()) throw IOException("Cannot create ${directory.path}")
                val entries = target.toFile().listFiles() ?: return
                for (entry in entries) makeLink(File(directory, entry.name), entry)
            } else if (directory.exists() && !directory.isDirectory) {
                throw IOException("File/directory conflict at ${directory.name}")
            } else {
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create ${directory.path}")
            }
        }

        /** 只删除视图自身创建的目录树，不沿链接删除玩家文件。 */
        private fun removeView(file: File) {
            val path: Path = file.toPath()
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
            if (!Files.isSymbolicLink(path) && file.isDirectory) {
                val children = file.listFiles() ?: throw IOException("Cannot list ${file.path} for cleanup")
                for (child in children) removeView(child)
            }
            Files.deleteIfExists(path)
        }
    }
}
