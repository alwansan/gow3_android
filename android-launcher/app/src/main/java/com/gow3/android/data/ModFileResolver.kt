package com.gow3.android.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipFile
import org.json.JSONObject

/**
 * 上游 `scripts/mods.py` 语义的 Kotlin 移植（Android launcher 侧）：模组目录发现/布局判定、
 * 数据目录学习、加载顺序/禁用、冲突消解、ZIP 投放与 `mod.json` 元数据。纯文件逻辑，不持有视图；
 * 只读不写模组与游戏文件，仅写自己的状态文件（mods.json 等由调用方负责）。
 */
object ModFileResolver {

    /** 模组可以遮蔽的顶层目录（casefold）：游戏数据在 EXEC 与根目录的每个语言目录里，dvdroot_ps4 是 shadPS4 布局。 */
    val GAME_ROOTS: Set<String> = setOf(
        "dvdroot_ps4", "exec", "dutch", "english", "french", "german", "italian",
        "polish", "portuguese", "russian", "spanish",
    )

    /** 永远不通过覆盖层替换的目录：可执行补丁走补丁编译器。 */
    val PROTECTED: Set<String> = setOf("eboot.bin", "sce_module", "sce_sys")

    /** 模组游戏目录旁边只描述模组的文件；wrapper 文件夹也可以携带它们。 */
    val METADATA: Set<String> = setOf(".txt", ".md", ".jpg", ".jpeg", ".png", ".ini", ".json")
    val IMAGES: Set<String> = setOf(".png", ".jpg", ".jpeg")
    val FIRST_IMAGES: List<String> = listOf("preview", "cover", "screenshot")

    const val ZIP_STAMP = ".from-zip"
    private const val ZIP_LIMIT = 16L shl 30 // 一个模组 zip 解压的上限（拒绝 zip 炸弹）

    /** `name` 位于 `folder` 内，大小写不敏感匹配已有条目（Windows 制作的模组常与游戏的小写名不同）。 */
    fun child(folder: File, name: String): File {
        val entries = folder.listFiles() ?: return File(folder, name)
        for (entry in entries) {
            if (entry.name.lowercase() == name.lowercase()) return entry
        }
        return File(folder, name)
    }

    /** 只允许该游戏的可用数据目录；绝不从游戏学习可执行/系统目录。 */
    fun dataRoots(game: File?): Set<String> {
        if (game == null || !game.isDirectory) return GAME_ROOTS
        val entries = game.listFiles() ?: return GAME_ROOTS
        return GAME_ROOTS + entries.filter { it.isDirectory && it.name.lowercase() !in PROTECTED }.map { it.name.lowercase() }
    }

    fun hasGameRoot(folder: File, roots: Set<String>): Boolean {
        if (!folder.isDirectory) return false
        return folder.listFiles()?.any { it.isDirectory && it.name.lowercase() in roots } == true
    }

    /**
     * 模组文件落位：扁平游戏（无 dvdroot_ps4）去掉模组文件的 dvdroot_ps4/ 前缀，
     * 使 dvdroot_ps4/EXEC/... 替换 EXEC/...；可执行/系统路径拒绝。
     */
    fun gamePath(relative: List<String>, flat: Boolean): List<String> {
        var parts = relative
        if (flat && parts.size > 1 && parts[0].lowercase() == "dvdroot_ps4") parts = parts.drop(1)
        if (parts[0].lowercase() in PROTECTED) {
            throw IllegalArgumentException("Executable/system replacement is unsupported: ${parts.joinToString("/")}")
        }
        return parts
    }

    /** 一个模组的游戏内容根：(root, prefix)，无则 null。接受 <mod>/<游戏目录>、<mod>/app0/<游戏目录> 与两者的一层 wrapper。 */
    fun contentRoot(folder: File, roots: Set<String> = GAME_ROOTS): ContentRoot? {
        if (!folder.isDirectory) return null
        for (wrapper in listOf("", "app0")) {
            val base = if (wrapper.isEmpty()) folder else child(folder, wrapper)
            if (hasGameRoot(base, roots)) return ContentRoot(base, "")
        }
        val entries = folder.listFiles()?.filter { !it.name.startsWith(".") } ?: return null
        val folders = entries.filter { it.isDirectory }
        val stray = entries.filter { it.isFile && it.extension.lowercase() !in METADATA }
        if (folders.size == 1 && stray.isEmpty()) return contentRoot(folders[0], roots)
        return null
    }

