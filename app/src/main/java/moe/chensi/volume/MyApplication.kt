package moe.chensi.volume

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuProvider

class MyApplication : Application() {
    companion object {
        const val MIN_FLOATING_BUTTON_SIZE_DP = 40
        const val MAX_FLOATING_BUTTON_SIZE_DP = 96
        const val DEFAULT_FLOATING_BUTTON_SIZE_DP = 56
        const val FLOATING_BUTTON_SIZE_STEP_DP = 4

        const val MIN_POPUP_WIDTH_DP = 150
        const val MAX_POPUP_WIDTH_DP = 420
        const val DEFAULT_POPUP_WIDTH_DP = 220
        const val MIN_POPUP_HEIGHT_PERCENT = 25
        const val MAX_POPUP_HEIGHT_PERCENT = 80
        const val DEFAULT_POPUP_HEIGHT_PERCENT = 45
        const val MIN_POPUP_OPACITY = 0.5f
        const val MAX_POPUP_OPACITY = 1f
        const val DEFAULT_POPUP_OPACITY = 0.96f
        const val MIN_POPUP_VERTICAL_OFFSET_DP = -240
        const val MAX_POPUP_VERTICAL_OFFSET_DP = 240
        const val DEFAULT_POPUP_VERTICAL_OFFSET_DP = 0

        private const val FLOATING_BUTTON_PREFERENCES = "floating_button"
        private const val KEY_FLOATING_BUTTON_ENABLED = "enabled"
        private const val KEY_FLOATING_BUTTON_SIZE_DP = "size_dp"
        private const val SYSTEM_VOLUME_HOOKS_PREFERENCES = "system_volume_hooks"
        private const val KEY_SYSTEM_VOLUME_HOOKS_ENABLED = "enabled"
        private const val APPEARANCE_PREFERENCES = "appearance"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_POPUP_WIDTH_DP = "popup_width_dp"
        private const val KEY_POPUP_HEIGHT_PERCENT = "popup_height_percent"
        private const val KEY_POPUP_OPACITY = "popup_opacity"
        private const val KEY_POPUP_SIDE = "popup_side"
        private const val KEY_POPUP_VERTICAL = "popup_vertical"
        private const val KEY_POPUP_VERTICAL_OFFSET_DP = "popup_vertical_offset_dp"
    }

    val dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_volumes")
    private val floatingButtonPreferences by lazy {
        getSharedPreferences(FLOATING_BUTTON_PREFERENCES, MODE_PRIVATE)
    }
    private val systemVolumeHooksPreferences by lazy {
        getSharedPreferences(SYSTEM_VOLUME_HOOKS_PREFERENCES, MODE_PRIVATE)
    }
    private val appearancePreferences by lazy {
        getSharedPreferences(APPEARANCE_PREFERENCES, MODE_PRIVATE)
    }

    private val _themeMode = MutableStateFlow(ThemeMode.System)
    val themeMode = _themeMode.asStateFlow()

    private val _popupAppearance = MutableStateFlow(PopupAppearance())
    val popupAppearance = _popupAppearance.asStateFlow()

    private val _floatingButtonEnabled = MutableStateFlow(false)
    val floatingButtonEnabled = _floatingButtonEnabled.asStateFlow()

    private val _floatingButtonSize = MutableStateFlow(DEFAULT_FLOATING_BUTTON_SIZE_DP)
    val floatingButtonSize = _floatingButtonSize.asStateFlow()

    private val _systemVolumeButtonHooksEnabled = MutableStateFlow(true)
    val systemVolumeButtonHooksEnabled = _systemVolumeButtonHooksEnabled.asStateFlow()

    val manager by lazy {
        Manager(this, dataStore)
    }

