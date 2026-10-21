package com.gow3.android.data

import com.gow3.android.data.model.GameSfo


import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * The game preparation the Bionic runtime needs, done on the device.
 *
 * The glibc build ran scripts/prepare.py, link_libc.py, link_modules.py, content_profile.py and
 * launch_env.py on the host (or in the rootfs), so a phone build needed a machine with Python.
 * Here the launcher produces the same inputs itself: the patch XML is parsed with the platform DOM
 * for its own UI anyway, and the binary half - the PS4 SELF, the signature search and the G3PATCH2
 * blob - is in libgow3prepare (app/src/main/cpp/gow3prepare, a port of scripts/patches.py whose
 * output is checked byte for byte against the Python by tools/migration/run_prepare_golden.sh).
 *
 * What lands in <files>/gow3port/data: patches.bin, content.bin, patch_status.json and launch.env.
 * run-bionic.sh consumes them and refuses to start when launch.env's game identity does not match
 * the selected folder.
 */
object Gow3Prepare {
    private const val TAG = "God of War III"
    private const val BASE_DMEM_MB = 7104 // the same base run-gow3.sh passes on Android

    /** link_modules.py's DEFAULT_MODULES. libc.prx first: it becomes module id 2. */
    private val DEFAULT_LINKED_MODULES = listOf("libc.prx", "libSceFios2.prx")

    @Volatile private var loaded = false

