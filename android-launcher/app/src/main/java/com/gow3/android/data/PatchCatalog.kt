package com.gow3.android.data

import com.gow3.android.data.model.GameSfo


import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import android.content.res.AssetManager

enum class PatchGroup { RESOLUTION, FRAME_RATE, VIDEO, FIX, EFFECT }

/** 补丁来源：内置目录（APK 资产）或启用模组携带的 patches 目录下的 xml。 */
enum class PatchSource { BUILTIN, MOD }

/**
 * One <Line> of a patch: the site it patches (a signature plus an offset inside the match, or the
 * reference build's address) and the bytes to write there. [target] names a <Target> the line writes
 * instead of patching a site, and [value] may hold {rel:NAME:TAIL} placeholders.
 */
data class PatchLine(
    val type: String,
    val address: String,
    val value: String,
    val at: String,
    val target: String,
    val pattern: String,
)

/** A <Target> a patch refers to: either a whole line's site, or a place found by signature. */
data class PatchTarget(
    val name: String,
    val line: String,
    val at: String,
    val ref: Boolean,
    val tail: String,
    val pattern: String,
)

data class GamePatch(
    val name: String,
    val note: String,
    val author: String,
    val validation: String,
    val defaultEnabled: Boolean,
    val appVersion: String,
    val appElf: String,
    val lines: List<PatchLine>,
    val targets: List<PatchTarget>,
    val source: PatchSource = PatchSource.BUILTIN,
) {
    val resolution: String? get() = name.takeIf { it.startsWith("Resolution Patch - ") }?.removePrefix("Resolution Patch - ")
    /** 补丁页/设置页显示名：统一「宽×高」实际像素尺寸（含 × 排版），档位内部值（480p/1280x800…）
     *  只作 ini/补丁名键，不直接展示；与 gow3_overlay.cpp resolution_labels、strings 的档位文案一致。 */
    val displayName: String
        get() = when (resolution) {
            null -> name
            "480p" -> "854×480"
            "540p" -> "960×540"
            "720p" -> "1280×720"
            "900p" -> "1600×900"
            "1080p" -> "1920×1080"
            "1440p" -> "2560×1440"
            "1800p" -> "3200×1800"
            "4K" -> "3840×2160"
            else -> resolution!!.replace("x", "×")
        }
    val fps: String? get() = name.takeIf { it.startsWith("Frame Rate Patch - ") }?.removePrefix("Frame Rate Patch - ")?.removeSuffix(" FPS")
    val group: PatchGroup get() = when {
        resolution != null -> PatchGroup.RESOLUTION
        fps != null -> PatchGroup.FRAME_RATE
        name.startsWith("Skip ") -> PatchGroup.VIDEO
        name.startsWith("Disable ") -> PatchGroup.EFFECT
        else -> PatchGroup.FIX
    }
}

/**
 * The catalogue in the flat text format libgow3prepare reads (gow3_prepare.h): one P block per
 * patch, then its Targets and Lines. Names and patterns contain spaces, so both take the rest of
 * their line; empty fields are a single '-'.
 */
internal fun PatchCatalog.toNativeInput(): String = buildString {
    val field = { value: String -> value.trim().ifEmpty { "-" } }
    for (patch in patches) {
        append("P ").append(field(patch.name)).append('\n')
        append("N ").append(field(patch.note.replace("\\", "\\\\").replace("\n", "\\n"))).append('\n')
        append("V ").append(field(patch.appVersion)).append('\n')
        append("E ").append(field(patch.appElf)).append('\n')
        for (target in patch.targets) {
            append("T ").append(field(target.name)).append(' ')
                .append(field(target.line)).append(' ')
                .append(field(target.at)).append(' ')
                .append(if (target.ref) "1" else "0").append(' ')
                .append(field(target.tail)).append(' ')
                .append(field(target.pattern)).append('\n')
        }
        for (line in patch.lines) {
            append("L ").append(field(line.type)).append(' ')
                .append(field(line.address)).append(' ')
                .append(field(line.value)).append(' ')
                .append(field(line.at)).append(' ')
                .append(field(line.target)).append(' ')
                .append(field(line.pattern)).append('\n')
        }
    }
}

