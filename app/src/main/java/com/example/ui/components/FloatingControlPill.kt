package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.SignalSuccessGreen
import com.example.ui.theme.VoiceActiveCoral
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Single compact draggable & edge-hideable floating control pill for Antigrafity Remote.
 * Provides quick access to Back/Home, Voice Command, Local Notifications, Refresh, and Edge Tuck.
 */
@Composable
fun FloatingControlPill(
    isExpanded: Boolean,
    isTucked: Boolean,
    canGoBack: Boolean,
    isVoiceActive: Boolean,
    notificationsEnabled: Boolean,
    onToggleExpand: () -> Unit,
    onTuck: () -> Unit,
    onUntuck: () -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onVoiceClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onRefreshClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .navigationBarsPadding(),
    ) {
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()

        val edgeMarginPx = with(density) { 12.dp.toPx() }
        val collapsedPillWidthPx = with(density) { 56.dp.toPx() }
        val collapsedPillHeightPx = with(density) { 56.dp.toPx() }
        val expandedWidthPx = with(density) { 296.dp.toPx() }
        val expandedHeightPx = with(density) { 120.dp.toPx() }
        val tuckedWidthPx = with(density) { 30.dp.toPx() }

        val maxWidthPx = constraints.maxWidth.toFloat()
        val maxHeightPx = constraints.maxHeight.toFloat()

        var snappedToRight by remember { mutableStateOf(true) }
        val offsetX = remember { Animatable(0f) }
        val offsetY = remember { Animatable(0f) }
        var initialized by remember { mutableStateOf(false) }

        LaunchedEffect(maxWidthPx, maxHeightPx) {
            if (!initialized && maxWidthPx > 0f && maxHeightPx > 0f) {
                offsetX.snapTo((maxWidthPx - collapsedPillWidthPx - edgeMarginPx).coerceAtLeast(edgeMarginPx))
                offsetY.snapTo((maxHeightPx * 0.72f).coerceIn(edgeMarginPx, (maxHeightPx - collapsedPillHeightPx - edgeMarginPx).coerceAtLeast(edgeMarginPx)))
                initialized = true
            }
        }

        // Re-align X smoothly whenever expanded or tucked state changes
        LaunchedEffect(isExpanded, isTucked, snappedToRight, maxWidthPx, maxHeightPx) {
            if (!initialized || maxWidthPx <= 0f) return@LaunchedEffect
            val targetX = when {
                isTucked && snappedToRight -> (maxWidthPx - tuckedWidthPx).coerceAtLeast(0f)
                isTucked -> 0f
                isExpanded -> {
                    val clampedRight = (maxWidthPx - expandedWidthPx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
                    if (snappedToRight) clampedRight else edgeMarginPx
                }
                snappedToRight -> (maxWidthPx - collapsedPillWidthPx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
                else -> edgeMarginPx
            }
            val currentHeight = if (isExpanded) expandedHeightPx else collapsedPillHeightPx
            val maxY = (maxHeightPx - currentHeight - edgeMarginPx).coerceAtLeast(edgeMarginPx)
            val targetY = offsetY.value.coerceIn(edgeMarginPx, maxY)

            launch {
                offsetX.animateTo(
                    targetValue = targetX,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                )
            }
            if (targetY != offsetY.value) {
                launch {
                    offsetY.animateTo(
                        targetValue = targetY,
                        animationSpec = spring(stiffness = Spring.StiffnessMedium),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt()) }
                .pointerInput(isExpanded, isTucked, maxWidthPx, maxHeightPx) {
                    detectDragGestures(
                        onDragEnd = {
                            if (isExpanded) return@detectDragGestures
                            val currentWidth = if (isTucked) tuckedWidthPx else collapsedPillWidthPx
                            val centerX = offsetX.value + currentWidth / 2f
                            snappedToRight = centerX >= maxWidthPx / 2f
                            val snapTargetX = when {
                                isTucked && snappedToRight -> (maxWidthPx - tuckedWidthPx).coerceAtLeast(0f)
                                isTucked -> 0f
                                snappedToRight -> (maxWidthPx - collapsedPillWidthPx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
                                else -> edgeMarginPx
                            }
                            scope.launch {
                                offsetX.animateTo(
                                    targetValue = snapTargetX,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMediumLow,
                                    ),
                                )
                            }
                        },
                        onDrag = { change, dragAmount ->
                            if (isExpanded) return@detectDragGestures
                            change.consume()
                            val currentWidth = if (isTucked) tuckedWidthPx else collapsedPillWidthPx
                            val maxX = (maxWidthPx - currentWidth).coerceAtLeast(0f)
                            val maxY = (maxHeightPx - collapsedPillHeightPx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
                            scope.launch {
                                offsetX.snapTo((offsetX.value + dragAmount.x).coerceIn(0f, maxX))
                                offsetY.snapTo((offsetY.value + dragAmount.y).coerceIn(edgeMarginPx, maxY))
                            }
                        },
                    )
                },
        ) {
            AnimatedContent(
                targetState = Triple(isTucked, isExpanded, snappedToRight),
                transitionSpec = {
                    (fadeIn() + scaleIn(initialScale = 0.9f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.9f))
                },
                label = "FloatingControlPillTransition",
            ) { (tucked, expanded, rightSide) ->
                when {
                    tucked -> TuckedEdgeHandle(
                        onRightEdge = rightSide,
                        onUntuck = onUntuck,
                    )

                    expanded -> ExpandedRemoteDock(
                        canGoBack = canGoBack,
                        isVoiceActive = isVoiceActive,
                        notificationsEnabled = notificationsEnabled,
                        onBack = onBack,
                        onHome = onHome,
                        onVoiceClick = onVoiceClick,
                        onNotificationsClick = onNotificationsClick,
                        onRefreshClick = onRefreshClick,
                        onTuck = onTuck,
                        onClose = onToggleExpand,
                    )

                    else -> CompactFloatingFab(
                        isVoiceActive = isVoiceActive,
                        notificationsEnabled = notificationsEnabled,
                        onClick = onToggleExpand,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactFloatingFab(
    isVoiceActive: Boolean,
    notificationsEnabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(56.dp)
            .testTag("floating_control_fab"),
        shape = CircleShape,
        color = if (isVoiceActive) VoiceActiveCoral else MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        contentColor = if (isVoiceActive) Color.White else MaterialTheme.colorScheme.primary,
        border = BorderStroke(
            width = 1.5.dp,
            color = if (isVoiceActive) VoiceActiveCoral else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
        ),
        shadowElevation = 10.dp,
        tonalElevation = 6.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (isVoiceActive) Icons.Rounded.Mic else Icons.Rounded.Tune,
                contentDescription = stringResource(R.string.cd_floating_menu_toggle),
                modifier = Modifier.size(24.dp),
            )
            if (notificationsEnabled && !isVoiceActive) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(SignalSuccessGreen),
                )
            }
        }
    }
}

@Composable
private fun TuckedEdgeHandle(
    onRightEdge: Boolean,
    onUntuck: () -> Unit,
) {
    val handleShape = if (onRightEdge) {
        RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)
    } else {
        RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp)
    }

    Surface(
        onClick = onUntuck,
        modifier = Modifier
            .width(30.dp)
            .height(56.dp)
            .testTag("floating_control_untuck_handle"),
        shape = handleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        shadowElevation = 6.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (onRightEdge) Icons.Rounded.ChevronLeft else Icons.Rounded.ChevronRight,
                contentDescription = stringResource(R.string.cd_untuck_pill),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ExpandedRemoteDock(
    canGoBack: Boolean,
    isVoiceActive: Boolean,
    notificationsEnabled: Boolean,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onVoiceClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onTuck: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .width(296.dp)
            .testTag("floating_control_dock"),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        shadowElevation = 14.dp,
        tonalElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                PillQuickAction(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    label = stringResource(R.string.action_back),
                    contentDescription = stringResource(R.string.cd_action_back),
                    enabled = canGoBack,
                    onClick = onBack,
                    testTag = "pill_action_back",
                )
                PillQuickAction(
                    icon = Icons.Rounded.Home,
                    label = stringResource(R.string.action_home),
                    contentDescription = stringResource(R.string.cd_action_home),
                    onClick = onHome,
                    testTag = "pill_action_home",
                )
                PillQuickAction(
                    icon = Icons.Rounded.Mic,
                    label = stringResource(R.string.action_voice),
                    contentDescription = stringResource(R.string.cd_action_voice),
                    highlighted = isVoiceActive,
                    highlightColor = VoiceActiveCoral,
                    onClick = onVoiceClick,
                    testTag = "pill_action_voice",
                )
                PillQuickAction(
                    icon = if (notificationsEnabled) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsNone,
                    label = stringResource(R.string.action_notif),
                    contentDescription = stringResource(R.string.cd_action_notifications),
                    showBadgeDot = notificationsEnabled,
                    onClick = onNotificationsClick,
                    testTag = "pill_action_notifications",
                )
                PillQuickAction(
                    icon = Icons.Rounded.Refresh,
                    label = stringResource(R.string.action_refresh),
                    contentDescription = stringResource(R.string.cd_action_refresh),
                    onClick = onRefreshClick,
                    testTag = "pill_action_refresh",
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onTuck)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("pill_action_tuck"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VisibilityOff,
                        contentDescription = stringResource(R.string.cd_tuck_pill),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.action_hide),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onClose)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("pill_action_collapse"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.cd_floating_menu_toggle),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.action_close),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun PillQuickAction(
    icon: ImageVector,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    testTag: String,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    highlightColor: Color = MaterialTheme.colorScheme.primary,
    showBadgeDot: Boolean = false,
) {
    val contentAlpha = if (enabled) 1f else 0.42f
    Column(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Surface(
                shape = CircleShape,
                color = if (highlighted) highlightColor else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = contentAlpha),
                modifier = Modifier.size(38.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = contentDescription,
                        tint = if (highlighted) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (showBadgeDot) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(SignalSuccessGreen),
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
            maxLines = 1,
        )
    }
}
