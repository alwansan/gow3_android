package com.gow3.android.feature.mods

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import com.gow3.android.ui.components.ActionButton
import com.gow3.android.ui.components.EmptyState
import com.gow3.android.ui.components.ScreenColumn

import com.gow3.android.core.settings.GamePrefs
import com.gow3.android.core.settings.Gow3Settings
import com.gow3.android.data.CatalogProblem
import com.gow3.android.data.GamePatch
import com.gow3.android.data.Gow3Data
import com.gow3.android.data.ModFileResolver
import com.gow3.android.data.ModImporter
import com.gow3.android.data.ModsConfig
import com.gow3.android.data.PatchCatalog
import com.gow3.android.data.PatchCatalogException
import com.gow3.android.data.PatchGroup
import com.gow3.android.ui.components.LauncherColors

import android.content.SharedPreferences
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gow3.android.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Mod & Patch 页：Load mods 总开关 + 文件模组管理（启用/排序/冲突/详情/预设/刷新），
 * 下方保留内置补丁目录区块。模组状态写 mods.json，下次启动生效；总开关同时控制模组
 * 文件替换与模组内补丁（写入 gow3.ini 的 load_mods）。
 */
@Composable
internal fun ModsScreen() {
    val context = LocalContext.current
    val prefs = remember(context) { GamePrefs(context) }
    var revision by remember { mutableIntStateOf(0) }
    var catalog by remember { mutableStateOf<PatchCatalog?>(null) }
    var ini by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<CatalogProblem?>(null) }
    var notice by remember { mutableStateOf<Int?>(null) }

    // 模组状态：可用模组（字母序）、加载顺序、禁用集合、预设、展开的详情、预设名输入。
    val modsDir = remember(context) { File(Gow3Data.root(context), "mods") }
    val modsConfig = remember(context) { File(Gow3Data.root(context), "mods.json") }
    val presetsFile = remember(context) { File(Gow3Data.root(context), "mods_presets.json") }
    var available by remember { mutableStateOf(emptyList<String>()) }
    var order by remember { mutableStateOf(emptyList<String>()) }
    var disabled by remember { mutableStateOf(setOf<String>()) }
    var presets by remember { mutableStateOf(emptyMap<String, Pair<List<String>, List<String>>>()) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var presetName by remember { mutableStateOf("") }
    // 子页：模组与补丁分栏，默认显示补丁。
    var subTab by remember { mutableStateOf(ModSubTab.PATCHES) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    fun loadIni(): Map<String, String> = Gow3Settings.read(Gow3Data.config(context))
    val loadMods = ini["load_mods"] != "0"

    fun load(): PatchCatalog = PatchCatalog.load(
        prefs.gameDir()?.let(::File),
        prefs.updateDir()?.takeIf { it.isNotBlank() }?.let(::File),
        modsDir,
        modsConfig,
        loadMods,
        context.assets::open,
    )

    DisposableEffect(prefs, lifecycle) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "game_dir" || key == "update_dir") revision++
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        prefs.raw.registerOnSharedPreferenceChangeListener(listener)
        lifecycle.addObserver(observer)
        onDispose {
            prefs.raw.unregisterOnSharedPreferenceChangeListener(listener)
            lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(revision) {
        loading = true
        catalog = null
        problem = null
        notice = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val game = prefs.gameDir()?.let(::File)
                val loaded = load() to loadIni()
                // 模组目录与状态只在游戏目录可用时读取（dataRoots 需要有效游戏）。
                val mods = if (game != null) ModFileResolver.discover(modsDir, game) else emptyList()
                val (savedOrder, savedDisabled) = ModsConfig.read(modsConfig)
                val mergedOrder = (savedOrder + mods).filter { it in mods }.distinct()
                Triple(loaded.first, loaded.second, Triple(mods, mergedOrder, savedDisabled.filter { it in mods }))
            }
        }
        result.onSuccess { (loadedCatalog, loadedIni, modsState) ->
            catalog = loadedCatalog
            ini = loadedIni
            available = modsState.first
            order = modsState.second
            disabled = modsState.third.toSet()
            presets = ModsConfig.readPresets(presetsFile)
        }.onFailure { problem = (it as? PatchCatalogException)?.problem ?: CatalogProblem.READ_FAILED }
        loading = false
    }

    fun saveMods(newOrder: List<String>, newDisabled: Set<String>) {
        saving = true
        notice = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val all = available
                    val mergedOrder = (newOrder + all).filter { it in all }.distinct()
                    ModsConfig.write(modsConfig, mergedOrder, newDisabled.filter { it in all })
                    revision++
                }
            }
            result.onFailure { notice = R.string.settings_save_failed }
            saving = false
        }
    }

    fun toggleMod(name: String, enabled: Boolean) {
        saveMods(order, if (enabled) disabled - name else disabled + name)
    }

    fun moveMod(name: String, up: Boolean) {
        val index = order.indexOf(name)
        if (index < 0) return
        val target = if (up) index - 1 else index + 1
        if (target < 0 || target >= order.size) return
        val next = order.toMutableList()
        next[index] = next[target]
        next[target] = name
        saveMods(next, disabled)
    }

    fun toggleMasterSwitch(enabled: Boolean) {
        saving = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { Gow3Settings.update(Gow3Data.config(context), mapOf("load_mods" to if (enabled) "1" else "0")) }
            }
            result.onSuccess { revision++ }.onFailure { notice = R.string.settings_save_failed }
            saving = false
        }
    }

    fun savePreset() {
        val name = presetName.trim()
        if (name.isEmpty()) return
        saving = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ModsConfig.writePresets(presetsFile, presets + (name to (order to disabled.toList())))
                    presetName = ""
                    revision++
                }
            }
            result.onFailure { notice = R.string.settings_save_failed }
            saving = false
        }
    }

    fun loadPreset(name: String) {
        val preset = presets[name] ?: return
        saving = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val json = org.json.JSONObject()
                        .put("order", org.json.JSONArray(preset.first))
                        .put("disabled", org.json.JSONArray(preset.second))
                    val (appliedOrder, appliedDisabled) = ModFileResolver.applyPreset(json, available)
                    ModsConfig.write(modsConfig, appliedOrder, appliedDisabled)
                    revision++
                }
            }
            result.onFailure { notice = R.string.settings_save_failed }
            saving = false
        }
    }

    fun deletePreset(name: String) {
        saving = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ModsConfig.writePresets(presetsFile, presets - name)
                    revision++
                }
            }
            result.onFailure { notice = R.string.settings_save_failed }
            saving = false
        }
    }

    fun toggle(current: PatchCatalog, patch: GamePatch, enabled: Boolean) {
        saving = true
        notice = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // 写入前重新读配置和身份，避免覆盖首页刚保存的值或写到另一游戏。
                    check(load().selectionKey == current.selectionKey)
                    val file = Gow3Data.config(context)
                    check(Gow3Settings.update(file, current.toggle(Gow3Settings.read(file), patch, enabled)))
                    Gow3Settings.read(file)
                }
            }
            result.onSuccess { values ->
                if (catalog?.selectionKey == current.selectionKey) ini = values
                notice = R.string.mods_saved
            }.onFailure { notice = R.string.settings_save_failed }
            saving = false
        }
    }

    // 导入：选中的 zip / 文件夹复制进托管 mods 目录，随后重新发现（zip 由 unpackZips 安全解包）。
    var imported by remember { mutableStateOf<String?>(null) }
    val importZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val name = withContext(Dispatchers.IO) { ModImporter.importZip(context, uri, modsDir) }
                imported = name
                notice = if (name != null) null else R.string.mods_import_failed
                revision++
            }
        }
    }
    val importFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch {
                val name = withContext(Dispatchers.IO) { ModImporter.importFolder(context, uri, modsDir) }
                imported = name
                notice = if (name != null) null else R.string.mods_import_failed
                revision++
            }
        }
    }

    val game = prefs.gameDir()?.let(::File)
    val enabledMods = (order + available).filter { it in available && it !in disabled }.distinct()
    val conflicts = if (game != null) ModFileResolver.conflicts(modsDir, enabledMods, game) else emptyList()
    val conflictFiles = conflicts.map { it.path }.toSet()

    ScreenColumn {
        Text(stringResource(R.string.mods_title), color = LauncherColors.textPrimary, fontFamily = FontFamily.Serif,
            fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.mods_description), color = LauncherColors.textMuted, fontSize = 13.sp, lineHeight = 19.sp)
        notice?.let { Text(stringResource(it), color = LauncherColors.gold, fontSize = 12.sp) }
        if (loading) {
            CircularProgressIndicator(color = LauncherColors.gold)
            Text(stringResource(R.string.mods_loading), color = LauncherColors.textMuted)
        } else if (problem != null) {
            val message = when (problem) {
                CatalogProblem.NO_GAME -> R.string.mods_no_game
                CatalogProblem.INVALID_GAME -> R.string.mods_invalid_game
                CatalogProblem.INVALID_UPDATE -> R.string.mods_invalid_update
                CatalogProblem.UNSUPPORTED -> R.string.mods_unsupported
                CatalogProblem.VERSION -> R.string.mods_version
                else -> R.string.mods_read_failed
            }
            EmptyState(stringResource(R.string.mods_unavailable), stringResource(message))
            ActionButton(stringResource(R.string.mods_retry), primary = false) { revision++ }
        } else {
            catalog?.let { current ->
                ModsSubTabs(subTab, !saving) { subTab = it }
                if (subTab == ModSubTab.PATCHES) {
                    // ---- 补丁：内置目录 + 模组内补丁 ----
                    Surface(color = LauncherColors.navSurface, shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        border = BorderStroke(1.dp, LauncherColors.gold.copy(alpha = 0.35f))) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${current.titleId} · ${current.appVersion}", color = LauncherColors.gold, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.mods_count, current.patches.size, current.selected(ini).size), color = LauncherColors.textPrimary, fontSize = 13.sp)
                            Text(stringResource(R.string.mods_next_launch), color = LauncherColors.textMuted, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                    }
                    for (group in PatchGroup.entries) {
                        val entries = current.patches.filter { it.group == group }
                        if (entries.isNotEmpty()) {
                            val label = when (group) {
                                PatchGroup.RESOLUTION -> R.string.mods_group_resolution
                                PatchGroup.FRAME_RATE -> R.string.mods_group_fps
                                PatchGroup.VIDEO -> R.string.mods_group_video
                                PatchGroup.FIX -> R.string.mods_group_fixes
                                PatchGroup.EFFECT -> R.string.mods_group_effects
                            }
                            Text(stringResource(label), color = LauncherColors.gold, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 14.dp))
                            if (group == PatchGroup.RESOLUTION || group == PatchGroup.FRAME_RATE) {
                                Text(stringResource(R.string.mods_exclusive), color = LauncherColors.textMuted, fontSize = 12.sp)
                            }
                            entries.forEach { patch ->
                                PatchCard(patch, patch.name in current.selected(ini), !saving) { enabled -> toggle(current, patch, enabled) }
                            }
                        }
                    }
                } else {
                    // ---- 模组：总开关 + 导入 + 列表 + 预设 ----
                    Surface(color = LauncherColors.navSurface, shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        border = BorderStroke(1.dp, LauncherColors.gold.copy(alpha = 0.35f))) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(stringResource(R.string.mods_load_title), color = LauncherColors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(stringResource(R.string.mods_load_hint), color = LauncherColors.textMuted, fontSize = 12.sp, lineHeight = 17.sp)
                            }
                            Switch(checked = loadMods, onCheckedChange = ::toggleMasterSwitch, enabled = !saving,
                                colors = SwitchDefaults.colors(checkedTrackColor = LauncherColors.crimson, checkedThumbColor = LauncherColors.gold))
                        }
                    }

                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.mods_files_title), color = LauncherColors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.mods_files_count, enabledMods.size, available.size), color = LauncherColors.textMuted, fontSize = 12.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            TextButton(onClick = { importZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, enabled = !saving) {
                                Text(stringResource(R.string.mods_import_zip), color = LauncherColors.gold, fontSize = 12.sp)
                            }
                            TextButton(onClick = { importFolder.launch(null) }, enabled = !saving) {
                                Text(stringResource(R.string.mods_import_folder), color = LauncherColors.gold, fontSize = 12.sp)
                            }
                            TextButton(onClick = { revision++ }, enabled = !saving) {
                                Text(stringResource(R.string.mods_refresh), color = LauncherColors.textMuted, fontSize = 12.sp)
                            }
                        }
                    }
                    imported?.let { name ->
                        Text(stringResource(R.string.mods_imported_fmt, name), color = LauncherColors.gold, fontSize = 12.sp)
                    }
                    if (available.isEmpty()) {
                        EmptyState(stringResource(R.string.mods_empty_title), stringResource(R.string.mods_files_empty))
                    } else {
                        if (conflictFiles.isNotEmpty()) {
                            Text(stringResource(R.string.mods_conflicts, conflictFiles.size), color = LauncherColors.crimson, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        }
                        Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (name in order) {
                                ModCard(name, name in disabled, !saving, conflictFiles, File(modsDir, name),
                                    onToggle = { toggleMod(name, it) },
                                    onMoveUp = { moveMod(name, up = true) },
                                    onMoveDown = { moveMod(name, up = false) },
                                    onExpand = { expanded = if (name in expanded) expanded - name else expanded + name },
                                    expanded = name in expanded,
                                    info = ModFileResolver.modInfo(File(modsDir, name), game),
                                )
                            }
                        }
                    }

                    // ---- 预设 ----
                    Text(stringResource(R.string.mods_presets_title), color = LauncherColors.textPrimary, fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp))
                    Text(stringResource(R.string.mods_presets_hint), color = LauncherColors.textMuted, fontSize = 12.sp)
                    if (presets.isEmpty()) {
                        Text(stringResource(R.string.mods_presets_empty), color = LauncherColors.textMuted, fontSize = 13.sp)
                    } else {
                        Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for ((name, _) in presets) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(name, color = LauncherColors.textPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { loadPreset(name) }, enabled = !saving) {
                                        Text(stringResource(R.string.mods_presets_load), color = LauncherColors.gold, fontSize = 12.sp)
                                    }
                                    TextButton(onClick = { deletePreset(name) }, enabled = !saving) {
                                        Text(stringResource(R.string.mods_presets_delete), color = LauncherColors.textMuted, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(value = presetName, onValueChange = { presetName = it }, enabled = !saving,
                            singleLine = true, modifier = Modifier.weight(1f),
                            textStyle = androidx.compose.ui.text.TextStyle(color = LauncherColors.textPrimary, fontSize = 13.sp))
                        TextButton(onClick = ::savePreset, enabled = !saving) {
                            Text(stringResource(R.string.mods_presets_save), color = LauncherColors.gold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

private enum class ModSubTab(@androidx.annotation.StringRes val label: Int) {
    MODS(R.string.mods_tab_mods), PATCHES(R.string.mods_tab_patches)
}

/** 模组/补丁子页切换：默认 PATCHES（补丁），模组管理在 MODS 页。 */
@Composable
private fun ModsSubTabs(selected: ModSubTab, enabled: Boolean, onSelect: (ModSubTab) -> Unit) {
    Surface(color = LauncherColors.navSurface, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        border = BorderStroke(1.dp, LauncherColors.gold.copy(alpha = 0.35f))) {
        Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (tab in ModSubTab.entries) {
                val active = tab == selected
                Surface(color = if (active) LauncherColors.crimson else LauncherColors.navSurface, shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f).clickable(enabled = enabled) { onSelect(tab) }) {
                    Text(stringResource(tab.label), color = if (active) LauncherColors.textPrimary else LauncherColors.textMuted,
                        fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun ModCard(
    name: String,
    isDisabled: Boolean,
    enabled: Boolean,
    conflictFiles: Set<String>,
    folder: File,
    onToggle: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onExpand: () -> Unit,
    expanded: Boolean,
    info: ModFileResolver.ModInfo,
) {
    val context = LocalContext.current
    Surface(color = LauncherColors.surface, shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, LauncherColors.gold.copy(alpha = if (isDisabled) 0.2f else 0.65f))) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Switch(checked = !isDisabled, onCheckedChange = onToggle, enabled = enabled,
                    colors = SwitchDefaults.colors(checkedTrackColor = LauncherColors.crimson, checkedThumbColor = LauncherColors.gold))
                Column(Modifier.weight(1f).clickable { onExpand() }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(info.name, color = LauncherColors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (info.author.isNotEmpty() || info.version.isNotEmpty()) {
                        Text(listOfNotNull(info.author.takeIf { it.isNotEmpty() }?.let { "by $it" }, info.version.takeIf { it.isNotEmpty() }?.let { "v$it" }).joinToString("  "),
                            color = LauncherColors.textMuted, fontSize = 11.sp)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = onMoveUp, enabled = enabled) { Text("▲", color = LauncherColors.gold, fontSize = 11.sp) }
                    TextButton(onClick = onMoveDown, enabled = enabled) { Text("▼", color = LauncherColors.gold, fontSize = 11.sp) }
                }
            }
            if (name in conflictFiles) {
                Text(stringResource(R.string.mods_conflict_warning, name), color = LauncherColors.crimson, fontSize = 12.sp)
            }
            if (expanded) {
                if (info.description.isNotEmpty()) {
                    Text(info.description, color = LauncherColors.textMuted, fontSize = 12.sp, lineHeight = 18.sp)
                }
                info.image?.let { image ->
                    val bitmap = remember(image.path) { BitmapFactory.decodeFile(image.path) }
                    if (bitmap != null) {
                        Image(bitmap.asImageBitmap(), contentDescription = info.name, modifier = Modifier.size(width = 268.dp, height = 151.dp),
                            contentScale = ContentScale.Fit)
                    }
                }
                Text(stringResource(R.string.mods_folder, folder.path), color = LauncherColors.textMuted, fontSize = 11.sp)
                TextButton(onClick = { openModFolder(context, folder) }, enabled = enabled) {
                    Text(stringResource(R.string.mods_open_folder), color = LauncherColors.gold, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun PatchCard(patch: GamePatch, checked: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Surface(color = LauncherColors.surface, shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, LauncherColors.gold.copy(alpha = if (checked) 0.65f else 0.2f))) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(patch.displayName, color = LauncherColors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    val status = when (patch.validation) {
                        "android-videoout-verified" -> R.string.mods_verified_device
                        "offline-matched" -> R.string.mods_verified_offline
                        "pending-title-sample" -> R.string.mods_pending_sample
                        else -> R.string.mods_legacy
                    }
                    Text(stringResource(status), color = LauncherColors.textMuted, fontSize = 11.sp)
                }
                Switch(checked = checked, onCheckedChange = onToggle, enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = patch.displayName },
                    colors = SwitchDefaults.colors(checkedTrackColor = LauncherColors.crimson, checkedThumbColor = LauncherColors.gold))
            }
        }
    }
}

/** 打开模组文件夹：经 FileProvider 一次性授予读权限，paths 只映射 gow3data/mods/。 */
private fun openModFolder(context: android.content.Context, folder: File) {
    try {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", folder)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.document-directory")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    } catch (error: Exception) {
        // 设备没有可浏览文件夹的应用时静默忽略。
    }
}
