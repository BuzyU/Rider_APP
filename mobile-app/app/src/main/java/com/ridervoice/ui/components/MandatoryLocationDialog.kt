package com.ridervoice.ui.components

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ridervoice.R
import com.ridervoice.permissions.LocationGateStatus
import com.ridervoice.permissions.MandatoryLocationGate
import com.ridervoice.ui.theme.Ink
import com.ridervoice.ui.theme.InkSecondary
import com.ridervoice.ui.theme.PaperOnPop
import com.ridervoice.ui.theme.PaperRaised
import com.ridervoice.ui.theme.Pop
import com.ridervoice.ui.theme.RvIcons
import com.ridervoice.ui.theme.RvRadius
import com.ridervoice.ui.theme.RvSpacing
import com.ridervoice.ui.theme.RvTouchTarget
import com.ridervoice.ui.theme.StatusWarn
import com.ridervoice.ui.theme.StatusWarnContainer

@Composable
fun rememberMandatoryLocationStatus(): Pair<LocationGateStatus, () -> Unit> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var status by remember { mutableStateOf(MandatoryLocationGate.evaluate(context)) }
    val refresh = { status = MandatoryLocationGate.evaluate(context) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                status = MandatoryLocationGate.evaluate(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return status to refresh
}

@Composable
fun MandatoryLocationGateDialog(
    status: LocationGateStatus,
    onDismiss: () -> Unit,
    onReady: () -> Unit,
    dismissLabel: String? = null
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val next = MandatoryLocationGate.evaluate(context)
        if (next == LocationGateStatus.READY) {
            onReady()
        } else if (next == LocationGateStatus.LOCATION_SERVICES_OFF) {
            runCatching { context.startActivity(MandatoryLocationGate.buildLocationSettingsIntent()) }
        }
    }

    val title = if (status == LocationGateStatus.LOCATION_SERVICES_OFF) {
        stringResource(R.string.location_gate_gps_off_title)
    } else {
        stringResource(R.string.location_gate_perm_title)
    }
    val body = if (status == LocationGateStatus.LOCATION_SERVICES_OFF) {
        stringResource(R.string.location_gate_gps_off_body)
    } else {
        stringResource(R.string.location_gate_perm_body)
    }
    val ctaLabel = if (status == LocationGateStatus.LOCATION_SERVICES_OFF) {
        stringResource(R.string.location_gate_open_gps_cta)
    } else {
        stringResource(R.string.location_gate_allow_cta)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = PaperRaised,
        icon = {
            Icon(
                imageVector = RvIcons.LocationOn,
                contentDescription = null,
                tint = Pop,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Ink
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
                onClick = {
                    val latest = MandatoryLocationGate.evaluate(context)
                    when (latest) {
                        LocationGateStatus.READY -> onReady()
                        LocationGateStatus.MISSING_PERMISSION -> permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                        LocationGateStatus.LOCATION_SERVICES_OFF -> {
                            runCatching {
                                context.startActivity(MandatoryLocationGate.buildLocationSettingsIntent())
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Pop,
                    contentColor = PaperOnPop
                ),
                shape = RoundedCornerShape(RvRadius.pill),
                modifier = Modifier
                    .heightIn(min = RvTouchTarget.min)
                    .testTag("location_gate_confirm_btn")
            ) {
                Text(text = ctaLabel, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = RvTouchTarget.min)
            ) {
                Text(
                    text = dismissLabel ?: stringResource(R.string.action_cancel),
                    color = InkSecondary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    )
}

@Composable
fun MandatoryLocationRequiredBanner(
    status: LocationGateStatus,
    onActionClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (status == LocationGateStatus.READY) return

    val msg = if (status == LocationGateStatus.LOCATION_SERVICES_OFF) {
        stringResource(R.string.location_gate_gps_off_body)
    } else {
        stringResource(R.string.location_gate_perm_body)
    }
    val cta = if (status == LocationGateStatus.LOCATION_SERVICES_OFF) {
        stringResource(R.string.location_gate_open_gps_cta)
    } else {
        stringResource(R.string.location_gate_allow_cta)
    }

    Surface(
        color = StatusWarnContainer,
        shape = RoundedCornerShape(RvRadius.md),
        border = BorderStroke(1.dp, StatusWarn.copy(alpha = 0.45f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("mandatory_location_banner")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = RvSpacing.md, vertical = RvSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RvSpacing.sm)
        ) {
            Icon(
                imageVector = RvIcons.LocationOn,
                contentDescription = null,
                tint = StatusWarn,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.location_gate_required_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(RvSpacing.xs))
            TextButton(
                onClick = onActionClick,
                modifier = Modifier
                    .heightIn(min = RvTouchTarget.min)
                    .testTag("mandatory_location_banner_cta")
            ) {
                Text(
                    text = cta,
                    color = StatusWarn,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}
