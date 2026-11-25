package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.data.AppPreferencesRepository
import com.example.data.NotificationSettings
import com.example.data.VoiceLanguage
import com.example.notifications.NotificationHelper
import com.example.notifications.NotificationScheduler
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RemoteControlsUiState(
    val isPillExpanded: Boolean = false,
    val isPillTucked: Boolean = false,
    val isNotificationSheetOpen: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AppPreferencesRepository(application)

    val notificationSettings: StateFlow<NotificationSettings> = repository.notificationSettingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = NotificationSettings(),
        )

    val voiceLanguage: StateFlow<VoiceLanguage> = repository.voiceLanguageFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = VoiceLanguage.INDONESIAN,
        )

    private val _controlsState = MutableStateFlow(RemoteControlsUiState())
    val controlsState: StateFlow<RemoteControlsUiState> = _controlsState.asStateFlow()

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val snackbarEvents = _snackbarEvents.asSharedFlow()

    init {
        NotificationHelper.createChannels(application)
    }

    fun togglePillExpanded() {
        _controlsState.update {
            it.copy(
                isPillExpanded = !it.isPillExpanded,
                isPillTucked = false,
            )
        }
    }

    fun collapsePill() {
        _controlsState.update { it.copy(isPillExpanded = false) }
    }

    fun tuckPillToEdge() {
        _controlsState.update { it.copy(isPillExpanded = false, isPillTucked = true) }
    }

    fun untuckPill() {
        _controlsState.update { it.copy(isPillTucked = false, isPillExpanded = false) }
    }

    fun openNotificationSheet() {
        _controlsState.update {
            it.copy(isPillExpanded = false, isNotificationSheetOpen = true)
        }
    }

    fun closeNotificationSheet() {
        _controlsState.update { it.copy(isNotificationSheetOpen = false) }
    }

    fun selectVoiceLanguage(language: VoiceLanguage) {
        viewModelScope.launch {
            repository.saveVoiceLanguage(language)
        }
    }

    fun saveNotificationSettings(settings: NotificationSettings) {
        viewModelScope.launch {
            repository.saveNotificationSettings(settings)
            NotificationScheduler.applySchedule(getApplication(), settings)
            _controlsState.update { it.copy(isNotificationSheetOpen = false) }
            emitSnackbar(getApplication<Application>().getString(R.string.notif_saved_toast))
        }
    }

    fun sendTestNotification(title: String, message: String) {
        val app = getApplication<Application>()
        val safeTitle = title.trim().ifBlank { NotificationSettings.DEFAULT_TITLE }
        val safeMessage = message.trim().ifBlank { NotificationSettings.DEFAULT_MESSAGE }
        val sent = NotificationHelper.showReminderNotification(app, safeTitle, safeMessage)
        if (sent) {
            emitSnackbar(app.getString(R.string.notif_test_sent_toast))
        } else {
            emitSnackbar(app.getString(R.string.notif_permission_needed))
        }
    }

    fun emitSnackbar(message: String) {
        _snackbarEvents.tryEmit(message)
    }
}
