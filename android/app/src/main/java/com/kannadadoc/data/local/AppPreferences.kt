package com.kannadadoc.data.local

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

enum class AppLanguage {
    ENGLISH, // Default: English with existing Kannada meanings preserved
    KANNADA  // Full Kannada UI labels
}

enum class HomeViewMode {
    GRID,
    DETAILS_LIST
}

enum class DefaultSaveFormat(
    val displayName: String,
    val displayNameKn: String,
    val bilingualTitle: String,
    val subtitle: String,
    val subtitleKn: String,
    val bilingualSubtitle: String,
    val shortLabel: String,
    val jsExportMode: String
) {
    NUDI_PC(
        displayName = "Nudi",
        displayNameKn = "ನುಡಿ",
        bilingualTitle = "Nudi • ನುಡಿ",
        subtitle = "Supported on PC only",
        subtitleKn = "ಕಂಪ್ಯೂಟರ್‌ನಲ್ಲಿ (PC) ಮಾತ್ರ ಬೆಂಬಲಿತವಾಗಿದೆ",
        bilingualSubtitle = "Supported on PC only • ಕಂಪ್ಯೂಟರ್‌ನಲ್ಲಿ (PC) ಮಾತ್ರ ಬೆಂಬಲಿತವಾಗಿದೆ",
        shortLabel = "Nudi",
        jsExportMode = "save_default_nudi"
    ),
    BARAHA_PC(
        displayName = "Baraha",
        displayNameKn = "ಬರಹ",
        bilingualTitle = "Baraha • ಬರಹ",
        subtitle = "Supported on PC only",
        subtitleKn = "ಕಂಪ್ಯೂಟರ್‌ನಲ್ಲಿ (PC) ಮಾತ್ರ ಬೆಂಬಲಿತವಾಗಿದೆ",
        bilingualSubtitle = "Supported on PC only • ಕಂಪ್ಯೂಟರ್‌ನಲ್ಲಿ (PC) ಮಾತ್ರ ಬೆಂಬಲಿತವಾಗಿದೆ",
        shortLabel = "Baraha",
        jsExportMode = "save_default_baraha"
    ),
    UNICODE(
        displayName = "Modern Universal Kannada",
        displayNameKn = "ಆಧುನಿಕ ಸಾರ್ವತ್ರಿಕ ಕನ್ನಡ",
        bilingualTitle = "Modern Universal Kannada • ಆಧುನಿಕ ಸಾರ್ವತ್ರಿಕ ಕನ್ನಡ",
        subtitle = "Supported on all modern devices (phones, tablets, etc.), not supported in Nudi & Baraha",
        subtitleKn = "ಫೋನ್, ಟ್ಯಾಬ್ಲೆಟ್ ಮುಂತಾದ ಎಲ್ಲಾ ಆಧುನಿಕ ಸಾಧನಗಳಲ್ಲಿ ಬೆಂಬಲಿತವಾಗಿದೆ, ಆದರೆ ನುಡಿ ಮತ್ತು ಬರಹದಲ್ಲಿ ಬೆಂಬಲಿತವಾಗಿಲ್ಲ",
        bilingualSubtitle = "Supported on all modern devices (phones, tablets, etc.), not supported in Nudi & Baraha • ಫೋನ್, ಟ್ಯಾಬ್ಲೆಟ್ ಮುಂತಾದ ಎಲ್ಲಾ ಆಧುನಿಕ ಸಾಧನಗಳಲ್ಲಿ ಬೆಂಬಲಿತವಾಗಿದೆ, ಆದರೆ ನುಡಿ ಮತ್ತು ಬರಹದಲ್ಲಿ ಬೆಂಬಲಿತವಾಗಿಲ್ಲ",
        shortLabel = "Universal",
        jsExportMode = "save_default_unicode"
    );

    companion object {
        const val KADATA_ALL_FORMATS_NOTE_BILINGUAL =
            "All of these formats are supported in Kadata — bring in a document from any format and Kadata automatically detects and displays it seamlessly.\n" +
                "ಈ ಎಲ್ಲಾ ಫಾರ್ಮ್ಯಾಟ್‌ಗಳು ಕಡತ ಆಪ್‌ನಲ್ಲಿ ಬೆಂಬಲಿತವಾಗಿವೆ — ಯಾವುದೇ ಫಾರ್ಮ್ಯಾಟ್‌ನ ದಾಖಲೆಯನ್ನು ತಂದರೂ ಕಡತ ಸ್ವಯಂಚಾಲಿತವಾಗಿ ಗುರುತಿಸಿ ಸುಗಮವಾಗಿ ತೋರಿಸುತ್ತದೆ."
    }
}

