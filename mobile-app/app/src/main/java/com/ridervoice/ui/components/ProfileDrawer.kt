package com.ridervoice.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.ui.theme.*

@Composable
fun ProfileDrawer(
    riderHandle: String = "@RIDER",
    riderName: String = "Active Transceiver",
    status: String = "Online",
    totalMiles: String = "1,204",
    totalRides: String = "45",
    onAccountClick: () -> Unit = {},
    onSettingsClick: () -> Unit,
    onDeviceSetupClick: () -> Unit,
    onLogoutClick: () -> Unit
) {
    val isDark = ThemeState.isDarkTheme

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(GraphiteBase)
            .border(1.dp, BorderColor)
            .padding(24.dp)
    ) {
        // Avatar / Call-Sign Section (Clickable to open Account)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onAccountClick() }
                .padding(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(DarkSlate)
                    .border(2.dp, ElectricCyan, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "Profile Avatar",
                    tint = ElectricCyan,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = riderHandle.uppercase(),
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(TechGreen))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = status,
                        color = TechGreen,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
        Divider(color = BorderColor, thickness = 1.dp)
        Spacer(modifier = Modifier.height(16.dp))

        // Transceiver / Ride Telemetry Section
        Text(
            text = "TELEMETRY LOGBOOK",
            color = NeonOrange,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(14.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StatItem(value = totalMiles, label = "Miles")
            StatItem(value = totalRides, label = "Rides")
            StatItem(value = "CH-01", label = "Default")
        }

        Spacer(modifier = Modifier.height(28.dp))
        Divider(color = BorderColor, thickness = 1.dp)
        Spacer(modifier = Modifier.height(16.dp))

        // Menu Items
        DrawerMenuItem(
            icon = Icons.Default.AccountCircle,
            text = "Operator Dossier (Account)",
            tint = ElectricCyan,
            onClick = onAccountClick
        )
        DrawerMenuItem(
            icon = Icons.Default.Settings,
            text = "Transceiver Settings",
            onClick = onSettingsClick
        )
        DrawerMenuItem(
            icon = Icons.Default.Headset,
            text = "Audio & Helmet Setup",
            onClick = onDeviceSetupClick
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        DrawerMenuItem(
            icon = Icons.Default.Logout,
            text = "Disengage (Sign Out)",
            tint = AlertRed,
            onClick = onLogoutClick
        )
    }
}

@Composable
private fun StatItem(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = TextPrimary,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label.uppercase(),
            color = TextSecondary,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun DrawerMenuItem(
    icon: ImageVector,
    text: String,
    tint: Color = TextPrimary,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = text,
            color = tint,
            style = MaterialTheme.typography.labelLarge,
            fontSize = 14.sp
        )
    }
}
