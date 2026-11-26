package com.ridervoice.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ridervoice.ui.theme.Brick
import com.ridervoice.ui.theme.BrickContainer
import com.ridervoice.ui.theme.Ink
import com.ridervoice.ui.theme.InkHairline
import com.ridervoice.ui.theme.PaperRaised
import com.ridervoice.ui.theme.Pop
import com.ridervoice.ui.theme.StatusOk
import com.ridervoice.ui.theme.StatusOkContainer
import com.ridervoice.ui.theme.StatusWarn
import com.ridervoice.ui.theme.StatusWarnContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

enum class RvFeedbackKind {
    Info,
    Success,
    Warning,
    Error
}

@Stable
class RvFeedbackController(
    val snackbarHostState: SnackbarHostState,
    private val scope: CoroutineScope
) {
    var currentKind by mutableStateOf(RvFeedbackKind.Info)
        private set

    private var activeMessage: String? = null

    fun showInfo(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) =
        show(RvFeedbackKind.Info, message, actionLabel, onAction)

    fun showSuccess(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) =
        show(RvFeedbackKind.Success, message, actionLabel, onAction)

    fun showWarning(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) =
        show(RvFeedbackKind.Warning, message, actionLabel, onAction)

    fun showError(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) =
        show(RvFeedbackKind.Error, message, actionLabel, onAction)

    fun show(
        kind: RvFeedbackKind,
        message: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
        duration: SnackbarDuration = SnackbarDuration.Short
    ) {
        if (snackbarHostState.currentSnackbarData != null && activeMessage == message) {
            return
        }
        activeMessage = message
        currentKind = kind
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                withDismissAction = false,
                duration = duration
            )
            if (activeMessage == message) {
                activeMessage = null
            }
            if (result == SnackbarResult.ActionPerformed) {
                onAction?.invoke()
            }
        }
    }

    fun dismissCurrent() {
        activeMessage = null
        snackbarHostState.currentSnackbarData?.dismiss()
    }
}

val LocalRvFeedback = staticCompositionLocalOf {
    RvFeedbackController(
        snackbarHostState = SnackbarHostState(),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    )
}

@Composable
fun rememberRvFeedbackController(): RvFeedbackController {
    val hostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    return remember(hostState, scope) {
        RvFeedbackController(snackbarHostState = hostState, scope = scope)
    }
}

@Composable
fun RvSnackbarHost(
    controller: RvFeedbackController,
    modifier: Modifier = Modifier,
    suppressed: Boolean = false
) {
    LaunchedEffect(suppressed) {
        if (suppressed) {
            controller.dismissCurrent()
        }
    }

    if (suppressed) return

    SnackbarHost(
        hostState = controller.snackbarHostState,
        modifier = modifier
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("rv_snackbar_host"),
        snackbar = { data ->
            RvFeedbackBanner(data = data, kind = controller.currentKind)
        }
    )
}

@Composable
internal fun RvFeedbackBanner(
    data: SnackbarData,
    kind: RvFeedbackKind,
    modifier: Modifier = Modifier
) {
    val (container, accent, icon) = when (kind) {
        RvFeedbackKind.Info -> Triple(PaperRaised, InkHairline, Icons.Default.Info)
        RvFeedbackKind.Success -> Triple(StatusOkContainer, StatusOk, Icons.Default.CheckCircle)
        RvFeedbackKind.Warning -> Triple(StatusWarnContainer, StatusWarn, Icons.Default.Warning)
        RvFeedbackKind.Error -> Triple(BrickContainer, Brick, Icons.Default.ErrorOutline)
    }
    val actionTint = if (kind == RvFeedbackKind.Info) Pop else accent

    Surface(
        color = container,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.25.dp, accent),
        shadowElevation = 4.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag("rv_snackbar_${kind.name.lowercase()}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (kind == RvFeedbackKind.Info) Pop else accent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = data.visuals.message,
                    color = Ink,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            data.visuals.actionLabel?.let { label ->
                TextButton(
                    onClick = { data.performAction() },
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("rv_snackbar_action")
                ) {
                    Text(
                        text = label,
                        color = actionTint,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
