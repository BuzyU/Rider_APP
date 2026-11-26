package com.ridervoice.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.R
import com.ridervoice.ui.theme.*

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null
) {
    var lastClickTime by remember { mutableLongStateOf(0L) }

    Button(
        onClick = {
            val now = System.currentTimeMillis()
            if (now - lastClickTime >= 1000L && !loading) {
                lastClickTime = now
                onClick()
            }
        },
        enabled = enabled && !loading,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = RvTouchTarget.primary),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Pop,
            contentColor = PaperOnPop,
            disabledContainerColor = PaperSunken,
            disabledContentColor = InkSecondary
        )
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = PaperOnPop,
                strokeWidth = 2.5.dp
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (leadingIcon != null) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(RvSpacing.sm))
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    destructive: Boolean = false
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = RvTouchTarget.min),
        shape = RoundedCornerShape(16.dp),
        border = ButtonDefaults.outlinedButtonBorder.copy(
            brush = SolidColor(if (destructive) Brick else InkBorderStrong)
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (destructive) BrickContainer.copy(alpha = 0.35f) else PaperRaised,
            contentColor = if (destructive) Brick else Ink,
            disabledContentColor = InkSecondary
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = if (destructive) Brick else Ink,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(RvSpacing.sm))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun StatusChip(
    status: String,
    modifier: Modifier = Modifier
) {
    val upper = status.uppercase()
    val bgColor: Color
    val textColor: Color
    val label: String
    val icon: ImageVector?

    when (upper) {
        "HOST", "OWNER" -> {
            bgColor = PopContainer
            textColor = Pop
            label = stringResource(R.string.status_host)
            icon = RvIcons.Star
        }
        "JOINED", "ACCEPTED", "READY" -> {
            bgColor = PaperSunken
            textColor = Ink
            label = if (upper == "READY") stringResource(R.string.status_ready) else stringResource(R.string.lobby_section_joined)
            icon = RvIcons.Check
        }
        "INVITED", "PENDING" -> {
            bgColor = PaperSunken
            textColor = InkSecondary
            label = stringResource(R.string.lobby_section_invited)
            icon = RvIcons.Schedule
        }
        "DECLINED" -> {
            bgColor = PaperSunken
            textColor = InkSecondary
            label = stringResource(R.string.lobby_section_declined)
            icon = RvIcons.Close
        }
        "LEFT" -> {
            bgColor = PaperSunken
            textColor = InkSecondary
            label = stringResource(R.string.status_left)
            icon = null
        }
        else -> {
            bgColor = PaperSunken
            textColor = InkSecondary
            label = status
            icon = null
        }
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(RvRadius.pill),
        border = BorderStroke(0.75.dp, if (textColor == Pop) Pop.copy(alpha = 0.45f) else InkHairline),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = RvSpacing.sm, vertical = RvSpacing.xs)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
            }
            Text(
                text = label,
                color = textColor,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun RiderAvatar(
    initials: String,
    modifier: Modifier = Modifier,
    isSpeaking: Boolean = false,
    isMuted: Boolean = false,
    size: Dp = 44.dp
) {
    val ringColor by animateColorAsState(
        targetValue = if (isSpeaking) Pop else Color.Transparent,
        label = "speakingRing"
    )

    Box(
        modifier = modifier
            .size(size)
            .border(2.dp, ringColor, CircleShape)
            .padding(2.dp)
            .clip(CircleShape)
            .background(PaperSunken),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials.take(2).uppercase(),
            modifier = Modifier.testTag("rider_avatar_inner"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Ink
        )

        if (isMuted) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(PaperRaised)
                    .border(0.75.dp, InkHairline, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = RvIcons.MicOff,
                    contentDescription = stringResource(R.string.cd_muted),
                    tint = InkSecondary,
                    modifier = Modifier.size(10.dp)
                )
            }
        }
    }
}

