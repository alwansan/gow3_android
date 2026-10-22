package com.gow3.android.data.archive



import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 纯存档归档逻辑：将游戏的 `savedata/` 目录树打包成保留 `savedata/<user_id>/<title>/...`
 * 前缀的 `.zip`，校验此类归档（防 zip-slip、校验布局和标题）、解压到暂存目录，
 * 并为备份目录命名。
 *
 * 保持不依赖 Android，以便宿主端 JVM 测试可以直接验证它，与 `Gow3Settings`（原 Gow3Ini）思路一致。
 * 用户 id 固定为 [USER_ID]（`1`）；标题目录名**不是常量**，由运行时按游戏
 * `sce_sys/param.sfo` 的 `INSTALL_DIR_SAVEDATA`／`TITLE_ID` 推导（`src/probe.c`）。
 * 因此调用方必须把期望目录名作为 [validate] 的 `expectedTitle` 传入；传 null 表示
 * 推导不出，此时退到形状检查（接受任意非空标题目录名，仍保留 zip-slip 与结构校验）。
 *
 * 只有当所有条目都位于 `savedata/<user_id>/<title>/` 之下（含 `<title>.memory/` 的
 * SaveDataMemory 目录）时，归档才会被接受。
 */
/**
 * 存档归档校验失败的原因；由 UI 层（SaveManager）映射为本地化提示文本。
 * 纯 JVM 类型，不依赖 Android。
 */
internal sealed class SaveArchiveProblem {
    object NotAZip : SaveArchiveProblem()
    object NoContent : SaveArchiveProblem()
    data class UnsafePath(val name: String) : SaveArchiveProblem()
    data class DuplicateEntry(val name: String) : SaveArchiveProblem()
    data class WrongLayout(val name: String) : SaveArchiveProblem()
    /** [expected] 为当前游戏推导出的期望目录名；null 表示推导不出（形状检查下的命名问题）。 */
    data class UnsupportedTitle(val name: String, val expected: String?) : SaveArchiveProblem()
}

/** [SaveArchive.check] 的结果：[problem] 为 null 时归档可导入，[title] 为归档中检测到的
 *  标题目录名（去 `.memory` 后缀；未检测到或归档不可用时为 null）。 */
internal class SaveArchiveCheck(val problem: SaveArchiveProblem?, val title: String?)

internal object SaveArchive {

    const val USER_ID = "1"
    private const val ROOT = "savedata"

    /**
     * 可以在这些区域代号之间互相导入存档：God of War III 的 savedata 数据文件
     * （`AUTOSAVE/SAVEDATA` 等）不内嵌 title_id，目录名只是按代号分区，因此把
     * `savedata/<user>/<source>/...` 重命名为期望代号即可被运行时读取。
     */
    val ALLOWED_TITLES = setOf("CUSA01740", "CUSA01715")

    /** 当目录中至少含一个存档文件（游戏非空的 savedata 目录树）时为 true。 */
    fun hasSaves(savedata: File): Boolean =
        savedata.isDirectory && savedata.walkTopDown().any { it.isFile }