    /** 模组携带的游戏补丁文件：patches 目录下的 xml，接受与 content_root 相同的单层 wrapper。 */
    fun patchFiles(folder: File): List<File> {
        if (!folder.isDirectory) return emptyList()
        val found = child(folder, "patches")
        if (found.isDirectory) {
            val files = found.listFiles()?.filter { it.isFile && it.extension.lowercase() == "xml" }
                ?.sortedBy { it.name.lowercase() } ?: emptyList()
            if (files.isNotEmpty()) return files
        }
        val entries = folder.listFiles()?.filter { !it.name.startsWith(".") } ?: return emptyList()
        val folders = entries.filter { it.isDirectory }
        val stray = entries.filter { it.isFile && it.extension.lowercase() !in METADATA }
        if (folders.size == 1 && stray.isEmpty()) return patchFiles(folders[0])
        return emptyList()
    }

    data class ContentRoot(val root: File, val prefix: String)

    data class ModFile(val gamePath: List<String>, val source: File)

    data class ModInfo(
        val name: String,
        val author: String,
        val version: String,
        val description: String,
        val url: String,
        val image: File?,
    )

    /**
     * 模组发现：`root` 下带可接受布局或带补丁文件的文件夹（模组名）。投放的 <name>.zip 先解包。
     */
    fun discover(root: File, game: File? = null): List<String> {
        if (!root.isDirectory) return emptyList()
        unpackZips(root, game)
        val roots = dataRoots(game)
        return root.listFiles()?.filter { it.isDirectory && (contentRoot(it, roots) != null || patchFiles(it).isNotEmpty()) }
            ?.map { it.name }?.sortedWith(compareBy({ it.lowercase() }, { it })) ?: emptyList()
    }

    /**
     * 按 mods.json 的 {order, disabled} 选出启用模组：新文件夹按字母序启用追加到末尾。
     * 不存在的配置视为全部可用。
     */
    fun selected(root: File, config: File?, game: File? = null): List<String> {
        val available = discover(root, game)
        if (config == null || !config.isFile) return available
        val settings = try {
            JSONObject(config.readText())
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid mods.json: $error")
        }
        val disabledNames = settings.optJSONArray("disabled") ?: throw IllegalArgumentException("Disabled mods must be a list of folder names")
        val disabled = HashSet<String>()
        for (i in 0 until disabledNames.length()) {
            val name = disabledNames.optString(i)
            if (name.isEmpty()) throw IllegalArgumentException("Disabled mods must be a list of folder names")
            disabled.add(name)
        }
        val orderNames = settings.optJSONArray("order") ?: throw IllegalArgumentException("Mod order must be a list of folder names")
        val order = ArrayList<String>()
        for (i in 0 until orderNames.length()) {
            val name = orderNames.optString(i)
            if (name.isEmpty()) throw IllegalArgumentException("Mod order must be a list of folder names")
            order.add(name)
        }
        // 新文件夹默认启用，按字母序追加。
        val seen = HashSet<String>()
        return (order + available).filter { it in available && it !in disabled && seen.add(it) }
    }

    /** 启用模组携带的补丁文件，按加载顺序（后置文件在同名补丁上胜出）。 */
    fun enabledPatchFiles(root: File, config: File?, enabled: Boolean, game: File? = null): List<File> {
        if (!enabled) return emptyList()
        val result = ArrayList<File>()
        for (name in selected(root, config, game)) {
            result.addAll(patchFiles(File(root, name)))
        }
        return result
    }