data class UserSettings(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val appLanguage: AppLanguage = AppLanguage.ENGLISH,
    val defaultFontSizePt: Int = 18,
    val defaultSaveFormat: DefaultSaveFormat = DefaultSaveFormat.NUDI_PC,
    val autoSaveIntervalMinutes: Int = 5,
    val blockAutoKeyboard: Boolean = false,
    val homeViewMode: HomeViewMode = HomeViewMode.GRID
) {
    val isKannadaUi: Boolean get() = appLanguage == AppLanguage.KANNADA

    fun resolveDarkTheme(isSystemInDarkTheme: Boolean): Boolean = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
    }
}

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("kannadadoc_prefs", Context.MODE_PRIVATE)
    private val formatPrefs = context.getSharedPreferences("kadata_doc_formats", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    private val _documentFormatMap = MutableStateFlow(loadFormatMap())
    val documentFormatMap: StateFlow<Map<String, String>> = _documentFormatMap.asStateFlow()

    private fun loadSettings(): UserSettings {
        val themeName = prefs.getString(KEY_THEME_MODE_V2, AppThemeMode.SYSTEM.name)
        val theme = runCatching { AppThemeMode.valueOf(themeName ?: "") }
            .getOrDefault(AppThemeMode.SYSTEM)

        val langName = prefs.getString(KEY_APP_LANGUAGE, AppLanguage.ENGLISH.name)
        val language = runCatching { AppLanguage.valueOf(langName ?: "") }
            .getOrDefault(AppLanguage.ENGLISH)

        val savedFormatName = prefs.getString(KEY_SAVE_FORMAT, DefaultSaveFormat.NUDI_PC.name)
        val format = runCatching { DefaultSaveFormat.valueOf(savedFormatName ?: "") }
            .getOrDefault(DefaultSaveFormat.NUDI_PC)

        val viewModeName = prefs.getString(KEY_HOME_VIEW_MODE, HomeViewMode.GRID.name)
        val viewMode = runCatching { HomeViewMode.valueOf(viewModeName ?: "") }
            .getOrDefault(HomeViewMode.GRID)

        val autoSaveMin = prefs.getInt(KEY_AUTOSAVE_MINUTES, 5).coerceIn(1, 60)

        return UserSettings(
            themeMode = theme,
            appLanguage = language,
            defaultFontSizePt = prefs.getInt(KEY_FONT_SIZE, 18).coerceIn(14, 32),
            defaultSaveFormat = format,
            autoSaveIntervalMinutes = autoSaveMin,
            blockAutoKeyboard = prefs.getBoolean(KEY_BLOCK_AUTO_KB, false),
            homeViewMode = viewMode
        )
    }

    private fun loadFormatMap(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for ((k, v) in formatPrefs.all) {
            if (v is String && v.isNotBlank()) {
                result[k] = v
            }
        }
        return result
    }

    fun setDocumentFormatTag(uriString: String, formatTag: String) {
        if (uriString.isBlank() || formatTag.isBlank()) return
        formatPrefs.edit().putString(uriString, formatTag).apply()
        val updated = _documentFormatMap.value.toMutableMap()
        updated[uriString] = formatTag
        _documentFormatMap.value = updated
    }

    fun removeDocumentFormatTag(uriString: String) {
        if (uriString.isBlank()) return
        formatPrefs.edit().remove(uriString).apply()
        val updated = _documentFormatMap.value.toMutableMap()
        updated.remove(uriString)
        _documentFormatMap.value = updated
    }

    fun setThemeMode(mode: AppThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE_V2, mode.name).apply()
        _settings.value = _settings.value.copy(themeMode = mode)
    }

    fun toggleLanguage() {
        val next = if (_settings.value.appLanguage == AppLanguage.ENGLISH) {
            AppLanguage.KANNADA
        } else {
            AppLanguage.ENGLISH
        }
        setAppLanguage(next)
    }

    fun setAppLanguage(language: AppLanguage) {
        prefs.edit().putString(KEY_APP_LANGUAGE, language.name).apply()
        _settings.value = _settings.value.copy(appLanguage = language)
    }

    fun setDefaultFontSizePt(sizePt: Int) {
        val clamped = sizePt.coerceIn(14, 32)
        prefs.edit().putInt(KEY_FONT_SIZE, clamped).apply()
        _settings.value = _settings.value.copy(defaultFontSizePt = clamped)
    }

    fun setDefaultSaveFormat(format: DefaultSaveFormat) {
        prefs.edit().putString(KEY_SAVE_FORMAT, format.name).apply()
        _settings.value = _settings.value.copy(defaultSaveFormat = format)
    }

    fun setAutoSaveIntervalMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(1, 60)
        prefs.edit().putInt(KEY_AUTOSAVE_MINUTES, clamped).apply()
        _settings.value = _settings.value.copy(autoSaveIntervalMinutes = clamped)
    }

    fun setBlockAutoKeyboard(blocked: Boolean) {
        prefs.edit().putBoolean(KEY_BLOCK_AUTO_KB, blocked).apply()
        _settings.value = _settings.value.copy(blockAutoKeyboard = blocked)
    }

    fun toggleHomeViewMode() {
        val next = if (_settings.value.homeViewMode == HomeViewMode.GRID) {
            HomeViewMode.DETAILS_LIST
        } else {
            HomeViewMode.GRID
        }
        setHomeViewMode(next)
    }

    fun setHomeViewMode(mode: HomeViewMode) {
        prefs.edit().putString(KEY_HOME_VIEW_MODE, mode.name).apply()
        _settings.value = _settings.value.copy(homeViewMode = mode)
    }

    fun consumeDailySplashIfDue(): Boolean {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val lastShown = prefs.getString(KEY_LAST_SPLASH_DATE, null)
        if (lastShown == today) return false
        prefs.edit().putString(KEY_LAST_SPLASH_DATE, today).apply()
        return true
    }

    companion object {
        private const val KEY_THEME_MODE_V2 = "theme_mode_v2"
        private const val KEY_APP_LANGUAGE = "app_language"
        private const val KEY_FONT_SIZE = "default_font_size_pt"
        private const val KEY_SAVE_FORMAT = "default_save_format"
        private const val KEY_AUTOSAVE_MINUTES = "autosave_interval_minutes_v2"
        private const val KEY_LAST_SPLASH_DATE = "last_daily_splash_date"
        private const val KEY_BLOCK_AUTO_KB = "block_auto_keyboard"
        private const val KEY_HOME_VIEW_MODE = "home_view_mode"

        val AUTO_SAVE_OPTIONS_MINUTES = listOf(1, 2, 5, 10, 15, 30)
    }
}