@Composable
fun RiderRow(
    name: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    status: String? = null,
    isSpeaking: Boolean = false,
    isMuted: Boolean = false,
    trailing: @Composable (() -> Unit)? = null
) {
    val initials = name.split(" ")
        .filter { it.isNotBlank() }
        .take(2)
        .mapNotNull { it.firstOrNull()?.toString() }
        .joinToString("")
        .ifBlank { name.take(2) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(PaperRaised)
            .border(1.dp, InkHairline, RoundedCornerShape(20.dp))
            .padding(horizontal = RvSpacing.md, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RiderAvatar(
            initials = initials,
            isSpeaking = isSpeaking,
            isMuted = isMuted
        )

        Spacer(modifier = Modifier.width(RvSpacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .testTag("rider_row_name"),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (status != null) {
                    Spacer(modifier = Modifier.width(RvSpacing.sm))
                    StatusChip(status = status)
                }
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    modifier = Modifier.testTag("rider_row_subtitle"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (trailing != null) {
            Spacer(modifier = Modifier.width(RvSpacing.sm))
            trailing()
        }
    }
}

@Composable
fun CodeCard(
    code: String,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    val formattedCode = if (code.length == 8) {
        "${code.take(4)}  ${code.substring(4)}"
    } else {
        code
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, InkHairline, RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = PaperRaised)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.label_ride_code),
                style = MaterialTheme.typography.labelSmall,
                color = InkSecondary,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(RvSpacing.xs))

            Text(
                text = formattedCode.uppercase(),
                style = MaterialTheme.typography.displayLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = Ink,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(RvSpacing.md))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(RvSpacing.md)
            ) {
                OutlinedButton(
                    onClick = onCopy,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = RvTouchTarget.min),
                    shape = RoundedCornerShape(14.dp),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = SolidColor(InkHairline)
                    )
                ) {
                    Icon(
                        imageVector = RvIcons.ContentCopy,
                        contentDescription = stringResource(R.string.action_copy_code),
                        modifier = Modifier.size(18.dp),
                        tint = Ink
                    )
                    Spacer(modifier = Modifier.width(RvSpacing.xs))
                    Text(stringResource(R.string.action_copy), color = Ink, fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = onShare,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = RvTouchTarget.min),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CharcoalSurface,
                        contentColor = PaperOnPop
                    )
                ) {
                    Icon(
                        imageVector = RvIcons.Share,
                        contentDescription = stringResource(R.string.action_share_link),
                        modifier = Modifier.size(18.dp),
                        tint = PaperOnPop
                    )
                    Spacer(modifier = Modifier.width(RvSpacing.xs))
                    Text(stringResource(R.string.action_share), color = PaperOnPop, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun InfoCard(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    content: @Composable (() -> Unit)? = null
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, InkHairline, RoundedCornerShape(RvRadius.md)),
        shape = RoundedCornerShape(RvRadius.md),
        colors = CardDefaults.cardColors(containerColor = PaperRaised)
    ) {
        Column(modifier = Modifier.padding(RvSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Pop,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(RvSpacing.sm))
                }
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Ink,
                        fontWeight = FontWeight.Bold
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = InkSecondary
                        )
                    }
                }
            }
            if (content != null) {
                Spacer(modifier = Modifier.height(RvSpacing.sm))
                content()
            }
        }
    }
}

@Composable
fun InlineError(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    Surface(
        color = BrickContainer,
        shape = RoundedCornerShape(RvRadius.sm),
        border = BorderStroke(1.dp, Brick),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = RvSpacing.md, vertical = RvSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = RvIcons.Warning,
                contentDescription = null,
                tint = Brick,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(RvSpacing.sm))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = Brick,
                modifier = Modifier.weight(1f)
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.action_retry), color = Brick, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = RvIcons.Info,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = RvSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = InkSecondary,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(RvSpacing.md))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Ink,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(RvSpacing.xs))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = InkSecondary,
            textAlign = TextAlign.Center
        )
        if (actionText != null && onAction != null) {
            Spacer(modifier = Modifier.height(RvSpacing.md))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = Pop)
            ) {
                Text(actionText, color = PaperOnPop)
            }
        }
    }
}

