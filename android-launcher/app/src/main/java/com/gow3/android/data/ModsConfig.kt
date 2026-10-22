package com.gow3.android.data

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** `mods.json`（{order, disabled}）与 `mods_presets.json`（按名的 {order, disabled}）的读写。 */
object ModsConfig {

    private fun strings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val items = array
        return (0 until items.length()).map { items.getString(it) }
    }

    /** (order, disabled)；文件缺失或损坏时按全部可用处理（空 order/disabled）。 */
    fun read(file: File): Pair<List<String>, List<String>> {
        if (!file.isFile) return emptyList<String>() to emptyList()
        return try {
            val json = JSONObject(file.readText())
            strings(json.optJSONArray("order")) to strings(json.optJSONArray("disabled"))
        } catch (error: Exception) {
            emptyList<String>() to emptyList()
        }
    }

    fun write(file: File, order: List<String>, disabled: List<String>) {
        file.parentFile?.mkdirs()
        val json = JSONObject()
            .put("order", JSONArray(order))
            .put("disabled", JSONArray(disabled))
        file.writeText(json.toString(2) + "\n")
    }

    /** 按名 → (order, disabled)。 */
    fun readPresets(file: File): LinkedHashMap<String, Pair<List<String>, List<String>>> {
        val result = LinkedHashMap<String, Pair<List<String>, List<String>>>()
        if (!file.isFile) return result
        try {
            val root = JSONObject(file.readText())
            val names = root.names()
            for (i in 0 until (names?.length() ?: 0)) {
                val name = names.getString(i)
                val preset = root.optJSONObject(name) ?: continue
                result[name] = strings(preset.optJSONArray("order")) to strings(preset.optJSONArray("disabled"))
            }
        } catch (error: Exception) {
            // 损坏的预设文件视为空。
        }
        return result
    }

    fun writePresets(file: File, presets: Map<String, Pair<List<String>, List<String>>>) {
        file.parentFile?.mkdirs()
        val root = JSONObject()
        for ((name, pair) in presets) {
            root.put(name, JSONObject()
                .put("order", JSONArray(pair.first))
                .put("disabled", JSONArray(pair.second)))
        }
        file.writeText(root.toString(2) + "\n")
    }
}
