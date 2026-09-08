package com.ridervoice.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ridervoice.ui.screens.InviteFriendsContent
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.LobbyViewModel

@Composable
fun AddRidersBottomSheet(
    convoyName: String,
    lobbyViewModel: LobbyViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isDark = ThemeState.isDarkTheme
    var selectedTab by remember { mutableStateOf(0) }

    val shareLink by lobbyViewModel.shareLink.collectAsState()
    val isGeneratingLink by lobbyViewModel.isGeneratingLink.collectAsState()
    val linkError by lobbyViewModel.linkError.collectAsState()

    var isCopied by remember { mutableStateOf(false) }

    LaunchedEffect(selectedTab) {
        if (selectedTab == 1 && shareLink == null) {
            lobbyViewModel.generateShareLink(convoyName)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                color = GraphiteBase,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = false) {}
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp)
                ) {
                    // Drag handle aesthetic
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(4.dp)
                                .background(BorderColor, RoundedCornerShape(2.dp))
                        )
                    }

                    // Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "DISPATCH REINFORCEMENTS",
                                style = MaterialTheme.typography.labelSmall,
                                color = NeonOrange,
                                letterSpacing = 1.5.sp
                            )
                            Text(
                                text = "ADD CONVOY RIDERS",
                                style = MaterialTheme.typography.titleLarge,
                                color = TextPrimary
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                        }
                    }

                    // Tab Bar: "From Squad" vs "Share Link"
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = DarkSlate,
                        contentColor = NeonOrange,
                        divider = { Divider(color = BorderColor, thickness = 1.dp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = {
                                Text(
                                    "FROM SQUAD",
                                    color = if (selectedTab == 0) NeonOrange else TextSecondary,
                                    fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            icon = {
                                Icon(
                                    Icons.Default.Group,
                                    contentDescription = null,
                                    tint = if (selectedTab == 0) NeonOrange else TextSecondary
                                )
                            }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = {
                                Text(
                                    "SHARE LINK",
                                    color = if (selectedTab == 1) NeonOrange else TextSecondary,
                                    fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            icon = {
                                Icon(
                                    Icons.Default.Link,
                                    contentDescription = null,
                                    tint = if (selectedTab == 1) NeonOrange else TextSecondary
                                )
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Tab Content
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 320.dp, max = 500.dp)
                    ) {
                        if (selectedTab == 0) {
                            // Squad List Tab
                            InviteFriendsContent(
                                convoyName = convoyName,
                                onInvitesSent = null, // In bottom sheet, invites are sent directly per row
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            // Share Link Tab
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Surface(
                                    color = DarkSlate,
                                    shape = RoundedCornerShape(12.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.VpnKey,
                                                contentDescription = null,
                                                tint = ElectricCyan,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "24-HOUR SECURE TOKEN LINK",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = ElectricCyan,
                                                letterSpacing = 1.sp
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "Send this deep-link to riders via WhatsApp, SMS, or Telegram. Tapping it tunes their transceivers directly into this convoy without needing a prior squad invite.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = TextSecondary,
                                            fontSize = 13.sp
                                        )
                                    }
                                }

                                if (isGeneratingLink) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(120.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            CircularProgressIndicator(color = NeonOrange, strokeWidth = 3.dp)
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Text(
                                                text = "GENERATING SECURE TOKEN...",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSecondary
                                            )
                                        }
                                    }
                                } else if (shareLink != null) {
                                    val activeLink = shareLink!!

                                    // Token URL Display Box
                                    Surface(
                                        color = Gunmetal,
                                        shape = RoundedCornerShape(8.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, NeonOrange.copy(alpha = 0.5f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = activeLink,
                                                color = TextPrimary,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.weight(1f)
                                            )
                                            IconButton(
                                                onClick = {
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    val clip = ClipData.newPlainText("RiderVoice Convoy Link", activeLink)
                                                    clipboard.setPrimaryClip(clip)
                                                    isCopied = true
                                                    Toast.makeText(context, "Link copied to clipboard", Toast.LENGTH_SHORT).show()
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                                                    contentDescription = "Copy Link",
                                                    tint = if (isCopied) TechGreen else NeonOrange
                                                )
                                            }
                                        }
                                    }

                                    // Share Action Buttons
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                val clip = ClipData.newPlainText("RiderVoice Convoy Link", activeLink)
                                                clipboard.setPrimaryClip(clip)
                                                isCopied = true
                                                Toast.makeText(context, "Link copied to clipboard", Toast.LENGTH_SHORT).show()
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                Icons.Default.ContentCopy,
                                                contentDescription = null,
                                                tint = if (isCopied) TechGreen else TextPrimary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = if (isCopied) "COPIED" else "COPY LINK",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextPrimary
                                            )
                                        }

                                        Button(
                                            onClick = {
                                                val sendIntent = Intent().apply {
                                                    action = Intent.ACTION_SEND
                                                    putExtra(
                                                        Intent.EXTRA_TEXT,
                                                        "Join my RiderVoice convoy '$convoyName'!\n$activeLink"
                                                    )
                                                    type = "text/plain"
                                                }
                                                context.startActivity(Intent.createChooser(sendIntent, "Share Convoy Invite Link"))
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = NeonOrange),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                Icons.Default.Share,
                                                contentDescription = null,
                                                tint = if (isDark) Color.Black else Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "SHARE SHEET",
                                                color = if (isDark) Color.Black else Color.White,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                } else {
                                    // Fallback / error state
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = linkError ?: "Failed to generate link",
                                            color = AlertRed,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Button(
                                            onClick = { lobbyViewModel.generateShareLink(convoyName) },
                                            colors = ButtonDefaults.buttonColors(containerColor = NeonOrange),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("RETRY LINK GENERATION", color = if (isDark) Color.Black else Color.White)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
