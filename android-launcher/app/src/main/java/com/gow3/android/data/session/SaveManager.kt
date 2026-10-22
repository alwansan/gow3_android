package com.gow3.android.data.session

import com.gow3.android.core.settings.GamePrefs
import com.gow3.android.data.Gow3Data
import com.gow3.android.data.archive.SaveArchive
import com.gow3.android.data.archive.SaveArchiveProblem
import com.gow3.android.data.model.GameSfo


import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.gow3.android.R
import java.io.File
import java.io.FileInputStream

/** 一次存档导出/导入尝试的结果。Cancelled 表示用户在文件选择器中退出了。 */
internal sealed class SaveResult {
    data class Success(val message: String) : SaveResult()
    object Cancelled : SaveResult()
    data class Failed(val message: String) : SaveResult()
}

/**
 * 负责 God of War III 存档：将 `savedata/` 目录树导出为玩家通过 Storage Access
 * Framework 选择的 `.zip`，并在校验、安全备份和原子替换后导入此类归档。
 * 只触及 `filesDir/gow3data/user/savedata` 及其 `savedata_backups`；
 * 运行时、gow3.ini、运行时安装和游戏文件绝不被修改。
 */
internal class SaveManager(private val activity: Activity) {

    companion object {
        /** 区别于 GameFolderManager 的 1001/1002 和 LosslessDllManager 的 1003。 */
        const val REQUEST_EXPORT = 1004
        const val REQUEST_IMPORT = 1005
        private const val DEFAULT_EXPORT_NAME = "GodOfWar3-Saves"
    }

    /** 由 [launchExport]/[launchImport] 设置，由 [onActivityResult] 消费。 */
    private var pending: ((SaveResult) -> Unit)? = null

    /** 游戏目录/补丁目录的读取统一走 GamePrefs（core/settings）。 */
    private val gamePrefs = GamePrefs(activity)

    /** 游戏的 savedata，即运行时以 `--user <user_dir>` 接收后在 `<user_dir>/savedata` 读写的目录。 */
    fun savedataDir(): File = File(Gow3Data.user(activity), "savedata")

    /** 每次导入前创建的安全备份，与 Linux 侧 savedata_backups 布局一致。 */
    fun savedataBackupsDir(): File = File(Gow3Data.user(activity), "savedata_backups")

    /**
     * 当前游戏推导出的存档标题目录名，规则与运行时 `src/probe.c` 逐字对齐：
     * 取 `sce_sys/param.sfo` 的 `INSTALL_DIR_SAVEDATA`，其次 `TITLE_ID`。
     *
     * 已配置升级补丁目录时优先读它的 `param.sfo` —— 运行时的 `--app0` 是合并后的
     * game+update 树，冲突时 update 覆盖 base（真机日志里 `APP_VER` 取 update 的值）。
     *
     * 都读不到时返回 null，调用方退到形状检查。
     */
    fun expectedTitle(): String? {
        val candidates = listOfNotNull(
            gamePrefs.updateDir(),
            gamePrefs.gameDir(),
        )
        for (dir in candidates) {
            val name = GameSfo.saveDirName(File(dir, "sce_sys/param.sfo"))
            if (name != null) return name
        }
        return null
    }