enum class CatalogProblem { NO_GAME, INVALID_GAME, INVALID_UPDATE, UNSUPPORTED, VERSION, READ_FAILED }
class PatchCatalogException(val problem: CatalogProblem) : Exception(problem.name)

data class PatchCatalog(val titleId: String, val appVersion: String, val patches: List<GamePatch>) {
    val selectionKey: String get() = "patches_${titleId}_${appVersion.replace('.', '_')}"

    /** 设置页「跳过动画」快捷开关控制的补丁组：按名匹配，目录缺失的自动忽略。 */
    val skipVideoPatches: List<GamePatch>
        get() = patches.filter { it.name in SKIP_VIDEO_PATCHES }

    /** 分辨率和帧率与首页共用设置，其余选择按 Title ID 和版本隔离。 */
    fun selected(ini: Map<String, String>): Set<String> {
        val selected = if (selectionKey in ini) ini.getValue(selectionKey).split(';').filter { it.isNotBlank() }.toMutableSet()
        else patches.filter { it.defaultEnabled }.map { it.name }.toMutableSet()
        val resolution = ini["render_resolution"] ?: "native"
        val fps = ini["engine_fps"] ?: "120"
        selected.removeAll { it.startsWith("Resolution Patch") || it.startsWith("Frame Rate Patch") }
        patches.firstOrNull { it.resolution == resolution }?.let { selected.add(it.name) }
        patches.firstOrNull { it.fps == fps }?.let { selected.add(it.name) }
        if (resolution != "native") selected.remove(TEXTURE)
        else if (selectionKey !in ini && patches.any { it.name == TEXTURE }) selected.add(TEXTURE)
        return selected.intersect(patches.map { it.name }.toSet())
    }

    fun toggle(ini: Map<String, String>, patch: GamePatch, enabled: Boolean): Map<String, String> {
        require(patch in patches)
        val selected = selected(ini).toMutableSet()
        val updates = linkedMapOf<String, String>()
        if (enabled) selected.add(patch.name) else selected.remove(patch.name)
        patch.resolution?.let { value ->
            selected.removeAll { it.startsWith("Resolution Patch") && it != patch.name }
            if (enabled) selected.remove(TEXTURE)
            updates["render_resolution"] = if (enabled) value else "native"
        }
        patch.fps?.let { value ->
            selected.removeAll { it.startsWith("Frame Rate Patch") && it != patch.name }
            updates["engine_fps"] = if (enabled) value else "60"
        }
        if (patch.name == TEXTURE && enabled) {
            selected.removeAll { it.startsWith("Resolution Patch") }
            updates["render_resolution"] = "native"
        }
        // 页面保存是下一次启动的新请求，不能被上一次未确认重启的回滚覆盖。
        updates["restart_unconfirmed"] = "0"
        updates[selectionKey] = patches.filter { it.name in selected }.joinToString(";") { it.name }
        return updates
    }

    /**
     * 同时切换一组补丁（设置页「跳过动画」快捷开关一次控制两个 Skip 补丁）。
     * 写入契约与单补丁 [toggle] 完全一致；这组补丁不带分辨率/帧率副作用，无特殊联动。
     */
    fun toggleAll(ini: Map<String, String>, group: List<GamePatch>, enabled: Boolean): Map<String, String> {
        require(group.isNotEmpty() && group.all { it in patches })
        val selected = selected(ini).toMutableSet()
        group.forEach { if (enabled) selected.add(it.name) else selected.remove(it.name) }
        return mapOf(
            "restart_unconfirmed" to "0",
            selectionKey to patches.filter { it.name in selected }.joinToString(";") { it.name },
        )
    }

