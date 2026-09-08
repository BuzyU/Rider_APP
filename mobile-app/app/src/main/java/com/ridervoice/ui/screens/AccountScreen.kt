package com.ridervoice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.ridervoice.ui.components.TacticalButton
import com.ridervoice.ui.components.UpdateDialog
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.AccountViewModel
import com.ridervoice.ui.viewmodels.UpdateViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    accountViewModel: AccountViewModel = hiltViewModel(),
    updateViewModel: UpdateViewModel = hiltViewModel(),
    onBackClick: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onCheckForUpdates: () -> Unit = { updateViewModel.checkForUpdates() }
) {
    val uiState by accountViewModel.uiState.collectAsState()
    var showEditDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GraphiteBase)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── TOP APP BAR ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 44.dp, start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.Default.ArrowBackIosNew,
                        contentDescription = "Back",
                        tint = TextPrimary
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "OPERATOR DOSSIER",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonOrange,
                        letterSpacing = 1.5.sp
                    )
                    Text(
                        text = "RIDER ACCOUNT",
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(onClick = { showEditDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit Profile",
                        tint = ElectricCyan
                    )
                }
            }

            // ── CONTENT ────────────────────────────────────────────────────────────
            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = NeonOrange, strokeWidth = 3.dp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "RETRIEVING OPERATOR DOSSIER...",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            letterSpacing = 1.2.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Feedback messages
                    uiState.errorMessage?.let { err ->
                        item {
                            Surface(
                                color = AlertRed.copy(alpha = 0.15f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, AlertRed),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = AlertRed)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = err, color = AlertRed, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }

                    uiState.successMessage?.let { msg ->
                        item {
                            Surface(
                                color = TechGreen.copy(alpha = 0.15f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, TechGreen),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = TechGreen)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = msg, color = TechGreen, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }

                    // ── OPERATOR HEADER CARD ───────────────────────────────────────
                    item {
                        Surface(
                            color = DarkSlate,
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // Dynamic Avatar: Google profile pic with graceful fallback to initial avatar
                                Box(
                                    modifier = Modifier
                                        .size(96.dp)
                                        .clip(CircleShape)
                                        .background(Gunmetal)
                                        .border(2.5.dp, ElectricCyan, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!uiState.photoUrl.isNullOrBlank()) {
                                        SubcomposeAsyncImage(
                                            model = ImageRequest.Builder(context)
                                                .data(uiState.photoUrl)
                                                .crossfade(true)
                                                .build(),
                                            contentDescription = "User Profile Picture",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(CircleShape)
                                        ) {
                                            val state = painter.state
                                            if (state is AsyncImagePainter.State.Loading) {
                                                CircularProgressIndicator(
                                                    color = ElectricCyan,
                                                    strokeWidth = 2.dp,
                                                    modifier = Modifier.size(28.dp)
                                                )
                                            } else if (state is AsyncImagePainter.State.Error) {
                                                // Fallback to Dynamic Initial-Based Avatar
                                                InitialAvatarText(initial = uiState.initialLetter)
                                            } else {
                                                SubcomposeAsyncImageContent()
                                            }
                                        }
                                    } else {
                                        // Dynamic Circular Avatar fallback using first letter of display name
                                        InitialAvatarText(initial = uiState.initialLetter)
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                // Display Name
                                Text(
                                    text = uiState.displayName.ifBlank { "Rider Transceiver" },
                                    style = MaterialTheme.typography.titleLarge,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 22.sp
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                // Handle Badge
                                Surface(
                                    color = Gunmetal,
                                    shape = RoundedCornerShape(6.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor)
                                ) {
                                    Text(
                                        text = "@${uiState.handle.removePrefix("@")}",
                                        color = TechGreen,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        letterSpacing = 1.sp
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Auth Provider Badge
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(if (uiState.authProvider == "Google") ElectricCyan else NeonOrange)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "${uiState.authProvider.uppercase()} AUTHENTICATED",
                                        color = TextSecondary,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 11.sp,
                                        letterSpacing = 1.sp
                                    )
                                }
                            }
                        }
                    }

                    // ── IDENTIFICATION & COMMS ─────────────────────────────────────
                    item {
                        Text(
                            text = "── IDENTIFICATION & COMMS ──",
                            color = NeonOrange,
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 1.5.sp,
                            fontSize = 11.sp
                        )
                    }

                    item {
                        AccountInfoRow(
                            label = "Display Name",
                            value = uiState.displayName.ifBlank { "Not configured" },
                            icon = Icons.Default.Badge
                        )
                    }

                    item {
                        AccountInfoRow(
                            label = "Call-Sign Handle",
                            value = "@${uiState.handle.removePrefix("@")}",
                            icon = Icons.Default.AlternateEmail
                        )
                    }

                    item {
                        AccountInfoRow(
                            label = "Email Address",
                            value = uiState.email,
                            icon = Icons.Default.Email
                        )
                    }

                    item {
                        AccountInfoRow(
                            label = "Phone Number",
                            value = uiState.phone,
                            icon = Icons.Default.Phone
                        )
                    }

                    // ── EQUIPMENT & RIG ────────────────────────────────────────────
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "── EQUIPMENT & RIG ──",
                            color = NeonOrange,
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 1.5.sp,
                            fontSize = 11.sp
                        )
                    }

                    item {
                        AccountInfoRow(
                            label = "Motorcycle / Rig",
                            value = uiState.bikeModel,
                            icon = Icons.Default.TwoWheeler
                        )
                    }

                    item {
                        AccountInfoRow(
                            label = "Tactical Notes / Bio",
                            value = uiState.bio,
                            icon = Icons.Default.Notes
                        )
                    }

                    // ── QUICK ACTIONS ──────────────────────────────────────────────
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        TacticalButton(
                            text = "CALIBRATE PROFILE (EDIT DETAILS)",
                            onClick = { showEditDialog = true },
                            color = Gunmetal,
                            textColor = ElectricCyan,
                            isOutlined = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    item {
                        TacticalButton(
                            text = "CHECK FOR FIRMWARE UPDATES",
                            onClick = onCheckForUpdates,
                            color = DarkSlate,
                            textColor = NeonOrange,
                            isOutlined = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            }
        }

        // ── PROFILE EDIT DIALOG ──────────────────────────────────────────────────
        if (showEditDialog) {
            EditProfileDialog(
                initialDisplayName = uiState.displayName,
                initialHandle = uiState.handle,
                initialPhone = if (uiState.phone == "Not configured") "" else uiState.phone,
                initialBikeModel = if (uiState.bikeModel == "Not configured") "" else uiState.bikeModel,
                initialBio = if (uiState.bio == "No bio provided") "" else uiState.bio,
                isSaving = uiState.isSaving,
                onDismiss = { showEditDialog = false },
                onSave = { name, handle, phone, bike, bio ->
                    accountViewModel.updateProfile(name, handle, phone, bike, bio)
                    showEditDialog = false
                }
            )
        }

        // ── IN-APP UPDATE DIALOG ─────────────────────────────────────────────────
        val updateState by updateViewModel.uiState.collectAsState()
        UpdateDialog(
            uiState = updateState,
            onConfirmDownload = { updateViewModel.promptDownloadConfirmation(it) },
            onStartDownload = { updateViewModel.startDownload(it) },
            onCancelDownload = { updateViewModel.cancelDownload() },
            onInstallUpdate = { file, info -> updateViewModel.installUpdate(file, info) },
            onOpenSettings = { updateViewModel.openSettings() },
            onRetry = { updateViewModel.checkForUpdates() },
            onDismiss = { updateViewModel.dismiss() }
        )
    }
}

@Composable
private fun InitialAvatarText(initial: String) {
    Text(
        text = initial,
        color = ElectricCyan,
        style = MaterialTheme.typography.displayMedium,
        fontWeight = FontWeight.Black,
        fontSize = 40.sp
    )
}

@Composable
private fun AccountInfoRow(
    label: String,
    value: String,
    icon: ImageVector
) {
    Surface(
        color = DarkSlate,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Gunmetal),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label.uppercase(),
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp,
                    letterSpacing = 0.8.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = value,
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditProfileDialog(
    initialDisplayName: String,
    initialHandle: String,
    initialPhone: String,
    initialBikeModel: String,
    initialBio: String,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (displayName: String, handle: String, phone: String, bikeModel: String, bio: String) -> Unit
) {
    var displayName by remember { mutableStateOf(initialDisplayName) }
    var handle by remember { mutableStateOf(initialHandle.removePrefix("@")) }
    var phone by remember { mutableStateOf(initialPhone) }
    var bikeModel by remember { mutableStateOf(initialBikeModel) }
    var bio by remember { mutableStateOf(initialBio) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "CALIBRATE OPERATOR PROFILE",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        containerColor = DarkSlate,
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = NeonOrange,
                        unfocusedBorderColor = BorderColor,
                        containerColor = Gunmetal,
                        textColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = handle,
                    onValueChange = { handle = it },
                    label = { Text("Call-Sign Handle") },
                    leadingIcon = { Text("@", color = TechGreen, fontWeight = FontWeight.Bold) },
                    singleLine = true,
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = TechGreen,
                        unfocusedBorderColor = BorderColor,
                        containerColor = Gunmetal,
                        textColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone Number") },
                    singleLine = true,
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = ElectricCyan,
                        unfocusedBorderColor = BorderColor,
                        containerColor = Gunmetal,
                        textColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = bikeModel,
                    onValueChange = { bikeModel = it },
                    label = { Text("Motorcycle / Rig (e.g. Yamaha MT-07)") },
                    singleLine = true,
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = ElectricCyan,
                        unfocusedBorderColor = BorderColor,
                        containerColor = Gunmetal,
                        textColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = bio,
                    onValueChange = { bio = it },
                    label = { Text("Tactical Notes / Bio") },
                    maxLines = 3,
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = ElectricCyan,
                        unfocusedBorderColor = BorderColor,
                        containerColor = Gunmetal,
                        textColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(displayName, handle, phone, bikeModel, bio) },
                enabled = !isSaving,
                colors = ButtonDefaults.buttonColors(containerColor = NeonOrange)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text("SAVE DOSSIER", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("CANCEL", color = TextPrimary)
            }
        }
    )
}