    private fun load(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("gow3prepare")
            loaded = true
            true
        } catch (error: UnsatisfiedLinkError) {
            // A debug build can be assembled without the NDK, so the library may be absent. Say so
            // instead of failing later with a confusing message from a launch.
            Log.w(TAG, "libgow3prepare is not in this build; patches cannot be compiled", error)
            false
        }
    }

    private external fun nativeCompile(
        ebootPath: String,
        patchInput: String,
        outDir: String,
        appVersion: String,
        selected: Array<String>,
        baseDmemMb: Int,
        engineFps: Int,
        deviceMemMb: Long,
    ): String

    private external fun nativeContentProfile(outDir: String, sku: Int, userParams: IntArray): Boolean

    /**
     * Builds boot-linked.bin (G3PROBE5) from the game's own files: the eboot's load segments
     * flattened, then libc.prx and libSceFios2.prx appended and their imports bound to each other's
     * exports. A port of scripts/prepare.py and scripts/link_modules.py, checked byte for byte
     * against them by tools/migration/run_link_golden.sh.
     *
     * [moduleFiles] is in link order and must start with libc.prx: it becomes module id 2, which
     * every later module's TLS relocations refer to.
     */
    private external fun nativeBootLinked(gameDir: String, outDir: String, moduleFiles: Array<String>): Boolean

    /** 成功结果的 gameView 交给调用方持有，必须在对应进程退出后关闭。 */
    data class Result(
        val ok: Boolean,
        val reason: String,
        val applied: List<String>,
        val skipped: Map<String, String>,
        val dmemMb: Int,
        val vblankHz: Int,
        val renderResolution: String,
        val writeCount: Int,
        val message: String,
        val gameView: GameUpdateView? = null,
    )

    /**
     * Compiles the selected patches and writes content.bin beside them.
     *
     * [catalog] is the launcher's own patch list: it knows the title, the version and which patches
     * the player enabled, so this does not re-derive any of that. [ini] carries the graphics
     * options, [updateDir] the optional upgrade overlay (its param.sfo decides the effective
     * version, exactly as the catalogue does).
     */
    // Declared for the Java caller (GameActivity): the catalogue's problems and the native
    // library's absence are both reported by returning a Result with ok=false, but the Kotlin
    // signature still has to be visible to javac.
    @JvmStatic
    @Throws(Exception::class)
    fun prepare(
        context: Context,
        gameDir: File,
        updateDir: File?,
        catalog: PatchCatalog,
        ini: Map<String, String>,
    ): Result {
        val parent = File(File(context.filesDir, "gow3port"), "data")
        val view = GameUpdateView.create(gameDir, updateDir, parent)
        try {
            val identity = GameSfo.read(File(view.directory, "sce_sys/param.sfo"))
            if (identity?.get("TITLE_ID") != catalog.titleId || identity?.get("APP_VER") != catalog.appVersion) {
                throw java.io.IOException("The effective game identity changed; select the game/update again")
            }
            // 补丁、链接模块、SFO 与运行时 app0 必须来自同一份有效视图。
            val result = prepareEffective(context, view.directory, catalog, ini)
            if (result.ok) return result.copy(gameView = view)
            view.close()
            return result
        } catch (error: Exception) {
            try {
                view.close()
            } catch (cleanup: java.io.IOException) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    private fun prepareEffective(
        context: Context,
        gameDir: File,
        catalog: PatchCatalog,
        ini: Map<String, String>,
    ): Result {
        val outDir = File(File(context.filesDir, "gow3port"), "data")
        if (!outDir.isDirectory && !outDir.mkdirs()) {
            return Result(false, "prepare-failed", emptyList(), emptyMap(), BASE_DMEM_MB, 60, "native", 0,
                "cannot create ${outDir.path}")
        }
        val eboot = File(gameDir, "eboot.bin")
        if (!eboot.isFile) {
            return Result(false, "prepare-failed", emptyList(), emptyMap(), BASE_DMEM_MB, 60, "native", 0,
                "no eboot.bin in ${gameDir.path}")
        }
        if (!load()) {
            return Result(false, "prepare-failed", emptyList(), emptyMap(), BASE_DMEM_MB, 60, "native", 0,
                "libgow3prepare is missing from this build")
        }

        val selected = catalog.selected(ini).toList()
        val input = buildString {
            append(catalog.toNativeInput())
        }
        val engineFps = ini["engine_fps"]?.toIntOrNull() ?: 60
        val status = try {
            JSONObject(
                nativeCompile(
                    eboot.absolutePath,
                    input,
                    outDir.absolutePath,
                    catalog.appVersion,
                    selected.toTypedArray(),
                    BASE_DMEM_MB,
                    engineFps,
                    deviceMemoryMb(context),
                )
            )
        } catch (error: Exception) {
            Log.e(TAG, "patch compilation failed", error)
            return Result(false, "prepare-failed", emptyList(), emptyMap(), BASE_DMEM_MB, 60, "native", 0,
                error.message ?: error.javaClass.simpleName)
        }

        val ebootSha = sha256(eboot)
        Log.i(TAG, "effective game: ${catalog.titleId} ${catalog.appVersion}; eboot=$ebootSha")
        // The native half does not know the identity (that is the launcher's reading of param.sfo);
        // fill it in so the report matches what scripts/patches.py writes.
        status.put("title_id", catalog.titleId)
        status.put("eboot_sha256", ebootSha)
        File(outDir, "patch_status.json").writeText(status.toString(2) + "\n")
        val reason = status.optString("reason", "prepare-failed")
        val effective = status.optJSONObject("effective")
        val dmem = effective?.optInt("dmem_mb", BASE_DMEM_MB) ?: BASE_DMEM_MB
        val vblank = effective?.optInt("vblank_hz", 60) ?: 60
        val resolution = effective?.optString("render_resolution") ?: "native"
        val applied = status.optJSONArray("applied")?.let { array ->
            (0 until array.length()).map { array.optString(it) }
        } ?: emptyList()
        val skipped = status.optJSONObject("skipped")?.let { obj ->
            obj.keys().asSequence().associateWith { obj.optString(it) }
        } ?: emptyMap()
        val writes = status.optInt("write_count", 0)

        // G3CONT01: the SKU is a probe setting (full=3) and the four USER_DEFINED_PARAM values come
        // from the game's param.sfo - the same inputs scripts/content_profile.py uses. The upgrade
        // overlay's param.sfo is the effective one, which is what the catalogue read too.
        val sfo = GameSfo.read(File(gameDir, "sce_sys/param.sfo")) ?: emptyMap()
        val userParams = IntArray(4) { index ->
            sfo["USER_DEFINED_PARAM_${index + 1}"]?.toLongOrNull()?.let {
                if (it in 0..0xffffffffL) it.toInt() else 0
            } ?: 0
        }
        if (!nativeContentProfile(outDir.absolutePath, 3, userParams)) {
            return Result(false, "prepare-failed", applied, skipped, dmem, vblank, resolution, writes,
                "cannot write content.bin into ${outDir.path}")
        }
        // The boot image itself: what probe.c actually executes. Without it run-bionic.sh has
        // nothing to start, which is why this used to need scripts/prepare.py on a host.
        // The list is link_modules.py's DEFAULT_MODULES unless linked_modules overrides it, the
        // same way --modules overrides it there. libc.prx has to come first: it becomes module 2.
        val modules = (ini["linked_modules"]?.split(' ', ',', ';')?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: DEFAULT_LINKED_MODULES)
        try {
            if (!nativeBootLinked(gameDir.absolutePath, outDir.absolutePath, modules.toTypedArray())) {
                throw java.io.IOException("Cannot link the effective game image")
            }
        } catch (error: Exception) {
            Log.e(TAG, "boot image linking failed", error)
            return Result(false, "prepare-failed", emptyList(), emptyMap(), BASE_DMEM_MB, 60, "native", 0,
                error.message ?: error.javaClass.simpleName)
        }
        // run-bionic.sh reads these; the identity in it is what it checks the folder against.
        val launchEnv = buildString {
            appendLine("export GOW3_DEFERRED_READBACK='1'")
            appendLine("export GOW3_STALE_READBACK='1'")
            appendLine("export GOW3_ASYNC_SHADERS='1'")
            appendLine("export GOW3_READBACK_LINEAR='1'")
            appendLine("export GOW3_GAME_TITLE='${catalog.titleId}'")
            appendLine("export GOW3_GAME_VERSION='${catalog.appVersion}'")
            appendLine("export GOW3_EBOOT_SHA256='$ebootSha'")
            appendLine("export GOW3_PATCH_STATUS='$reason'")
            appendLine("export GOW3_PATCHES='${applied.joinToString(";")}'")
            appendLine("export GOW3_PATCHES_ONLY='1'")
            appendLine("export GOW3_ENGINE_FPS='$engineFps'")
            appendLine("export GOW3_DMEM_MB='$dmem'")
            appendLine("export GOW3_VBLANK_HZ='$vblank'")
            appendLine("export GOW3_PATCH_INPUTS='{}'")
        }
        File(outDir, "launch.env").writeText(launchEnv)

        val ok = writes > 0 || reason == "no-patches" || reason == "no-applicable-patches"
        val message = when (reason) {
            "compiled", "no-patches", "no-applicable-patches" -> "$applied"
            else -> reason
        }
        return Result(ok, reason, applied, skipped, dmem, vblank, resolution, writes, message)
    }

    private fun deviceMemoryMb(context: Context): Long {
        val info = android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)
            ?.getMemoryInfo(info)
        return info.totalMem / (1024 * 1024)
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
