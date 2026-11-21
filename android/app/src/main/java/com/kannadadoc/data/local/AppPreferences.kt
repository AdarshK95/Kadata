package com.kannadadoc.data.local

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppThemeMode {
    LIGHT,
    DARK
}

enum class AppLanguage {
    ENGLISH, // Default: English with existing Kannada meanings preserved
    KANNADA  // Full Kannada UI labels
}

enum class DefaultSaveFormat(
    val displayName: String,
    val displayNameKn: String,
    val subtitle: String,
    val subtitleKn: String,
    val shortLabel: String,
    val jsExportMode: String
) {
    NUDI_PC(
        displayName = "Nudi for PC (.docx)",
        displayNameKn = "ನುಡಿ (PC .docx)",
        subtitle = "Best for colleagues using Nudi on Windows (English stays Calibri)",
        subtitleKn = "ಕಂಪ್ಯೂಟರ್‌ನಲ್ಲಿ ನುಡಿ ಬಳಸುವವರಿಗೆ ಸೂಕ್ತ",
        shortLabel = "Nudi (PC)",
        jsExportMode = "save_default_nudi"
    ),
    BARAHA_PC(
        displayName = "Baraha for PC (.docx)",
        displayNameKn = "ಬರಹ (PC .docx)",
        subtitle = "Best for colleagues using Baraha / BRH Kannada on Windows",
        subtitleKn = "ಕಂಪ್ಯೂಟರ್‌ನಲ್ಲಿ ಬರಹ ಬಳಸುವವರಿಗೆ ಸೂಕ್ತ",
        shortLabel = "Baraha (PC)",
        jsExportMode = "save_default_baraha"
    ),
    UNICODE(
        displayName = "Modern Unicode (.docx)",
        displayNameKn = "ಆಧುನಿಕ ಯೂನಿಕೋಡ್ (.docx)",
        subtitle = "Standard Unicode Kannada (Google Docs, phones, newer Word)",
        subtitleKn = "ಗೂಗಲ್ ಡಾಕ್ಸ್, ಮೊಬೈಲ್ ಮತ್ತು ಹೊಸ ವರ್ಡ್‌ಗೆ ಸೂಕ್ತ",
        shortLabel = "Unicode",
        jsExportMode = "save_default_unicode"
    )
}

data class UserSettings(
    val themeMode: AppThemeMode = AppThemeMode.LIGHT,
    val appLanguage: AppLanguage = AppLanguage.ENGLISH,
    val defaultFontSizePt: Int = 18,
    val defaultSaveFormat: DefaultSaveFormat = DefaultSaveFormat.NUDI_PC,
    val autoSaveIntervalMinutes: Int = 10
) {
    val isDarkTheme: Boolean get() = themeMode == AppThemeMode.DARK
    val isKannadaUi: Boolean get() = appLanguage == AppLanguage.KANNADA
}

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("kannadadoc_prefs", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    private fun loadSettings(): UserSettings {
        val themeName = prefs.getString(KEY_THEME_MODE, AppThemeMode.LIGHT.name)
        val theme = runCatching { AppThemeMode.valueOf(themeName ?: "") }
            .getOrDefault(AppThemeMode.LIGHT)

        val langName = prefs.getString(KEY_APP_LANGUAGE, AppLanguage.ENGLISH.name)
        val language = runCatching { AppLanguage.valueOf(langName ?: "") }
            .getOrDefault(AppLanguage.ENGLISH)

        val savedFormatName = prefs.getString(KEY_SAVE_FORMAT, DefaultSaveFormat.NUDI_PC.name)
        val format = runCatching { DefaultSaveFormat.valueOf(savedFormatName ?: "") }
            .getOrDefault(DefaultSaveFormat.NUDI_PC)

        val autoSaveMin = prefs.getInt(KEY_AUTOSAVE_MINUTES, 10).coerceIn(1, 60)

        return UserSettings(
            themeMode = theme,
            appLanguage = language,
            defaultFontSizePt = prefs.getInt(KEY_FONT_SIZE, 18).coerceIn(14, 32),
            defaultSaveFormat = format,
            autoSaveIntervalMinutes = autoSaveMin
        )
    }

    fun setThemeMode(mode: AppThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
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

    /**
     * Returns true only on the first cold open of the current calendar day (local timezone).
     * Immediately marks today as shown so resuming from background or reopening later today
     * skips the 1.5s splash screen.
     */
    fun consumeDailySplashIfDue(): Boolean {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val lastShown = prefs.getString(KEY_LAST_SPLASH_DATE, null)
        if (lastShown == today) return false
        prefs.edit().putString(KEY_LAST_SPLASH_DATE, today).apply()
        return true
    }

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_APP_LANGUAGE = "app_language"
        private const val KEY_FONT_SIZE = "default_font_size_pt"
        private const val KEY_SAVE_FORMAT = "default_save_format"
        private const val KEY_AUTOSAVE_MINUTES = "autosave_interval_minutes"
        private const val KEY_LAST_SPLASH_DATE = "last_daily_splash_date"

        val AUTO_SAVE_OPTIONS_MINUTES = listOf(1, 2, 5, 10, 15, 30)
    }
}
