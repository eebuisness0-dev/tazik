package dev.tazik.inferno.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "tazik_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class ContrastLevel { STANDARD, MEDIUM, HIGH }
enum class LanguageMode { SYSTEM, RU, EN, UA }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val oledBlack: Boolean = false,
    val contrastLevel: ContrastLevel = ContrastLevel.STANDARD,
    val languageMode: LanguageMode = LanguageMode.SYSTEM,
    val runInBackground: Boolean = true,
    val notifyWhileRunning: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val askLaunchMode: Boolean = true,
    val lastUsedIpsw: String = "",
    val defaultDeviceName: String = "iPhone 11",
)

class SettingsStore(private val context: Context) {
    private val prefs = context.settingsStore

    val settings: Flow<AppSettings> = prefs.data.map { p ->
        AppSettings(
            themeMode = ThemeMode.entries.firstOrNull { it.name == p[KEY_THEME] } ?: ThemeMode.SYSTEM,
            contrastLevel = ContrastLevel.entries.firstOrNull { it.name == p[KEY_CONTRAST] } ?: ContrastLevel.STANDARD,
            languageMode = LanguageMode.entries.firstOrNull { it.name == p[KEY_LANG] } ?: LanguageMode.SYSTEM,
            dynamicColor = p[KEY_DYNAMIC] ?: true,
            oledBlack = p[KEY_OLED] ?: false,
            runInBackground = p[KEY_BG_RUN] ?: true,
            notifyWhileRunning = p[KEY_NOTIFY] ?: true,
            hapticsEnabled = p[KEY_HAPTICS] ?: true,
            askLaunchMode = p[KEY_ASK_LAUNCH] ?: true,
            lastUsedIpsw = p[KEY_LAST_IPSW] ?: "",
            defaultDeviceName = p[KEY_DEFAULT_NAME] ?: "iPhone 11",
        )
    }

    suspend fun set(fn: (AppSettings) -> AppSettings) {
        val current = settings.first()
        prefs.edit { p ->
            with(fn(current)) {
                p[KEY_THEME] = themeMode.name
                p[KEY_CONTRAST] = contrastLevel.name
                p[KEY_LANG] = languageMode.name
                p[KEY_DYNAMIC] = dynamicColor
                p[KEY_OLED] = oledBlack
                p[KEY_BG_RUN] = runInBackground
                p[KEY_NOTIFY] = notifyWhileRunning
                p[KEY_HAPTICS] = hapticsEnabled
                p[KEY_ASK_LAUNCH] = askLaunchMode
                p[KEY_LAST_IPSW] = lastUsedIpsw
                p[KEY_DEFAULT_NAME] = defaultDeviceName
            }
        }
    }

    companion object {
        private val KEY_THEME = stringPreferencesKey("theme_mode")
        private val KEY_CONTRAST = stringPreferencesKey("contrast_level")
        private val KEY_LANG = stringPreferencesKey("language_mode")
        private val KEY_DYNAMIC = booleanPreferencesKey("dynamic_color")
        private val KEY_OLED = booleanPreferencesKey("oled_black")
        private val KEY_BG_RUN = booleanPreferencesKey("run_in_background")
        private val KEY_NOTIFY = booleanPreferencesKey("notify_while_running")
        private val KEY_HAPTICS = booleanPreferencesKey("haptics_enabled")
        private val KEY_ASK_LAUNCH = booleanPreferencesKey("ask_launch_mode")
        private val KEY_LAST_IPSW = stringPreferencesKey("last_used_ipsw")
        private val KEY_DEFAULT_NAME = stringPreferencesKey("default_device_name")

        @Volatile
        private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) { instance ?: SettingsStore(context.applicationContext).also { instance = it } }
    }
}
