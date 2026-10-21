package com.gow3.android.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

/**
 * 把玩家选中的模组导入托管 `mods/` 目录：ZIP 原样落地（由 `ModFileResolver.unpackZips` 安全解包），
 * 文件夹递归复制。只读外部内容，不修改源；文件名过滤为安全字符，避免路径分隔符与控制字符。
 */
object ModImporter {

    private const val MAX_NAME = 80
    private const val MAX_DEPTH = 32

    /** 导入模组 zip：返回落地名（不含 .zip），失败返回 null。 */
    fun importZip(context: Context, uri: Uri, modsDir: File): String? {
        val name = safeName(displayName(context, uri) ?: return null).removeSuffix(".zip")
        if (name.isEmpty()) return null
        modsDir.mkdirs()
        val target = File(modsDir, "$name.zip")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            name
        } catch (error: Exception) {
            target.delete()
            null
        }
    }

    /** 导入模组文件夹：递归复制到 `mods/<name>/`，返回落地名，失败返回 null。 */
    fun importFolder(context: Context, uri: Uri, modsDir: File): String? {
        val name = safeName(displayName(context, uri) ?: return null)
        if (name.isEmpty()) return null
        modsDir.mkdirs()
        val target = File(modsDir, name)
        return try {
            copyTree(context, uri, target, 0)
            name
        } catch (error: Exception) {
            target.deleteRecursively()
            null
        }
    }

    /** 文件名只保留安全字符（字母数字、空格与少量标点），去掉首尾空格与点，并限长。 */
    private fun safeName(raw: String): String {
        val cleaned = raw.trim().filter { it.isLetterOrDigit() || it in " ._-()[]+" }.trim(' ', '.')
        return if (cleaned.length > MAX_NAME) cleaned.substring(0, MAX_NAME).trim() else cleaned
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column)?.takeIf { it.isNotBlank() } else null
            }
    } catch (error: Exception) {
        null
    }

    /** SAF 树递归复制；目录按文档条目遍历，文件走 openInputStream。深度受限防环。 */
    private fun copyTree(context: Context, treeUri: Uri, target: File, depth: Int) {
        if (depth > MAX_DEPTH) throw IOException("mod folder is nested too deeply")
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri),
        )
        context.contentResolver.query(children, null, null, null, null)?.use { cursor ->
            val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn) ?: continue
                val mime = cursor.getString(mimeColumn) ?: ""
                val documentId = cursor.getString(idColumn) ?: continue
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                val destination = File(target, safeName(name))
                if (destination.name.isEmpty() || destination.name == "." || destination.name == "..") continue
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    destination.mkdirs()
                    copyTree(context, childUri, destination, depth + 1)
                } else {
                    destination.parentFile?.mkdirs()
                    context.contentResolver.openInputStream(childUri)?.use { input ->
                        destination.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IOException("cannot read $name")
                }
            }
        } ?: throw IOException("cannot list the selected folder")
    }
}