    /** `yyyyMMdd-HHmmss`，与 Linux 侧 save_backup.py 使用相同的时间戳格式。 */
    fun stamp(now: Date = Date()): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)

    /** [backupsRoot] 下第一个可用的备份名：时间戳，然后是 `<stamp>-2`、`<stamp>-3`…… */
    fun backupName(backupsRoot: File, stamp: String): String {
        var name = stamp
        var n = 2
        while (File(backupsRoot, name).exists()) {
            name = "$stamp-$n"
            n++
        }
        return name
    }

    /** 将整个 [savedata] 目录树写入 [target] 的 zip，条目保留 `savedata/` 前缀。 */
    fun pack(savedata: File, target: File) {
        val root = savedata.parentFile ?: throw IllegalStateException("savedata has no parent directory")
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            savedata.walkTopDown().forEach { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                if (file.isDirectory) {
                    zip.putNextEntry(ZipEntry("$relative/"))
                    zip.closeEntry()
                } else {
                    zip.putNextEntry(ZipEntry(relative))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /**
     * 校验 [input] 并检测归档内的标题目录名。
     *
     * [expectedTitle] 为当前游戏推导出的期望标题目录名；null 表示推导不出，退到形状检查。
     * 校验只拒绝结构问题（zip-slip、布局、重复、空归档）和完全不在 [ALLOWED_TITLES] 内的
     * 代号 —— 不同区域代号（例如 CUSA01715 ↔ CUSA01740）在可导入集合内时允许通过，
     * 由调用方在 [extract] 时重写为目标代号。
     */
    fun check(input: InputStream, expectedTitle: String?): SaveArchiveCheck {
        val seen = HashSet<String>()
        val titles = LinkedHashSet<String>()
        var contentFiles = 0
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    val problem = validateEntry(name, seen, expectedTitle)
                    if (problem != null) return SaveArchiveCheck(problem, null)
                    titleOf(name)?.let { titles.add(it) }
                    if (!entry.isDirectory) contentFiles++
                }
            }
        } catch (_: Exception) {
            return SaveArchiveCheck(SaveArchiveProblem.NotAZip, null)
        }
        if (contentFiles == 0) return SaveArchiveCheck(SaveArchiveProblem.NoContent, null)
        // 归档只允许含一个标题目录：多个代号无法确定该重写到哪个，拒绝而不是合并。
        if (titles.size > 1) {
            return SaveArchiveCheck(SaveArchiveProblem.UnsupportedTitle(titles.first(), expectedTitle), null)
        }
        return SaveArchiveCheck(null, titles.singleOrNull())
    }

    /** [check] 的别名：只需要知道是否可导入、不关心检测出的代号时使用。 */
    fun validate(input: InputStream, expectedTitle: String?): SaveArchiveProblem? =
        check(input, expectedTitle).problem

    private fun validateEntry(name: String, seen: MutableSet<String>, expectedTitle: String?): SaveArchiveProblem? {
        if (!isSafePath(name)) return SaveArchiveProblem.UnsafePath(name)
        if (!seen.add(name)) return SaveArchiveProblem.DuplicateEntry(name)
        if (!name.startsWith(ROOT)) return SaveArchiveProblem.WrongLayout(name)
        if (!isSupportedName(name, expectedTitle)) return SaveArchiveProblem.UnsupportedTitle(name, expectedTitle)
        return null
    }

    /** 拒绝绝对路径、`..` 和空段（zip-slip 防护）。 */
    private fun isSafePath(name: String): Boolean {
        if (name.isEmpty() || name.startsWith("/") || name.contains('\\')) return false
        // 结尾斜杠表示目录条目，并非空段。
        return name.trimEnd('/').split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    /**
     * 接受 `savedata/` 结构树，以及 `savedata/<user_id>/<title>(/)` 或 `<title>.memory` 下的内容。
     * [expectedTitle] 非 null 时，标题目录名须为期望代号（或它的 `.memory`），或在
     * [ALLOWED_TITLES] 兼容集合内；为 null 时只要求非空（形状检查）。
     */
    private fun isSupportedName(name: String, expectedTitle: String?): Boolean {
        val parts = name.trimEnd('/').split('/')
        return when {
            parts.size == 1 -> parts[0] == ROOT
            parts[0] != ROOT || parts[1] != USER_ID -> false
            parts.size == 2 -> true
            expectedTitle == null -> parts[2].isNotEmpty()
            else -> isAllowedTitle(parts[2], expectedTitle)
        }
    }

    /** [title] 是当前游戏的期望代号（含 `.memory` 后缀）或兼容集合中的另一个代号。 */
    private fun isAllowedTitle(title: String, expectedTitle: String): Boolean {
        if (title == expectedTitle || title == "$expectedTitle.memory") return true
        val base = title.removeSuffix(".memory")
        return base in ALLOWED_TITLES
    }

    /** 从条目名提取标题目录名（去 `.memory` 后缀）；非存档内容或布局外返回 null。 */
    private fun titleOf(name: String): String? {
        val parts = name.trimEnd('/').split('/')
        if (parts.size < 3 || parts[0] != ROOT || parts[1] != USER_ID) return null
        val title = parts[2].removeSuffix(".memory")
        return title.takeIf { it.isNotEmpty() }
    }

    /**
     * 将已校验的归档解压到 [staging]（它将成为新的 `savedata`）。
     * [targetTitle] 非 null 时，把归档内的标题目录名（含 `.memory` 后缀）重写为目标代号，
     * 从而让不同区域代号的存档落到运行时读取的目录下。
     */
    fun extract(input: InputStream, staging: File, targetTitle: String? = null) {
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (name == ROOT || name == "$ROOT/") continue
                var relative = name.removePrefix("$ROOT/")
                if (targetTitle != null) {
                    val parts = relative.split('/')
                    val rewritten = if (parts.size >= 3 && parts[0] == USER_ID) {
                        val base = parts[1].removeSuffix(".memory")
                        if (base in ALLOWED_TITLES && base != targetTitle) {
                            val newTitle = if (parts[1].endsWith(".memory")) "$targetTitle.memory" else targetTitle
                            listOf(parts[0], newTitle) + parts.drop(2)
                        } else parts
                    } else parts
                    relative = rewritten.joinToString("/")
                }
                val target = File(staging, relative)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
        }
    }

    /**
     * 用 [staging] 替换 [savedata]。先把当前目录树重命名到一边，因此最终重命名
     * 失败时可以恢复原目录，绝不会留下空的 `savedata`。
     */
    fun replaceStaging(staging: File, savedata: File) {
        val old = File(savedata.parentFile, "savedata.old")
        old.deleteRecursively()
        if (savedata.exists() && !savedata.renameTo(old)) throw IllegalStateException("could not move aside the existing savedata")
        if (!staging.renameTo(savedata)) {
            if (old.exists()) old.renameTo(savedata)
            throw IllegalStateException("could not apply the imported saves")
        }
        old.deleteRecursively()
    }
}
