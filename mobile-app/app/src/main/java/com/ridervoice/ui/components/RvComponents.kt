package com.ridervoice.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            .height(RvTouchTarget.primary),
        shape = RoundedCornerShape(RvRadius.md),
        colors = ButtonDefaults.buttonColors(
            containerColor = NeonOrange,
            contentColor = Color.White,
            disabledContainerColor = Gunmetal,
            disabledContentColor = TextSecondary
        )
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = Color.White,
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
            .height(RvTouchTarget.min),
        shape = RoundedCornerShape(RvRadius.md),
        border = ButtonDefaults.outlinedButtonBorder.copy(
            brush = androidx.compose.ui.graphics.SolidColor(if (destructive) AlertRed else BorderColor)
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (destructive) AlertRed else TextPrimary,
            disabledContentColor = TextSecondary
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
                    tint = if (destructive) AlertRed else TextPrimary,
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
    val (bgColor, textColor, label) = when (status.uppercase()) {
        "HOST", "OWNER" -> Triple(AmberContainer, NeonOrange, "Host")
        "JOINED", "ACCEPTED" -> Triple(TechGreen.copy(alpha = 0.15f), TechGreen, "Joined")
        "INVITED", "PENDING" -> Triple(WarningAmber.copy(alpha = 0.15f), WarningAmber, "Invited")
        "DECLINED" -> Triple(Gunmetal, TextSecondary, "Declined")
        "LEFT" -> Triple(Gunmetal, TextSecondary, "Left")
        else -> Triple(Gunmetal, TextSecondary, status)
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(RvRadius.pill),
        modifier = modifier
    ) {
        Text(
            text = label,
            color = textColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = RvSpacing.sm, vertical = RvSpacing.xs)
        )
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
        targetValue = if (isSpeaking) TechGreen else Color.Transparent,
        label = "speakingRing"
    )

    Box(
        modifier = modifier
            .size(size)
            .border(2.dp, ringColor, CircleShape)
            .padding(if (isSpeaking) 2.dp else 0.dp)
            .clip(CircleShape)
            .background(DarkSlate),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials.take(2).uppercase(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        if (isMuted) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(Gunmetal),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MicOff,
                    contentDescription = "Muted",
                    tint = TextSecondary,
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
            .clip(RoundedCornerShape(RvRadius.sm))
            .background(DarkSlate)
            .padding(horizontal = RvSpacing.md, vertical = RvSpacing.sm),
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
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                if (status != null) {
                    Spacer(modifier = Modifier.width(RvSpacing.sm))
                    StatusChip(status = status)
                }
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
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
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(RvRadius.md),
        colors = CardDefaults.cardColors(containerColor = DarkSlate)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(RvSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "RIDE CODE",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(RvSpacing.xs))

            Text(
                text = formattedCode.uppercase(),
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 32.sp),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = NeonOrange,
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
                        .height(RvTouchTarget.min),
                    shape = RoundedCornerShape(RvRadius.sm),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(BorderColor)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy code",
                        modifier = Modifier.size(18.dp),
                        tint = TextPrimary
                    )
                    Spacer(modifier = Modifier.width(RvSpacing.xs))
                    Text("Copy", color = TextPrimary)
                }

                Button(
                    onClick = onShare,
                    modifier = Modifier
                        .weight(1f)
                        .height(RvTouchTarget.min),
                    shape = RoundedCornerShape(RvRadius.sm),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonOrange)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share link",
                        modifier = Modifier.size(18.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(RvSpacing.xs))
                    Text("Share", color = Color.White)
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
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(RvRadius.md),
        colors = CardDefaults.cardColors(containerColor = DarkSlate)
    ) {
        Column(modifier = Modifier.padding(RvSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = NeonOrange,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(RvSpacing.sm))
                }
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
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
        color = HazardContainer,
        shape = RoundedCornerShape(RvRadius.sm),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = RvSpacing.md, vertical = RvSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = AlertRed,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(RvSpacing.sm))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = AlertRed,
                modifier = Modifier.weight(1f)
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry) {
                    Text("Retry", color = AlertRed, fontWeight = FontWeight.Bold)
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
    icon: ImageVector = Icons.Default.Info,
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
            tint = TextSecondary,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(RvSpacing.md))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(RvSpacing.xs))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        if (actionText != null && onAction != null) {
            Spacer(modifier = Modifier.height(RvSpacing.md))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = NeonOrange)
            ) {
                Text(actionText, color = Color.White)
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
            .background(Gunmetal.copy(alpha = 0.5f))
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "Cancel",
    destructive: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (destructive) AlertRed else TextPrimary
            )
        },
        text = {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (destructive) AlertRed else NeonOrange
                )
            ) {
                Text(confirmLabel, color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissLabel, color = TextPrimary)
            }
        },
        containerColor = DarkSlate
    )
}
