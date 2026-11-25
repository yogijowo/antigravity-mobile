package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.VoiceLanguage
import com.example.ui.theme.VoiceActiveCoral
import com.example.voice.VoiceStatus
import com.example.voice.VoiceUiState

@Composable
fun VoiceCommandOverlay(
    voiceState: VoiceUiState,
    selectedLanguage: VoiceLanguage,
    onSelectLanguage: (VoiceLanguage) -> Unit,
    onRetryListen: () -> Unit,
    onStopAndSend: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = voiceState.isVisible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .testTag("voice_command_overlay"),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                border = BorderStroke(
                    width = 1.5.dp,
                    color = if (voiceState.status == VoiceStatus.LISTENING) {
                        VoiceActiveCoral.copy(alpha = 0.65f)
                    } else {
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    },
                ),
                shadowElevation = 16.dp,
                tonalElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = null,
                                tint = if (voiceState.status == VoiceStatus.LISTENING) {
                                    VoiceActiveCoral
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = stringResource(R.string.voice_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            VoiceLanguage.entries.forEach { lang ->
                                FilterChip(
                                    selected = selectedLanguage == lang,
                                    onClick = { onSelectLanguage(lang) },
                                    label = { Text(lang.shortLabel) },
                                    modifier = Modifier.testTag("voice_lang_${lang.shortLabel.lowercase()}"),
                                )
                            }
                            IconButton(
                                onClick = onCancel,
                                modifier = Modifier.testTag("voice_close_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.action_cancel),
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Animated audio level bars
                    VoiceLevelVisualizer(
                        rmsLevel = voiceState.rmsLevel,
                        isListening = voiceState.status == VoiceStatus.LISTENING,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    val statusText = when {
                        voiceState.partialTranscript.isNotBlank() -> voiceState.partialTranscript
                        voiceState.status == VoiceStatus.ERROR -> voiceState.errorMessage
                            ?: stringResource(R.string.voice_ready)
                        voiceState.status == VoiceStatus.LISTENING -> stringResource(R.string.voice_listening)
                        voiceState.status == VoiceStatus.PROCESSING -> stringResource(R.string.voice_processing)
                        else -> stringResource(R.string.voice_ready)
                    }

                    Text(
                        text = statusText,
                        style = if (voiceState.partialTranscript.isNotBlank()) {
                            MaterialTheme.typography.bodyLarge
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        color = if (voiceState.status == VoiceStatus.ERROR) {
                            MaterialTheme.colorScheme.error
                        } else if (voiceState.partialTranscript.isNotBlank()) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("voice_cancel_button"),
                        ) {
                            Text(text = stringResource(R.string.action_cancel))
                        }

                        if (voiceState.status == VoiceStatus.LISTENING) {
                            Button(
                                onClick = onStopAndSend,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = VoiceActiveCoral,
                                    contentColor = Color.White,
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("voice_stop_send_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Send,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = stringResource(R.string.action_stop_and_send))
                            }
                        } else {
                            Button(
                                onClick = onRetryListen,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("voice_retry_mic_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Mic,
                                    contentDescription = stringResource(R.string.cd_voice_mic),
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = stringResource(R.string.action_voice))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceLevelVisualizer(
    rmsLevel: Float,
    isListening: Boolean,
) {
    val barWeights = listOf(0.45f, 0.78f, 1.0f, 0.85f, 0.55f, 0.92f, 0.6f)
    val activeColor = if (isListening) VoiceActiveCoral else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)

    Row(
        modifier = Modifier.height(42.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        barWeights.forEach { weight ->
            val targetHeight = if (isListening) {
                (10f + (rmsLevel * 30f * weight)).dp
            } else {
                8.dp
            }
            val animatedHeight by animateDpAsState(
                targetValue = targetHeight,
                animationSpec = spring(stiffness = 450f),
                label = "VoiceBarHeight",
            )
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(animatedHeight)
                    .clip(CircleShape)
                    .background(activeColor),
            )
        }
    }
}
