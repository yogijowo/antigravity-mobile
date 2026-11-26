package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.voice.AiAssistantPhase
import com.example.voice.AiAssistantUiState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

private val HudObsidianDeep = Color(0xFF060A14)
private val HudObsidianCard = Color(0xFF0F172A)
private val MatrixCyan = Color(0xFF38BDF8)
private val MatrixBlue = Color(0xFF60A5FA)
private val MatrixIndigo = Color(0xFF818CF8)
private val MatrixEmerald = Color(0xFF34D399)
private val MatrixAmber = Color(0xFFFBBF24)
private val MatrixCoral = Color(0xFFF87171)

/**
 * Full-screen interactive Indonesian AI Assistant overlay featuring an animated human-face
 * dot-matrix visualization, live active parent process ticker, and strict mic-lock feedback.
 */
@Composable
fun AiFaceMatrixOverlay(
    state: AiAssistantUiState,
    onToggleMute: () -> Unit,
    onMicClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state.isVisible,
        enter = fadeIn(animationSpec = tween(220)),
        exit = fadeOut(animationSpec = tween(180)),
        modifier = modifier.fillMaxSize(),
    ) {
        val accentColor = when (state.phase) {
            AiAssistantPhase.LISTENING -> MatrixCyan
            AiAssistantPhase.PROCESSING, AiAssistantPhase.SENDING -> MatrixIndigo
            AiAssistantPhase.SPEAKING_DONE, AiAssistantPhase.GREETING -> MatrixEmerald
            else -> MatrixBlue
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.14f),
                            HudObsidianDeep,
                            Color(0xFF03050B),
                        ),
                    ),
                )
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .navigationBarsPadding()
                .testTag("ai_face_matrix_overlay"),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 560.dp)
                    .align(Alignment.Center)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 1. Top Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.ai_mode_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        PhaseStatusChip(phase = state.phase, accentColor = accentColor)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(
                            onClick = onToggleMute,
                            modifier = Modifier.testTag("ai_mute_button"),
                        ) {
                            Icon(
                                imageVector = if (state.isMuted) {
                                    Icons.AutoMirrored.Rounded.VolumeOff
                                } else {
                                    Icons.AutoMirrored.Rounded.VolumeUp
                                },
                                contentDescription = stringResource(R.string.cd_ai_mute_toggle),
                                tint = if (state.isMuted) MatrixCoral else Color.White.copy(alpha = 0.85f),
                            )
                        }
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.testTag("ai_close_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.cd_ai_close),
                                tint = Color.White.copy(alpha = 0.9f),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 2. Hero Animated Human Face Dot-Matrix Canvas
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    AiHumanFaceMatrixCanvas(
                        phase = state.phase,
                        isTtsSpeaking = state.isTtsSpeaking,
                        rmsLevel = state.rmsLevel,
                        accentColor = accentColor,
                        modifier = Modifier
                            .fillMaxWidth(0.86f)
                            .widthIn(max = 330.dp)
                            .aspectRatio(0.92f)
                            .testTag("ai_human_face_matrix"),
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 3. Live Parent Process & Conversation Display Card
                AiProcessAndTranscriptCard(
                    state = state,
                    accentColor = accentColor,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(18.dp))

                // 4. Bottom Mic Control & Turn-Taking Lock Indicator
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (state.isMicLocked) {
                            MatrixAmber.copy(alpha = 0.14f)
                        } else {
                            accentColor.copy(alpha = 0.14f)
                        },
                        border = BorderStroke(
                            1.dp,
                            if (state.isMicLocked) {
                                MatrixAmber.copy(alpha = 0.45f)
                            } else {
                                accentColor.copy(alpha = 0.45f)
                            },
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = if (state.isMicLocked) Icons.Rounded.Lock else Icons.Rounded.GraphicEq,
                                contentDescription = null,
                                tint = if (state.isMicLocked) MatrixAmber else accentColor,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                text = if (state.isMicLocked) {
                                    stringResource(R.string.ai_mic_locked_hint)
                                } else {
                                    stringResource(R.string.ai_mic_ready_hint)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (state.isMicLocked) MatrixAmber else Color.White.copy(alpha = 0.9f),
                            )
                        }
                    }

                    Surface(
                        onClick = onMicClick,
                        enabled = !state.isMicLocked,
                        shape = CircleShape,
                        color = when {
                            state.isMicLocked -> Color(0xFF1E293B)
                            state.phase == AiAssistantPhase.LISTENING -> MatrixCyan
                            else -> accentColor.copy(alpha = 0.9f)
                        },
                        border = BorderStroke(
                            width = 2.dp,
                            color = if (state.isMicLocked) {
                                Color.White.copy(alpha = 0.14f)
                            } else {
                                Color.White.copy(alpha = 0.45f)
                            },
                        ),
                        shadowElevation = if (state.isMicLocked) 0.dp else 12.dp,
                        modifier = Modifier
                            .size(68.dp)
                            .testTag("ai_mic_button"),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = when {
                                    state.isMicLocked -> Icons.Rounded.MicOff
                                    else -> Icons.Rounded.Mic
                                },
                                contentDescription = stringResource(R.string.cd_voice_mic),
                                tint = if (state.isMicLocked) {
                                    Color.White.copy(alpha = 0.38f)
                                } else {
                                    HudObsidianDeep
                                },
                                modifier = Modifier.size(30.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun PhaseStatusChip(
    phase: AiAssistantPhase,
    accentColor: Color,
) {
    val label = when (phase) {
        AiAssistantPhase.GREETING -> stringResource(R.string.ai_status_greeting)
        AiAssistantPhase.LISTENING -> stringResource(R.string.ai_status_listening)
        AiAssistantPhase.SENDING -> stringResource(R.string.ai_status_sending)
        AiAssistantPhase.PROCESSING -> stringResource(R.string.ai_status_processing)
        AiAssistantPhase.SPEAKING_DONE -> stringResource(R.string.ai_status_speaking)
        AiAssistantPhase.IDLE, AiAssistantPhase.HIDDEN -> stringResource(R.string.ai_status_idle)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(accentColor),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = accentColor.copy(alpha = 0.95f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AiProcessAndTranscriptCard(
    state: AiAssistantUiState,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.testTag("ai_process_panel"),
        shape = RoundedCornerShape(22.dp),
        color = HudObsidianCard.copy(alpha = 0.88f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.28f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Show active parent process prominently when processing or after receiving process updates
            val showProcessHeader = state.phase == AiAssistantPhase.PROCESSING ||
                state.phase == AiAssistantPhase.SENDING ||
                state.currentProcessText.isNotBlank()

            if (showProcessHeader && state.phase != AiAssistantPhase.LISTENING) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = if (state.phase == AiAssistantPhase.PROCESSING || state.phase == AiAssistantPhase.SENDING) {
                            Icons.Rounded.Sync
                        } else {
                            Icons.Rounded.CheckCircle
                        },
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = stringResource(R.string.ai_process_header),
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Text(
                    text = state.currentProcessText.ifBlank { state.statusSubtext },
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("ai_active_process_text"),
                )

                // Compact trail of previous parent steps (never nested sub-steps)
                val previousSteps = state.parentProcessLog.dropLast(1).takeLast(2)
                if (previousSteps.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        previousSteps.forEach { step ->
                            Text(
                                text = "✓ $step",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.56f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            } else {
                // Listening / Greeting / Idle live prompt view
                val mainDisplay = when {
                    !state.errorMessage.isNullOrBlank() -> state.errorMessage
                    state.liveTranscript.isNotBlank() -> "“${state.liveTranscript}”"
                    state.statusSubtext.isNotBlank() -> state.statusSubtext
                    else -> stringResource(R.string.ai_mic_ready_hint)
                }

                Text(
                    text = mainDisplay,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (!state.errorMessage.isNullOrBlank()) MatrixCoral else Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ai_live_transcript_text"),
                )

                if (state.lastUserPrompt.isNotBlank() && state.liveTranscript.isBlank()) {
                    Text(
                        text = "Terakhir dikirim: ${state.lastUserPrompt}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.52f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * Custom Jetpack Compose Canvas rendering a sculptural human-face dot-matrix ("matrik wajah orang")
 * with natural blinking eyes, processing scanline wave, lip-sync mouth modulation during speech,
 * and reactive audio rings when listening.
 */
@Composable
private fun AiHumanFaceMatrixCanvas(
    phase: AiAssistantPhase,
    isTtsSpeaking: Boolean,
    rmsLevel: Float,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "AiFaceMatrixAnim")

    val breathPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "BreathPhase",
    )

    val scanPhase by infiniteTransition.animateFloat(
        initialValue = -1.2f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ScanPhase",
    )

    val speechWave by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 480, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "SpeechWave",
    )

    val blinkCycle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "BlinkCycle",
    )

    val smoothRms by animateFloatAsState(
        targetValue = if (phase == AiAssistantPhase.LISTENING) rmsLevel else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "SmoothRms",
    )

    // Brief natural blink near the end of each 3.8s cycle
    val blinkAmount = when {
        blinkCycle in 0.91f..0.95f -> (blinkCycle - 0.91f) / 0.04f
        blinkCycle in 0.95f..0.99f -> (0.99f - blinkCycle) / 0.04f
        else -> 0f
    }

    val isProcessing = phase == AiAssistantPhase.PROCESSING || phase == AiAssistantPhase.SENDING
    val isListening = phase == AiAssistantPhase.LISTENING
    val mouthOpenAmount = when {
        isTtsSpeaking -> (0.35f + 0.65f * abs(sin(speechWave)) * (0.7f + 0.3f * sin(speechWave * 1.7f)))
        isListening -> 0.12f + smoothRms * 0.25f
        else -> 0.08f
    }
    val eyeLookX = if (isProcessing) sin(breathPhase) * 0.055f else 0f

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val baseRadius = minOf(w, h) * 0.44f

        // 1. Subtle reactive halo ring around the matrix face
        val haloScale = 1.0f + sin(breathPhase) * 0.025f + smoothRms * 0.14f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    accentColor.copy(alpha = if (isListening || isTtsSpeaking) 0.22f else 0.12f),
                    Color.Transparent,
                ),
                center = Offset(cx, cy),
                radius = baseRadius * 1.38f * haloScale,
            ),
            radius = baseRadius * 1.38f * haloScale,
            center = Offset(cx, cy),
        )

        // 2. Sculptural Dot-Matrix Human Face Grid
        val cols = 29
        val rows = 33
        val gridWidth = w * 0.84f
        val gridHeight = h * 0.88f
        val startX = (w - gridWidth) / 2f
        val startY = (h - gridHeight) / 2f
        val stepX = gridWidth / (cols - 1)
        val stepY = gridHeight / (rows - 1)
        val baseDotRadius = minOf(stepX, stepY) * 0.34f

        for (r in 0 until rows) {
            // Normalized coordinates: nx in [-1, 1], ny in [-1, 1]
            val ny = (r.toFloat() / (rows - 1)) * 2f - 1f

            // Human head silhouette half-width curve (wider at temples/cheekbones, tapered at chin, subtle neck)
            val headHalfWidth = when {
                ny < -0.78f -> {
                    val t = ((-0.78f - ny) / 0.22f).coerceIn(0f, 1f)
                    0.56f * cos(t * (PI / 2.0)).toFloat()
                }
                ny < 0.15f -> {
                    // Cranium to cheekbones
                    val t = (ny + 0.78f) / 0.93f
                    0.56f + 0.22f * sin(t * PI).toFloat()
                }
                ny < 0.72f -> {
                    // Jawline tapering down toward chin
                    val t = (ny - 0.15f) / 0.57f
                    0.74f * (1f - 0.66f * t * t)
                }
                ny < 0.84f -> {
                    // Chin curve
                    val t = (ny - 0.72f) / 0.12f
                    0.26f * (1f - 0.35f * t)
                }
                else -> {
                    // Subtle neck matrix silhouette
                    0.24f
                }
            }

            for (c in 0 until cols) {
                val nx = (c.toFloat() / (cols - 1)) * 2f - 1f
                val absX = abs(nx)

                if (absX > headHalfWidth) continue

                val px = startX + c * stepX
                val py = startY + r * stepY

                // Base luminance gives 3D shading across forehead, cheeks, and outer face rim
                val rimFactor = (absX / headHalfWidth.coerceAtLeast(0.01f)).coerceIn(0f, 1f)
                var intensity = 0.22f + 0.26f * (1f - rimFactor * rimFactor)
                var dotColor = accentColor
                var radiusMultiplier = 0.78f

                // Outer face edge contour highlight
                if (rimFactor > 0.83f && ny < 0.82f) {
                    intensity = max(intensity, 0.55f)
                    radiusMultiplier = 0.92f
                }

                // Eyebrows
                val browY = -0.34f + (if (isProcessing) 0.02f * (1f - absX) else -0.015f)
                val onBrow = absX in 0.14f..0.48f && abs(ny - browY) < 0.048f
                if (onBrow) {
                    intensity = max(intensity, 0.72f)
                    radiusMultiplier = max(radiusMultiplier, 1.02f)
                }

                // Eyes (left at x = -0.30, right at x = +0.30, y = -0.17)
                val eyeCenterY = -0.17f
                val leftEyeDist = hypot((nx - (-0.30f + eyeLookX)) / 0.15f, (ny - eyeCenterY) / (0.095f * (1f - blinkAmount * 0.85f).coerceAtLeast(0.18f)))
                val rightEyeDist = hypot((nx - (0.30f + eyeLookX)) / 0.15f, (ny - eyeCenterY) / (0.095f * (1f - blinkAmount * 0.85f).coerceAtLeast(0.18f)))
                val eyeDist = minOf(leftEyeDist, rightEyeDist)
                if (eyeDist < 1.05f) {
                    val eyeGlow = (1f - eyeDist / 1.05f).coerceIn(0f, 1f)
                    intensity = max(intensity, 0.65f + 0.35f * eyeGlow)
                    dotColor = Color.White
                    radiusMultiplier = max(radiusMultiplier, 1.15f + 0.2f * eyeGlow)
                }

                // Nose bridge & tip
                val onNose = absX < 0.065f && ny in -0.10f..0.16f
                if (onNose) {
                    intensity = max(intensity, 0.56f)
                    radiusMultiplier = max(radiusMultiplier, 0.95f)
                }

                // Cheekbone soft luminance
                val leftCheek = hypot((nx + 0.42f) / 0.20f, (ny - 0.08f) / 0.16f)
                val rightCheek = hypot((nx - 0.42f) / 0.20f, (ny - 0.08f) / 0.16f)
                if (minOf(leftCheek, rightCheek) < 1f) {
                    intensity = max(intensity, 0.48f)
                }

                // Expressive Lip-Sync Mouth (centered at y = 0.36)
                val mouthHalfW = 0.27f + (if (isTtsSpeaking) 0.04f * mouthOpenAmount else 0f)
                if (absX <= mouthHalfW) {
                    val smileCurve = if (isProcessing) 0f else -0.045f * (absX / mouthHalfW) * (absX / mouthHalfW)
                    val mouthCenterY = 0.36f + smileCurve
                    val mouthHalfH = 0.035f + mouthOpenAmount * 0.11f * cos((absX / mouthHalfW) * (PI / 2.0)).toFloat()
                    val distMouthY = abs(ny - mouthCenterY)
                    if (distMouthY <= mouthHalfH) {
                        val isLipBorder = distMouthY >= mouthHalfH * 0.42f || mouthOpenAmount < 0.16f
                        if (isLipBorder) {
                            intensity = 0.96f
                            dotColor = if (isTtsSpeaking) Color.White else accentColor
                            radiusMultiplier = 1.24f
                        } else {
                            // Inner glowing voice cavity when speaking
                            intensity = 0.55f + 0.35f * mouthOpenAmount
                            dotColor = accentColor
                            radiusMultiplier = 0.85f
                        }
                    }
                }

                // Processing vertical neural scanline wave across the matrix
                if (isProcessing) {
                    val scanDist = abs(ny - scanPhase)
                    if (scanDist < 0.22f) {
                        val boost = (1f - scanDist / 0.22f) * 0.48f
                        intensity = (intensity + boost).coerceAtMost(1f)
                        radiusMultiplier += boost * 0.45f
                    }
                }

                // Subtle idle breathing shimmer
                val shimmer = 0.05f * sin(breathPhase + r * 0.35f + c * 0.25f)
                val finalAlpha = (intensity + shimmer).coerceIn(0.12f, 1f)

                drawCircle(
                    color = dotColor.copy(alpha = finalAlpha),
                    radius = baseDotRadius * radiusMultiplier,
                    center = Offset(px, py),
                )
            }
        }
    }
}