    override fun onCreate() {
        super.onCreate()

        _floatingButtonEnabled.value =
            floatingButtonPreferences.getBoolean(KEY_FLOATING_BUTTON_ENABLED, false)
        _floatingButtonSize.value = floatingButtonPreferences.getInt(
            KEY_FLOATING_BUTTON_SIZE_DP,
            DEFAULT_FLOATING_BUTTON_SIZE_DP
        ).coerceIn(MIN_FLOATING_BUTTON_SIZE_DP, MAX_FLOATING_BUTTON_SIZE_DP)
        _systemVolumeButtonHooksEnabled.value =
            systemVolumeHooksPreferences.getBoolean(KEY_SYSTEM_VOLUME_HOOKS_ENABLED, true)

        _themeMode.value = runCatching {
            ThemeMode.valueOf(
                appearancePreferences.getString(KEY_THEME_MODE, ThemeMode.System.name)
                    ?: ThemeMode.System.name
            )
        }.getOrDefault(ThemeMode.System)

        _popupAppearance.value = PopupAppearance(
            widthDp = appearancePreferences.getInt(
                KEY_POPUP_WIDTH_DP,
                DEFAULT_POPUP_WIDTH_DP
            ).coerceIn(MIN_POPUP_WIDTH_DP, MAX_POPUP_WIDTH_DP),
            maxHeightPercent = appearancePreferences.getInt(
                KEY_POPUP_HEIGHT_PERCENT,
                DEFAULT_POPUP_HEIGHT_PERCENT
            ).coerceIn(MIN_POPUP_HEIGHT_PERCENT, MAX_POPUP_HEIGHT_PERCENT),
            opacity = appearancePreferences.getFloat(
                KEY_POPUP_OPACITY,
                DEFAULT_POPUP_OPACITY
            ).coerceIn(MIN_POPUP_OPACITY, MAX_POPUP_OPACITY),
            side = runCatching {
                PopupSide.valueOf(
                    appearancePreferences.getString(KEY_POPUP_SIDE, PopupSide.Right.name)
                        ?: PopupSide.Right.name
                )
            }.getOrDefault(PopupSide.Right),
            vertical = runCatching {
                PopupVertical.valueOf(
                    appearancePreferences.getString(
                        KEY_POPUP_VERTICAL,
                        PopupVertical.Center.name
                    ) ?: PopupVertical.Center.name
                )
            }.getOrDefault(PopupVertical.Center),
            verticalOffsetDp = appearancePreferences.getInt(
                KEY_POPUP_VERTICAL_OFFSET_DP,
                DEFAULT_POPUP_VERTICAL_OFFSET_DP
            ).coerceIn(MIN_POPUP_VERTICAL_OFFSET_DP, MAX_POPUP_VERTICAL_OFFSET_DP)
        )
    }

    fun setFloatingButtonEnabled(enabled: Boolean) {
        floatingButtonPreferences.edit {
            putBoolean(KEY_FLOATING_BUTTON_ENABLED, enabled)
        }
        _floatingButtonEnabled.value = enabled
    }

    fun setFloatingButtonSize(sizeDp: Int) {
        val size = sizeDp.coerceIn(MIN_FLOATING_BUTTON_SIZE_DP, MAX_FLOATING_BUTTON_SIZE_DP)
        floatingButtonPreferences.edit {
            putInt(KEY_FLOATING_BUTTON_SIZE_DP, size)
        }
        _floatingButtonSize.value = size
    }

    fun setSystemVolumeButtonHooksEnabled(enabled: Boolean) {
        systemVolumeHooksPreferences.edit {
            putBoolean(KEY_SYSTEM_VOLUME_HOOKS_ENABLED, enabled)
        }
        _systemVolumeButtonHooksEnabled.value = enabled
    }

    fun setThemeMode(mode: ThemeMode) {
        appearancePreferences.edit {
            putString(KEY_THEME_MODE, mode.name)
        }
        _themeMode.value = mode
    }

    fun updatePopupAppearance(transform: (PopupAppearance) -> PopupAppearance) {
        val current = _popupAppearance.value
        val next = transform(current).let {
            it.copy(
                widthDp = it.widthDp.coerceIn(MIN_POPUP_WIDTH_DP, MAX_POPUP_WIDTH_DP),
                maxHeightPercent = it.maxHeightPercent.coerceIn(
                    MIN_POPUP_HEIGHT_PERCENT,
                    MAX_POPUP_HEIGHT_PERCENT
                ),
                opacity = it.opacity.coerceIn(MIN_POPUP_OPACITY, MAX_POPUP_OPACITY),
                verticalOffsetDp = it.verticalOffsetDp.coerceIn(
                    MIN_POPUP_VERTICAL_OFFSET_DP,
                    MAX_POPUP_VERTICAL_OFFSET_DP
                )
            )
        }
        if (next == current) {
            return
        }

        appearancePreferences.edit {
            putInt(KEY_POPUP_WIDTH_DP, next.widthDp)
            putInt(KEY_POPUP_HEIGHT_PERCENT, next.maxHeightPercent)
            putFloat(KEY_POPUP_OPACITY, next.opacity)
            putString(KEY_POPUP_SIDE, next.side.name)
            putString(KEY_POPUP_VERTICAL, next.vertical.name)
            putInt(KEY_POPUP_VERTICAL_OFFSET_DP, next.verticalOffsetDp)
        }
        _popupAppearance.value = next
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)

        val processName = getProcessName()
        Log.i("Application", "process name = $processName")
        ShizukuProvider.enableMultiProcessSupport(!processName.contains(":"))
        HiddenApiBypass.addHiddenApiExemptions("")
        
        CrashHandler.ensureInitialized(this)
    }
}