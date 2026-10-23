package com.ridervoice.ui.screens.room

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ridervoice.ui.theme.*

@Composable
fun AudioRouteSheet(
    onSelectRoute: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Audio Device",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Select where to route call audio:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(4.dp))

                AudioRouteOption(
                    title = "Bluetooth Headset / Intercom",
                    subtitle = "Cardo, Sena, AirPods, Helmet kit",
                    icon = Icons.Default.Bluetooth,
                    tint = ElectricCyan,
                    onClick = {
                        onSelectRoute("BLUETOOTH")
                        onDismiss()
                    }
                )

                AudioRouteOption(
                    title = "Phone Speaker",
                    subtitle = "Loudspeaker for group hearing",
                    icon = Icons.Default.VolumeUp,
                    tint = NeonOrange,
                    onClick = {
                        onSelectRoute("SPEAKER")
                        onDismiss()
                    }
                )

                AudioRouteOption(
                    title = "Phone Earpiece / Wired",
                    subtitle = "Handset receiver or 3.5mm wired headset",
                    icon = Icons.Default.PhoneInTalk,
                    tint = TextSecondary,
                    onClick = {
                        onSelectRoute("WIRED")
                        onDismiss()
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("CLOSE", color = ElectricCyan, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = DarkSlate
    )
}

@Composable
private fun AudioRouteOption(
    title: String,
    subtitle: String,
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Surface(
        color = Gunmetal,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