@Composable
fun LoadingSkeleton(
    modifier: Modifier = Modifier,
    height: Dp = 60.dp
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(RvRadius.sm))
            .background(PaperSunken)
    )
}

/** Unified async screen load state for presentation UI. */
sealed interface UiLoadState<out T> {
    data object Loading : UiLoadState<Nothing>
    data class Empty(
        val title: String,
        val body: String,
        val icon: ImageVector = RvIcons.Info,
        val actionText: String? = null,
        val onAction: (() -> Unit)? = null
    ) : UiLoadState<Nothing>
    data class Error(
        val message: String,
        val onRetry: (() -> Unit)? = null
    ) : UiLoadState<Nothing>
    data class Content<T>(val data: T) : UiLoadState<T>
}

/**
 * Renders unified [UiLoadState] transitions with [RvMotion.QUICK] crossfade (or [RvMotion.INSTANT]
 * when reduce-motion is enabled).
 */
@Composable
fun <T> RvStateHost(
    state: UiLoadState<T>,
    modifier: Modifier = Modifier,
    loadingContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("rv_state_loading"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LoadingSkeleton(height = 84.dp)
            LoadingSkeleton(height = 84.dp)
            LoadingSkeleton(height = 84.dp)
        }
    },
    emptyContent: @Composable (UiLoadState.Empty) -> Unit = { empty ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .testTag("rv_state_empty"),
            contentAlignment = Alignment.Center
        ) {
            EmptyState(
                title = empty.title,
                body = empty.body,
                icon = empty.icon,
                actionText = empty.actionText,
                onAction = empty.onAction
            )
        }
    },
    errorContent: @Composable (UiLoadState.Error) -> Unit = { err ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .testTag("rv_state_error"),
            contentAlignment = Alignment.Center
        ) {
            InlineError(
                message = err.message,
                onRetry = err.onRetry
            )
        }
    },
    content: @Composable (T) -> Unit
) {
    val reduceMotion = rememberIsReduceMotionEnabled()
    var lastEmpty by remember { mutableStateOf<UiLoadState.Empty?>(null) }
    var lastError by remember { mutableStateOf<UiLoadState.Error?>(null) }
    if (state is UiLoadState.Empty) lastEmpty = state
    if (state is UiLoadState.Error) lastError = state

    val stateKey = when (state) {
        is UiLoadState.Loading -> 0
        is UiLoadState.Empty -> 1
        is UiLoadState.Error -> 2
        is UiLoadState.Content -> 3
    }

    Crossfade(
        targetState = stateKey,
        animationSpec = tween(
            durationMillis = if (reduceMotion) RvMotion.INSTANT else RvMotion.QUICK,
            easing = RvMotion.Easing
        ),
        modifier = modifier
    ) { key ->
        when (key) {
            0 -> loadingContent()
            1 -> (state as? UiLoadState.Empty ?: lastEmpty)?.let { emptyContent(it) }
            2 -> (state as? UiLoadState.Error ?: lastError)?.let { errorContent(it) }
            else -> (state as? UiLoadState.Content<T>)?.let { content(it.data) }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String? = null,
    destructive: Boolean = false
) {
    val resolvedDismissLabel = dismissLabel ?: stringResource(R.string.action_cancel)
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (destructive) Brick else Ink
            )
        },
        text = {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = InkSecondary
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (destructive) Brick else Pop,
                    contentColor = if (destructive) PaperOnBrick else PaperOnPop
                )
            ) {
                Text(confirmLabel, color = if (destructive) PaperOnBrick else PaperOnPop, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(resolvedDismissLabel, color = Ink)
            }
        },
        containerColor = PaperRaised
    )
}