    /**
     * 模组替换/新增的每个文件：(gamePath, source)。只产出位于 dataRoots 下的文件；
     * 元数据留在覆盖层之外；可执行/系统替换直接拒绝；模组内链接拒绝。
     */
    fun modFiles(folder: File, game: File? = null): Sequence<ModFile> = sequence {
        val roots = dataRoots(game)
        val layout = contentRoot(folder, roots)
            ?: if (patchFiles(folder).isNotEmpty()) return@sequence
            else throw IllegalArgumentException("$folder: expected EXEC, a language folder or dvdroot_ps4 inside the mod folder")
        val root = layout.root.canonicalFile
        walk(root, layout.prefix, roots).forEach { (relative, source) ->
            yield(ModFile(relative, source))
        }
    }

    /** 深度遍历 [root]，拒绝链接；产出每个文件与其相对路径。 */
    private fun walk(root: File, prefix: String, roots: Set<String>): Sequence<Pair<List<String>, File>> = sequence {
        val stack = ArrayDeque<Pair<File, List<String>>>()
        stack.add(root to emptyList())
        while (stack.isNotEmpty()) {
            val (directory, parents) = stack.removeLast()
            val entries = directory.listFiles()?.sortedBy { it.name } ?: continue
            for (entry in entries) {
                if (Files.isSymbolicLink(entry.toPath())) {
                    throw IllegalArgumentException("Mod links are unsupported: ${entry.path}")
                }
                val relative = if (prefix.isEmpty()) parents + entry.name else parents + prefix + entry.name
                if (entry.isDirectory) {
                    stack.add(entry to relative)
                } else if (entry.isFile) {
                    if (relative[0].lowercase() !in roots) {
                        if (relative[0].lowercase() in PROTECTED) {
                            throw IllegalArgumentException("Executable/system replacement is unsupported: ${entry.path}")
                        }
                        continue // 元数据
                    }
                    yield(relative to entry)
                }
            }
        }
    }

    /** 不止一个模组（按加载顺序）替换的文件：[(gamePath, [mods])]，列表最后一个模组胜出。路径大小写不敏感。 */
    fun conflicts(root: File, names: List<String>, game: File? = null): List<Conflict> {
        val owners = LinkedHashMap<String, Pair<String, MutableList<String>>>()
        for (name in names) {
            try {
                for ((relative, _) in modFiles(File(root, name), game)) {
                    val path = gamePath(relative, flat = true).joinToString("/")
                    val key = path.lowercase()
                    owners.getOrPut(key) { path to ArrayList() }.second.add(name)
                }
            } catch (error: Exception) {
                // 不可读模组跳过，与 Python 参考一致。
            }
        }
        return owners.values.filter { it.second.size > 1 }
            .map { Conflict(it.first, it.second.toList()) }
            .sortedBy { it.path.lowercase() }
    }

    data class Conflict(val path: String, val mods: List<String>)

    /** 预设应用到当前可用模组：(order, disabled)，缺失的丢弃、新的追加并启用。 */
    fun applyPreset(preset: JSONObject, available: List<String>): Pair<List<String>, List<String>> {
        val orderNames = preset.optJSONArray("order")
        val disabledNames = preset.optJSONArray("disabled")
        val order = ArrayList<String>()
        if (orderNames != null) for (i in 0 until orderNames.length()) order.add(orderNames.optString(i))
        val disabled = HashSet<String>()
        if (disabledNames != null) for (i in 0 until disabledNames.length()) disabled.add(disabledNames.optString(i))
        val seen = HashSet<String>()
        val effectiveOrder = (order + available).filter { it in available && seen.add(it) }
        return effectiveOrder to disabled.filter { it in available }
    }