    companion object {
        private const val TEXTURE = "Bug Fix - Texture Corruption Fix"

        /** 「跳过动画」快捷开关绑定的补丁名（四个目录版本均有提供）。 */
        private val SKIP_VIDEO_PATCHES = setOf("Skip Intro", "Skip Any Video With X Button")
        private val versions = mapOf(
            "CUSA01623" to setOf("01.02"), "CUSA01715" to setOf("01.02"),
            "CUSA01720" to setOf("01.02"), "CUSA01740" to setOf("01.00", "01.02"),
        )

        // The caller opens the asset, so a missing one surfaces as an IOException to Java callers.
        @JvmStatic
        @Throws(IOException::class)
        fun load(game: File?, update: File?, open: (String) -> InputStream): PatchCatalog =
            load(game, update, null, null, false, open)

        /**
         * 供 Java 调用方使用（GameActivity）：asset 在这里打开，受检 IOException 由 load 自己声明。
         * Java 的 lambda（Function1.invoke 没有 throws）不允许穿出受检异常，所以不能把
         * AssetManager.open 直接交给调用方包成 lambda。
         */
        @JvmStatic
        @Throws(IOException::class)
        fun load(game: File?, update: File?, assets: AssetManager): PatchCatalog =
            load(game, update, null, null, false) { assets.open(it) }

        /**
         * 带模组输入的加载：启用模组（总开关开启）携带的 patches 目录下的 xml 解析为 `source=MOD`
         * 的补丁，与内置目录合并；同名补丁由模组项替换内置项。总开关关闭时不并入。只读模组文件，不修改它们。
         */
        @JvmStatic
        @Throws(IOException::class)
        fun load(game: File?, update: File?, modsDir: File?, modsConfig: File?, modsEnabled: Boolean,
                 open: (String) -> InputStream): PatchCatalog {
            if (game == null) throw PatchCatalogException(CatalogProblem.NO_GAME)
            if (!File(game, "eboot.bin").isFile) throw PatchCatalogException(CatalogProblem.INVALID_GAME)
            val base = GameSfo.read(File(game, "sce_sys/param.sfo")) ?: throw PatchCatalogException(CatalogProblem.INVALID_GAME)
            val effective = if (update == null) base else {
                val values = GameSfo.read(File(update, "sce_sys/param.sfo"))
                if (!File(update, "eboot.bin").isFile || values == null || values["TITLE_ID"] != base["TITLE_ID"] ||
                    game.canonicalFile == update.canonicalFile || game.canonicalFile.toPath().startsWith(update.canonicalFile.toPath()) ||
                    update.canonicalFile.toPath().startsWith(game.canonicalFile.toPath())) throw PatchCatalogException(CatalogProblem.INVALID_UPDATE)
                values
            }
            val title = effective["TITLE_ID"] ?: throw PatchCatalogException(CatalogProblem.INVALID_GAME)
            val version = effective["APP_VER"] ?: throw PatchCatalogException(CatalogProblem.INVALID_GAME)
            if (title !in versions) throw PatchCatalogException(CatalogProblem.UNSUPPORTED)
            if (version !in versions.getValue(title)) throw PatchCatalogException(CatalogProblem.VERSION)
            val catalog = open("God_of_War_III_Remastered_$title.xml").use { parse(it, title, version) }
            if (!modsEnabled || modsDir == null || !modsDir.isDirectory) return catalog
            val modPatches = ArrayList<GamePatch>()
            for (file in ModFileResolver.enabledPatchFiles(modsDir, modsConfig, true, update ?: game)) {
                try {
                    file.inputStream().use { modPatches.addAll(parseModPatches(it, title, version)) }
                } catch (error: Exception) {
                    // 损坏的模组补丁跳过，不阻断内置目录。
                }
            }
            if (modPatches.isEmpty()) return catalog
            return catalog.copy(patches = mergeModPatches(catalog.patches, modPatches))
        }

        /** 内置目录与模组补丁合并：后置的模组项按同名替换内置项；顺序保持内置在前、模组按加载顺序追加。 */
        fun mergeModPatches(builtin: List<GamePatch>, mod: List<GamePatch>): List<GamePatch> {
            val byName = LinkedHashMap<String, GamePatch>()
            for (patch in builtin) byName[patch.name] = patch
            for (patch in mod) byName[patch.name] = patch
            return byName.values.toList()
        }

        private fun readLines(meta: Element): List<PatchLine> {
            val nodes = meta.getElementsByTagName("Line")
            return (0 until nodes.length).map { index ->
                val line = nodes.item(index) as Element
                PatchLine(
                    line.getAttribute("Type"),
                    line.getAttribute("Address"),
                    line.getAttribute("Value"),
                    line.getAttribute("At"),
                    line.getAttribute("Target"),
                    line.getAttribute("Pattern"),
                )
            }
        }

        private fun readTargets(meta: Element): List<PatchTarget> {
            val nodes = meta.getElementsByTagName("Target")
            return (0 until nodes.length).map { index ->
                val target = nodes.item(index) as Element
                PatchTarget(
                    target.getAttribute("Name"),
                    target.getAttribute("Line"),
                    target.getAttribute("At"),
                    target.hasAttribute("Ref"),
                    target.getAttribute("Tail"),
                    target.getAttribute("Pattern"),
                )
            }
        }

        /** 仅解析 APK 内随运行时一起打包的目录，不读取玩家的外部 XML。 */
        fun parse(input: InputStream, title: String, version: String): PatchCatalog {
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input).documentElement
            val ids = root.getElementsByTagName("ID")
            require(ids.length == 1 && ids.item(0).textContent.trim() == title)
            val metas = root.getElementsByTagName("Metadata")
            val patches = (0 until metas.length).map { metas.item(it) as Element }
                .filter { it.getAttribute("AppVer") == version && it.getAttribute("AppElf") == "eboot.bin" }
                .map { meta ->
                    val always = meta.getAttribute("AlwaysOn").ifEmpty {
                        (meta.getAttribute("Name") == "Bug Fix - Startup Black Screen").toString()
                    }.toBoolean()
                    GamePatch(meta.getAttribute("Name"), meta.getAttribute("Note"), meta.getAttribute("Author"),
                        meta.getAttribute("Validation"), meta.getAttribute("isEnabled").toBoolean() || always,
                        meta.getAttribute("AppVer"), meta.getAttribute("AppElf").ifEmpty { "eboot.bin" },
                        readLines(meta), readTargets(meta))
                }
            require(patches.isNotEmpty() && patches.map { it.name }.distinct().size == patches.size)
            return PatchCatalog(title, version, patches)
        }

