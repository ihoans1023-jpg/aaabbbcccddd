package com.example.precisionautoclicker.data

import android.content.Context
import com.example.precisionautoclicker.model.ActionNode
import com.example.precisionautoclicker.model.ExecutionConfig
import com.example.precisionautoclicker.model.SavedPreset
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PresetRepository(context: Context) {

    private val prefs = context.getSharedPreferences("precision_autoclicker_presets", Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        private const val KEY_PRESETS = "saved_presets_json"
    }

    fun getAllPresets(): List<SavedPreset> {
        val raw = prefs.getString(KEY_PRESETS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<SavedPreset>>(raw)
        }.getOrDefault(emptyList()).sortedByDescending { it.updatedAtMs }
    }

    fun savePreset(name: String, nodes: List<ActionNode>, config: ExecutionConfig) {
        val cleanName = name.trim().ifEmpty { "프리셋 ${System.currentTimeMillis() % 10000}" }
        val current = getAllPresets().filterNot { it.name == cleanName }.toMutableList()
        current.add(
            0,
            SavedPreset(
                name = cleanName,
                nodes = nodes,
                config = config,
                updatedAtMs = System.currentTimeMillis()
            )
        )
        prefs.edit().putString(KEY_PRESETS, json.encodeToString(current)).apply()
    }

    fun deletePreset(name: String) {
        val updated = getAllPresets().filterNot { it.name == name }
        prefs.edit().putString(KEY_PRESETS, json.encodeToString(updated)).apply()
    }
}
