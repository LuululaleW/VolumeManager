package moe.chensi.volume.data

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class AppPreferencesStore(private val dataStore: DataStore<Preferences>) {
    companion object {
        private const val TAG = "AppPreferencesStore"

        private val key = stringPreferencesKey("apps")

        private val json = Json { ignoreUnknownKeys = true }
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    @Serializable
    private data class SerializedState(
        val values: MutableList<AppPreferences>,
        val indices: MutableMap<String, Int>,
        val systemSliderVisibility: MutableMap<String, Boolean> = mutableMapOf()
    )

    private val lock = Any()
    private var state = SerializedState(mutableListOf(), mutableMapOf())
    private val saveMutex = Mutex()
    private var tracked = false

    fun getSystemSliderVisible(id: String): Boolean {
        return synchronized(lock) { state.systemSliderVisibility[id] ?: true }
    }

    fun setSystemSliderVisible(id: String, value: Boolean) {
        val changed = synchronized(lock) {
            val oldValue = state.systemSliderVisibility[id] ?: true
            if (oldValue == value) {
                return@synchronized false
            }

            val updated = state.systemSliderVisibility.toMutableMap()
            updated[id] = value
            state = state.copy(systemSliderVisibility = updated)
            true
        }

        if (changed) {
            save()
        }
    }

    var systemSliderVisibility: Map<String, Boolean>
        get() = synchronized(lock) { state.systemSliderVisibility.toMap() }
        set(value) {
            val changed = synchronized(lock) {
                if (state.systemSliderVisibility == value) {
                    return@synchronized false
                }

                state = state.copy(systemSliderVisibility = value.toMutableMap())
                true
            }

            if (changed) {
                save()
            }
        }

    fun track(onLoaded: () -> Unit) {
        synchronized(lock) {
            if (tracked) {
                return
            }
            tracked = true
        }

        scope.launch {
            val preferences = dataStore.data
                .catch { error ->
                    Log.e(TAG, "Failed to read preferences", error)
                    emit(emptyPreferences())
                }
                .first()

            val valueJson = preferences[key]
            if (valueJson != null) {
                val decoded = runCatching {
                    json.decodeFromString<SerializedState>(valueJson)
                }.onFailure {
                    Log.e(TAG, "Failed to decode preferences, keeping last state", it)
                }.getOrNull()

                if (decoded != null) {
                    synchronized(lock) {
                        state = decoded
                    }
                }
            }

            runCatching { onLoaded() }
                .onFailure { Log.e(TAG, "Failed to handle loaded preferences", it) }
        }
    }

    fun getOrCreate(packageName: String): AppPreferences {
        synchronized(lock) {
            val index = state.indices[packageName]
            if (index != null) {
                return state.values[index]
            }

            val value = AppPreferences()
            state.indices[packageName] = state.values.size
            state.values.add(value)
            return value
        }
    }

    fun save() {
        scope.launch {
            saveMutex.withLock {
                runCatching {
                    val encoded = synchronized(lock) { Json.encodeToString(state) }
                    dataStore.edit { preferences ->
                        preferences[key] = encoded
                    }
                }.onFailure {
                    Log.e(TAG, "Failed to save preferences", it)
                }
            }
        }
    }
}