        /**
         * 解析一个模组补丁文件（设备侧 patches 目录下的 xml）：根为 `<Patch>`，内含多个 `<Metadata>`；
         * `<TitleID><ID>...</ID></TitleID>` 声明适用 Title（缺失时不限制）。只返回当前 title/version
         * 的补丁，标记 `source=MOD`。默认不启用（与第三方补丁一致，玩家在补丁页开关）。
         */
        private fun parseModPatches(input: InputStream, title: String, version: String): List<GamePatch> {
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input).documentElement
            val titles = root.getElementsByTagName("TitleID")
            val allowed = if (titles.length == 0) null else {
                val ids = (titles.item(0) as Element).getElementsByTagName("ID")
                (0 until ids.length).map { ids.item(it).textContent.trim() }.toSet()
            }
            val metas = root.getElementsByTagName("Metadata")
            return (0 until metas.length).map { metas.item(it) as Element }
                .filter { it.getAttribute("AppVer") == version && it.getAttribute("AppElf") == "eboot.bin" }
                .filter { allowed == null || title in allowed }
                .map { meta ->
                    GamePatch(meta.getAttribute("Name"), meta.getAttribute("Note"), meta.getAttribute("Author"),
                        meta.getAttribute("Validation"), meta.getAttribute("isEnabled").toBoolean(),
                        meta.getAttribute("AppVer"), meta.getAttribute("AppElf").ifEmpty { "eboot.bin" },
                        readLines(meta), readTargets(meta), PatchSource.MOD)
                }
        }
    }
}