    /** launcher 展示的模组信息：mod.json，缺省用文件夹名与 readme 前段；image 是预览图或任一图片。 */
    fun modInfo(folder: File, game: File? = null): ModInfo {
        val layout = contentRoot(folder, dataRoots(game))
        val places = linkedSetOf(folder, layout?.root ?: folder)
        val files = ArrayList<File>()
        for (place in places) {
            place.listFiles()?.filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }?.sortedBy { it.name.lowercase() }?.let { files.addAll(it) }
        }
        var info = ModInfo(folder.name, "", "", "", "", null)
        val meta = files.firstOrNull { it.name.lowercase() == "mod.json" }
        val fields = try {
            meta?.let { JSONObject(it.readText()) }
        } catch (error: Exception) {
            null
        }
        if (fields != null) {
            val url = fields.optString("url").trim()
            info = ModInfo(
                fields.optString("name").trim().ifEmpty { info.name },
                fields.optString("author").trim(),
                fields.optString("version").trim(),
                fields.optString("description").trim(),
                if (url.lowercase().startsWith("http://") || url.lowercase().startsWith("https://")) url else "",
                info.image,
            )
        }
        if (info.description.isEmpty()) {
            val readme = files.firstOrNull { it.nameWithoutExtension.lowercase() == "readme" && it.extension.lowercase() in setOf("txt", "md") }
            if (readme != null) {
                try {
                    info = info.copy(description = readme.readText(Charsets.UTF_8).trim().take(400))
                } catch (error: IOException) {
                    // 不可读 readme 保持空描述。
                }
            }
        }
        val images = files.filter { it.extension.lowercase() in IMAGES }
            .sortedBy { FIRST_IMAGES.indexOf(it.nameWithoutExtension.lowercase()).let { idx -> if (idx >= 0) idx else FIRST_IMAGES.size } }
        return info.copy(image = images.firstOrNull())
    }

    /** 文件类型位：unix mode 的 S_IFMT / S_IFLNK（等价 0o170000 / 0o120000）。 */
    private const val S_IFMT = 0xF000
    private const val S_IFLNK = 0xA000

    /**
     * 读取 zip 中央目录中每个条目的 unix 文件类型位。Android 的 java.util.zip.ZipEntry 不公开
     * external attributes（JDK 的 getExternalAttributes 在 Android 缺失），因此直接扫描中央目录：
     * 每个 CD 条目第 38 字节起 4 字节小端 external file attributes，高 16 位即 unix mode。
     */
    fun unixTypes(file: File): Map<String, Int> {
        val result = HashMap<String, Int>()
        java.io.RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length < 22) return result
            // End Of Central Directory：从尾部向前找签名 0x06054b50。
            val tail = minOf(length, 65557)
            val buffer = ByteArray(tail.toInt())
            raf.seek(length - tail)
            raf.readFully(buffer)
            var eocd = -1
            for (i in buffer.size - 22 downTo 0) {
                if (buffer[i] == 0x50.toByte() && buffer[i + 1] == 0x4b.toByte() &&
                    buffer[i + 2] == 0x05.toByte() && buffer[i + 3] == 0x06.toByte()) {
                    eocd = i
                    break
                }
            }
            if (eocd < 0) return result
            val cdOffset = leInt(buffer, eocd + 16)
            val cdSize = leInt(buffer, eocd + 12)
            if (cdOffset <= 0 || cdSize <= 0) return result
            val cd = ByteArray(cdSize)
            raf.seek(cdOffset.toLong())
            raf.readFully(cd)
            var offset = 0
            while (offset + 46 <= cd.size && leInt(cd, offset) == 0x02014b50) {
                val nameLength = leShort(cd, offset + 28).toInt()
                val extraLength = leShort(cd, offset + 30).toInt()
                val commentLength = leShort(cd, offset + 32).toInt()
                val external = leInt(cd, offset + 38)
                val name = String(cd, offset + 46, nameLength, Charsets.UTF_8)
                result[name] = (external ushr 16) and 0xFFFF
                offset += 46 + nameLength + extraLength + commentLength
            }
        }
        return result
    }

    private fun leShort(bytes: ByteArray, offset: Int): Int {
        val low = bytes[offset].toInt() and 0xFF
        val high = bytes[offset + 1].toInt() and 0xFF
        return low or (high shl 8)
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int {
        var value = 0
        for (i in 0 until 4) value = value or ((bytes[offset + i].toInt() and 0xFF) shl (8 * i))
        return value
    }

    /** zip 成员（info, relative path）；任何能写出模组文件夹之外的内容（绝对路径/..）或链接都拒绝。 */
    fun zipMembers(archive: ZipFile, types: Map<String, Int> = emptyMap()): List<Pair<java.util.zip.ZipEntry, List<String>>> {
        var total = 0L
        val members = ArrayList<Pair<java.util.zip.ZipEntry, List<String>>>()
        val entries = archive.entries()
        while (entries.hasMoreElements()) {
            val info = entries.nextElement()
            val parts = info.name.replace('\\', '/').split('/').filter { it.isNotEmpty() }
            if (info.isDirectory && parts.isEmpty()) continue
            if (parts.isEmpty() || info.name.startsWith("/") || ".." in parts ||
                parts.any { part -> ":" in part || "\u0000" in part || part.trimEnd(' ', '.') != part }) {
                throw IllegalArgumentException("unsafe path in the zip: ${info.name}")
            }
            val type = types[info.name] ?: 0
            if ((type and S_IFMT) == S_IFLNK) throw IllegalArgumentException("links are not allowed in a mod zip: ${info.name}")
            total += info.size
            if (total > ZIP_LIMIT) throw IllegalArgumentException("the zip unpacks to more than 16 GiB")
            members.add(info to parts)
        }
        return members
    }

    /**
     * 把 <root>/<name>.zip 解包为 <root>/<name>/（投放到 mods 文件夹的 zip）。zip 变化后重新解包；
     * 手工创建的已存在同名文件夹不被覆盖。返回本次解包的名称；无法解包的 zip 报告并跳过。
     */
    fun unpackZips(root: File, game: File? = null): List<String> {
        val done = ArrayList<String>()
        if (!root.isDirectory) return done
        val archives = root.listFiles()?.filter { it.extension.lowercase() == "zip" }?.sortedBy { it.name.lowercase() } ?: return done
        val resolvedRoot = root.canonicalFile
        for (archivePath in archives) {
            val target = File(root, archivePath.nameWithoutExtension)
            try {
                val resolvedTarget = target.canonicalFile
                if (resolvedTarget.parentFile != resolvedRoot) {
                    println("Mods: ${archivePath.name}: unsafe extraction folder")
                    continue
                }
            } catch (error: IOException) {
                println("Mods: ${archivePath.name}: $error")
                continue
            }
            val stamp = "${archivePath.length()} ${archivePath.lastModified()}"
            val marker = File(target, ZIP_STAMP)
            if (target.exists() && !(marker.isFile && marker.readText() != stamp)) {
                if (!marker.isFile) {
                    println("Mods: ${archivePath.name}: a folder called ${target.name} is already there; the zip is not unpacked")
                }
                continue
            }
            try {
                ZipFile(archivePath).use { archive ->
                    val members = zipMembers(archive, unixTypes(archivePath))
                    val destinations = members.map { (info, parts) ->
                        var current = target
                        for (part in parts.dropLast(1)) current = File(current, part)
                        info to File(current, parts.last())
                    }
                    if (destinations.any { !it.second.canonicalFile.toPath().startsWith(target.canonicalFile.toPath()) }) {
                        throw IllegalArgumentException("unsafe path in the zip: destination outside the mod folder")
                    }
                    if (target.exists()) target.deleteRecursively()
                    for ((info, destination) in destinations) {
                        if (info.isDirectory) {
                            destination.mkdirs()
                            continue
                        }
                        destination.parentFile?.mkdirs()
                        archive.getInputStream(info).use { input ->
                            destination.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
                target.mkdirs()
                marker.writeText(stamp)
                done.add(archivePath.nameWithoutExtension)
                val kind = if (contentRoot(target, dataRoots(game)) != null || patchFiles(target).isNotEmpty()) "a mod"
                    else "not a mod (no EXEC, language folder, dvdroot_ps4 or patches)"
                println("Mods: ${archivePath.name} unpacked: $kind")
            } catch (error: Exception) {
                target.deleteRecursively()
                println("Mods: ${archivePath.name}: $error")
            }
        }
        return done
    }
}
