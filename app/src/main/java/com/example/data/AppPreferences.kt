package com.example.data

import android.content.Context
import android.os.Environment
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.FolderConfig
import com.example.model.MoveLogEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "photo_vault_settings")

class AppPreferences(private val context: Context) {

    companion object {
        private val KEY_SERVICE_ENABLED = booleanPreferencesKey("service_enabled")
        private val KEY_TARGET_DIR = stringPreferencesKey("target_directory")
        private val KEY_MONITORED_FOLDERS = stringSetPreferencesKey("monitored_folders")
        private val KEY_CUSTOM_FOLDERS = stringSetPreferencesKey("custom_folders")
        private val KEY_HIDE_NO_MEDIA = booleanPreferencesKey("hide_no_media")
        private val KEY_NOTIFY_ON_MOVE = booleanPreferencesKey("notify_on_move")
        private val KEY_RECENT_LOGS = stringPreferencesKey("recent_logs")
        private val KEY_TOTAL_MOVED = intPreferencesKey("total_moved")

        val DEFAULT_SOURCE_FOLDERS = listOf(
            FolderConfig("DCIM/Camera", "Camera (DCIM/Camera)", isEnabled = true, isDefault = true),
            FolderConfig("Pictures/Screenshots", "Screenshots (Pictures/Screenshots)", isEnabled = true, isDefault = true),
            FolderConfig("DCIM/Screenshots", "Screenshots (DCIM/Screenshots)", isEnabled = true, isDefault = true),
            FolderConfig("Download", "Downloads (Download)", isEnabled = false, isDefault = true),
            FolderConfig("Android/media/com.whatsapp/WhatsApp/Media", "WhatsApp Media", isEnabled = false, isDefault = true),
            FolderConfig("Pictures", "Pictures (Root)", isEnabled = false, isDefault = true)
        )

        fun getDefaultTargetDirectory(): String {
            val base = Environment.getExternalStorageDirectory()?.absolutePath
                ?: "/storage/emulated/0"
            return "$base/_PhotoVault_Local"
        }
    }

    val isServiceEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SERVICE_ENABLED] ?: false
    }

    val targetDirectoryFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_TARGET_DIR] ?: getDefaultTargetDirectory()
    }

    val monitoredFoldersFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_MONITORED_FOLDERS] ?: setOf("DCIM/Camera", "Pictures/Screenshots", "DCIM/Screenshots")
    }

    val customFoldersFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_CUSTOM_FOLDERS] ?: emptySet()
    }

    val hideFromGalleryFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_HIDE_NO_MEDIA] ?: true
    }

    val notifyOnMoveFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_NOTIFY_ON_MOVE] ?: true
    }

    val totalMovedCountFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_TOTAL_MOVED] ?: 0
    }

    val recentLogsFlow: Flow<List<MoveLogEntry>> = context.dataStore.data.map { prefs ->
        val json = prefs[KEY_RECENT_LOGS] ?: "[]"
        deserializeLogs(json)
    }

    suspend fun setServiceEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SERVICE_ENABLED] = enabled
        }
    }

    suspend fun setTargetDirectory(path: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_TARGET_DIR] = path.trimEnd('/')
        }
    }

    suspend fun toggleMonitoredFolder(relativePath: String, isEnabled: Boolean) {
        context.dataStore.edit { prefs ->
            val current = (prefs[KEY_MONITORED_FOLDERS] ?: setOf("DCIM/Camera", "Pictures/Screenshots", "DCIM/Screenshots")).toMutableSet()
            if (isEnabled) {
                current.add(relativePath)
            } else {
                current.remove(relativePath)
            }
            prefs[KEY_MONITORED_FOLDERS] = current
        }
    }

    suspend fun addCustomFolder(folderPath: String) {
        val cleanPath = folderPath.trim().trim('/')
        if (cleanPath.isEmpty()) return
        context.dataStore.edit { prefs ->
            val currentCustom = (prefs[KEY_CUSTOM_FOLDERS] ?: emptySet()).toMutableSet()
            currentCustom.add(cleanPath)
            prefs[KEY_CUSTOM_FOLDERS] = currentCustom

            val currentMonitored = (prefs[KEY_MONITORED_FOLDERS] ?: setOf("DCIM/Camera", "Pictures/Screenshots")).toMutableSet()
            currentMonitored.add(cleanPath)
            prefs[KEY_MONITORED_FOLDERS] = currentMonitored
        }
    }

    suspend fun removeCustomFolder(folderPath: String) {
        context.dataStore.edit { prefs ->
            val currentCustom = (prefs[KEY_CUSTOM_FOLDERS] ?: emptySet()).toMutableSet()
            currentCustom.remove(folderPath)
            prefs[KEY_CUSTOM_FOLDERS] = currentCustom

            val currentMonitored = (prefs[KEY_MONITORED_FOLDERS] ?: emptySet()).toMutableSet()
            currentMonitored.remove(folderPath)
            prefs[KEY_MONITORED_FOLDERS] = currentMonitored
        }
    }

    suspend fun setHideFromGallery(hide: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_HIDE_NO_MEDIA] = hide
        }
    }

    suspend fun setNotifyOnMove(notify: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_NOTIFY_ON_MOVE] = notify
        }
    }

    suspend fun addLog(entry: MoveLogEntry) {
        context.dataStore.edit { prefs ->
            val currentJson = prefs[KEY_RECENT_LOGS] ?: "[]"
            val currentList = deserializeLogs(currentJson).toMutableList()
            currentList.add(0, entry)
            // Keep the last 50 logs for history
            val trimmedList = currentList.take(50)
            prefs[KEY_RECENT_LOGS] = serializeLogs(trimmedList)

            if (entry.success) {
                val currentTotal = prefs[KEY_TOTAL_MOVED] ?: 0
                prefs[KEY_TOTAL_MOVED] = currentTotal + 1
            }
        }
    }

    suspend fun clearLogs() {
        context.dataStore.edit { prefs ->
            prefs[KEY_RECENT_LOGS] = "[]"
        }
    }

    private fun serializeLogs(list: List<MoveLogEntry>): String {
        val jsonArray = JSONArray()
        list.forEach { item ->
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("timestamp", item.timestamp)
            obj.put("fileName", item.fileName)
            obj.put("sourcePath", item.sourcePath)
            obj.put("destinationPath", item.destinationPath)
            obj.put("fileSizeBytes", item.fileSizeBytes)
            obj.put("success", item.success)
            obj.put("errorMessage", item.errorMessage ?: "")
            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }

    private fun deserializeLogs(json: String): List<MoveLogEntry> {
        return try {
            val array = JSONArray(json)
            val list = mutableListOf<MoveLogEntry>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val err = obj.optString("errorMessage")
                list.add(
                    MoveLogEntry(
                        id = obj.optString("id", System.currentTimeMillis().toString()),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        fileName = obj.optString("fileName", ""),
                        sourcePath = obj.optString("sourcePath", ""),
                        destinationPath = obj.optString("destinationPath", ""),
                        fileSizeBytes = obj.optLong("fileSizeBytes", 0L),
                        success = obj.optBoolean("success", true),
                        errorMessage = if (err.isNullOrBlank()) null else err
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }
}