    /**
     * 打开系统“另存为”选择器。当没有存档时，立即以失败调用 [onResult]，且不显示
     * 选择器。其他情况下，结果通过 activity 的 legacy 回调经由 [onActivityResult] 到达。
     */
    fun launchExport(onResult: (SaveResult) -> Unit) {
        if (!SaveArchive.hasSaves(savedataDir())) {
            onResult(SaveResult.Failed(activity.getString(R.string.save_no_saves)))
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "$DEFAULT_EXPORT_NAME-${SaveArchive.stamp()}.zip")
        }
        pending = onResult
        try {
            activity.startActivityForResult(intent, REQUEST_EXPORT)
        } catch (ex: Exception) {
            pending = null
            onResult(SaveResult.Failed(activity.getString(R.string.no_file_picker_fmt, ex.message ?: activity.getString(R.string.unknown_error))))
        }
    }

    /**
     * 打开系统文件选择器以选择存档归档。[onResult] 一定会执行：用户取消、没有安装
     * 选择器、归档被拒绝和替换失败都会以 [SaveResult] 返回，绝不会抛异常。
     */
    fun launchImport(onResult: (SaveResult) -> Unit) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
        }
        pending = onResult
        try {
            activity.startActivityForResult(intent, REQUEST_IMPORT)
        } catch (ex: Exception) {
            pending = null
            onResult(SaveResult.Failed(activity.getString(R.string.no_file_picker_fmt, ex.message ?: activity.getString(R.string.unknown_error))))
        }
    }

    /** @return 当请求属于我们且已被消费时返回 true。 */
    fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?): Boolean {
        val isExport = requestCode == REQUEST_EXPORT
        val isImport = requestCode == REQUEST_IMPORT
        if (!isExport && !isImport) return false
        val callback = pending
        pending = null
        if (callback == null) return true
        val uri = intent?.data
        if (resultCode != Activity.RESULT_OK || uri == null) {
            callback(SaveResult.Cancelled)
            return true
        }
        Thread {
            val result = if (isExport) exportTo(uri) else importFrom(uri)
            activity.runOnUiThread { callback(result) }
        }.start()
        return true
    }

    /**
     * 先把 savedata 目录树打包成临时 zip（SAF 流不可 seek），再复制到选择器返回的 URI。
     * 无论成功或失败，临时文件始终会被删除。
     */
    private fun exportTo(uri: Uri): SaveResult {
        val savedata = savedataDir()
        if (!SaveArchive.hasSaves(savedata)) return SaveResult.Failed(activity.getString(R.string.save_no_saves))
        val temporary = File(Gow3Data.root(activity), ".export-saves.tmp")
        temporary.delete()
        return try {
            SaveArchive.pack(savedata, temporary)
            val output = activity.contentResolver.openOutputStream(uri)
                ?: return SaveResult.Failed(activity.getString(R.string.save_write_failed))
            output.use { out ->
                FileInputStream(temporary).use { it.copyTo(out) }
            }
            SaveResult.Success(activity.getString(R.string.save_exported_fmt, displayName(uri) ?: activity.getString(R.string.selected_location)))
        } catch (ex: Exception) {
            SaveResult.Failed(activity.getString(R.string.save_export_failed_fmt, ex.message ?: activity.getString(R.string.unknown_error)))
        } finally {
            temporary.delete()
        }
    }

    /** 校验、解压到暂存目录、备份当前存档并替换生效。 */
    private fun importFrom(uri: Uri): SaveResult {
        val user = Gow3Data.user(activity)
        val staging = File(user, "savedata.restoring")
        // 期望的标题目录名从当前游戏推导；推导不出时传 null，校验退到形状检查。
        val expected = expectedTitle()
        return try {
            val first = activity.contentResolver.openInputStream(uri)
                ?: return SaveResult.Failed(activity.getString(R.string.save_read_failed))
            val check = first.use { SaveArchive.check(it, expected) }
            if (check.problem != null) return SaveResult.Failed(saveArchiveProblemMessage(check.problem))

            staging.deleteRecursively()
            val second = activity.contentResolver.openInputStream(uri)
                ?: return SaveResult.Failed(activity.getString(R.string.save_read_failed))
            // 归档来自兼容区域代号（例如 CUSA01715）时重写为当前游戏目录，否则原样解压。
            val remapped = check.title != null && expected != null && check.title != expected
            second.use { SaveArchive.extract(it, staging, expected) }

            val savedata = savedataDir()
            val backup = backupExisting(savedata)
            SaveArchive.replaceStaging(staging, savedata)
            val result = when {
                remapped && backup != null ->
                    activity.getString(R.string.save_imported_remapped_with_backup_fmt, check.title, expected, backup.name)
                remapped -> activity.getString(R.string.save_imported_remapped_fmt, check.title, expected)
                backup != null -> activity.getString(R.string.save_imported_with_backup_fmt, backup.name)
                else -> activity.getString(R.string.save_imported)
            }
            SaveResult.Success(result)
        } catch (ex: Exception) {
            staging.deleteRecursively()
            SaveResult.Failed(activity.getString(R.string.save_import_failed_fmt, ex.message ?: activity.getString(R.string.unknown_error)))
        }
    }

    /** 将当前存档复制到带时间戳的备份目录；没有可备份内容时返回 null。 */
    private fun backupExisting(savedata: File): File? {
        if (!SaveArchive.hasSaves(savedata)) return null
        val root = savedataBackupsDir()
        if (!root.isDirectory && !root.mkdirs()) return null
        val target = File(root, SaveArchive.backupName(root, SaveArchive.stamp()))
        if (!savedata.copyRecursively(target)) return null
        return target
    }

    /** 把纯逻辑的 [SaveArchiveProblem] 映射为本地化提示文本。 */
    private fun saveArchiveProblemMessage(problem: SaveArchiveProblem): String = when (problem) {
        is SaveArchiveProblem.NotAZip -> activity.getString(R.string.save_archive_not_zip)
        is SaveArchiveProblem.NoContent -> activity.getString(R.string.save_archive_no_content)
        is SaveArchiveProblem.UnsafePath -> activity.getString(R.string.save_archive_unsafe_path_fmt, problem.name)
        is SaveArchiveProblem.DuplicateEntry -> activity.getString(R.string.save_archive_duplicate_fmt, problem.name)
        is SaveArchiveProblem.WrongLayout -> activity.getString(R.string.save_archive_wrong_layout_fmt, problem.name)
        is SaveArchiveProblem.UnsupportedTitle ->
            // 报告推导出的期望目录名，而不是某个固定的区域代号。expected 为 null 时形状检查
            // 已放行任意非空标题目录名，因此这里实际不会遇到；保守回退到布局提示。
            if (problem.expected != null) activity.getString(R.string.save_archive_title_fmt, problem.name, problem.expected)
            else activity.getString(R.string.save_archive_wrong_layout_fmt, problem.name)
    }

    /** Provider 的显示名称，回退到 URI 的最后一段。 */
    private fun displayName(uri: Uri): String? {        try {
            activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) {
                    val name = cursor.getString(column)
                    if (!name.isNullOrEmpty()) return name
                }
            }
        } catch (_: Exception) {
            // 回退到 URI 段。
        }
        val segment = uri.lastPathSegment ?: return null
        return segment.substringAfterLast('/').takeIf { it.isNotEmpty() }
    }
}
