package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.antigrafityDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "antigrafity_remote_prefs",
)

data class NotificationSettings(
    val enabled: Boolean = false,
    val intervalMinutes: Long = 60L,
    val title: String = DEFAULT_TITLE,
    val message: String = DEFAULT_MESSAGE,
) {
    companion object {
        const val MIN_INTERVAL_MINUTES = 15L
        const val DEFAULT_TITLE = "Antigrafity Remote"
        const val DEFAULT_MESSAGE = "Waktunya memeriksa status sesi Antigrafity Anda."
        val PRESET_INTERVALS_MINUTES = listOf(15L, 30L, 60L, 180L, 360L, 720L, 1440L)
    }
}

enum class VoiceLanguage(val localeTag: String, val shortLabel: String) {
    INDONESIAN("id-ID", "ID"),
    ENGLISH("en-US", "EN");

    companion object {
        fun fromTag(tag: String?): VoiceLanguage =
            entries.firstOrNull { it.localeTag.equals(tag, ignoreCase = true) } ?: INDONESIAN
    }
}

class AppPreferencesRepository(private val context: Context) {

    private object Keys {
        val NOTIF_ENABLED = booleanPreferencesKey("notif_enabled")
        val NOTIF_INTERVAL_MINUTES = longPreferencesKey("notif_interval_minutes")
        val NOTIF_TITLE = stringPreferencesKey("notif_title")
        val NOTIF_MESSAGE = stringPreferencesKey("notif_message")
        val VOICE_LANGUAGE = stringPreferencesKey("voice_language")
    }

    val notificationSettingsFlow: Flow<NotificationSettings> =
        context.antigrafityDataStore.data.map { prefs ->
            NotificationSettings(
                enabled = prefs[Keys.NOTIF_ENABLED] ?: false,
                intervalMinutes = (prefs[Keys.NOTIF_INTERVAL_MINUTES] ?: 60L)
                    .coerceAtLeast(NotificationSettings.MIN_INTERVAL_MINUTES),
                title = prefs[Keys.NOTIF_TITLE]?.takeIf { it.isNotBlank() }
                    ?: NotificationSettings.DEFAULT_TITLE,
                message = prefs[Keys.NOTIF_MESSAGE]?.takeIf { it.isNotBlank() }
                    ?: NotificationSettings.DEFAULT_MESSAGE,
            )
        }

    val voiceLanguageFlow: Flow<VoiceLanguage> =
        context.antigrafityDataStore.data.map { prefs ->
            VoiceLanguage.fromTag(prefs[Keys.VOICE_LANGUAGE])
        }

    suspend fun getNotificationSettings(): NotificationSettings =
        notificationSettingsFlow.first()

    suspend fun saveNotificationSettings(settings: NotificationSettings) {
        val safeTitle = settings.title.trim().take(80)
            .ifBlank { NotificationSettings.DEFAULT_TITLE }
        val safeMessage = settings.message.trim().take(300)
            .ifBlank { NotificationSettings.DEFAULT_MESSAGE }
        val safeInterval = settings.intervalMinutes
            .coerceAtLeast(NotificationSettings.MIN_INTERVAL_MINUTES)

        context.antigrafityDataStore.edit { prefs ->
            prefs[Keys.NOTIF_ENABLED] = settings.enabled
            prefs[Keys.NOTIF_INTERVAL_MINUTES] = safeInterval
            prefs[Keys.NOTIF_TITLE] = safeTitle
            prefs[Keys.NOTIF_MESSAGE] = safeMessage
        }
    }

    suspend fun saveVoiceLanguage(language: VoiceLanguage) {
        context.antigrafityDataStore.edit { prefs ->
            prefs[Keys.VOICE_LANGUAGE] = language.localeTag
        }
    }
}
