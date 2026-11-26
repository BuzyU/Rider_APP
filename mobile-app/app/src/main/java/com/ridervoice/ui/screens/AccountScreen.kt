package com.ridervoice.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.ridervoice.R
import com.ridervoice.ui.components.PrimaryButton
import com.ridervoice.ui.components.SecondaryButton
import com.ridervoice.ui.components.UpdateDialog
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.AccountViewModel
import com.ridervoice.ui.viewmodels.UpdateViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.ridervoice.ui.components.rvBringIntoViewOnFocus
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Dialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    accountViewModel: AccountViewModel = hiltViewModel(),
    updateViewModel: UpdateViewModel = hiltViewModel(),
    onBackClick: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onCheckForUpdates: () -> Unit = { updateViewModel.checkForUpdates() }
) {
    val uiState by accountViewModel.uiState.collectAsStateWithLifecycle()
    var showEditDialog by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Editorial Top Bar (Title + Circular Edit & Settings)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.nav_account),
                    color = Ink,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = { showEditDialog = true },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(PopContainer)
                        .border(1.dp, Pop.copy(alpha = 0.3f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.account_edit_profile),
                        tint = Pop,
                        modifier = Modifier.size(19.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = onNavigateToSettings,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(PaperRaised)
                        .border(1.dp, InkHairline, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.nav_settings),
                        tint = Ink,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Pop, strokeWidth = 3.dp)
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = stringResource(R.string.account_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = InkSecondary
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    uiState.errorMessage?.let { err ->
                        item {
                            Surface(
                                color = BrickContainer,
                                border = BorderStroke(1.dp, Brick),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = Brick)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(text = err, color = Brick, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }

                    uiState.successMessage?.let { msg ->
                        item {
                            Surface(
                                color = MossContainer,
                                border = BorderStroke(1.dp, Moss.copy(alpha = 0.4f)),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Moss)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(text = msg, color = Moss, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    // Profile Hero Bento Card
                    item {
                        Surface(
                            color = PaperRaised,
                            shape = RoundedCornerShape(24.dp),
                            border = BorderStroke(1.dp, InkHairline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(22.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(88.dp)
                                        .clip(CircleShape)
                                        .background(CharcoalSurface)
                                        .border(2.dp, InkHairline, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!uiState.photoUrl.isNullOrBlank()) {
                                        SubcomposeAsyncImage(
                                            model = ImageRequest.Builder(context)
                                                .data(uiState.photoUrl)
                                                .crossfade(true)
                                                .build(),
                                            contentDescription = stringResource(R.string.cd_user_profile_picture),
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(CircleShape)
                                        ) {
                                            val state = painter.state
                                            if (state is AsyncImagePainter.State.Loading) {
                                                CircularProgressIndicator(
                                                    color = PaperOnPop,
                                                    strokeWidth = 2.dp,
                                                    modifier = Modifier.size(26.dp)
                                                )
                                            } else if (state is AsyncImagePainter.State.Error) {
                                                InitialAvatarText(initial = uiState.initialLetter)
                                            } else {
                                                SubcomposeAsyncImageContent()
                                            }
                                        }
                                    } else {
                                        InitialAvatarText(initial = uiState.initialLetter)
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                Text(
                                    text = uiState.displayName.ifBlank { stringResource(R.string.participant_rider_prefix) },
                                    style = MaterialTheme.typography.headlineLarge,
                                    color = Ink,
                                    fontWeight = FontWeight.Bold
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        color = MossContainer,
                                        shape = RoundedCornerShape(RvRadius.pill)
                                    ) {
                                        Text(
                                            text = "@${uiState.handle.removePrefix("@")}",
                                            color = Moss,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                        )
                                    }

                                    Surface(
                                        color = PaperSunken,
                                        shape = RoundedCornerShape(RvRadius.pill),
                                        border = BorderStroke(1.dp, InkHairline)
                                    ) {
                                        Text(
                                            text = uiState.authProvider,
                                            color = InkSecondary,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Profile Details Grouped Card
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.account_section_details),
                                color = InkSecondary,
                                style = MaterialTheme.typography.labelSmall,
                                letterSpacing = 0.9.sp,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                            Surface(
                                color = PaperRaised,
                                shape = RoundedCornerShape(22.dp),
                                border = BorderStroke(1.dp, InkHairline),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    AccountInfoRow(
                                        label = stringResource(R.string.account_display_name),
                                        value = uiState.displayName.ifBlank { stringResource(R.string.account_not_configured) },
                                        icon = Icons.Default.Badge
                                    )
                                    Divider(color = InkHairline, modifier = Modifier.padding(start = 66.dp, end = 16.dp))
                                    AccountInfoRow(
                                        label = stringResource(R.string.account_callsign_handle),
                                        value = "@${uiState.handle.removePrefix("@")}",
                                        icon = Icons.Default.AlternateEmail
                                    )
                                    Divider(color = InkHairline, modifier = Modifier.padding(start = 66.dp, end = 16.dp))
                                    AccountInfoRow(
                                        label = stringResource(R.string.account_email_address),
                                        value = uiState.email,
                                        icon = Icons.Default.Email
                                    )
                                    Divider(color = InkHairline, modifier = Modifier.padding(start = 66.dp, end = 16.dp))
                                    AccountInfoRow(
                                        label = stringResource(R.string.account_phone_number),
                                        value = uiState.phone,
                                        icon = Icons.Default.Phone
                                    )
                                }
                            }
                        }
                    }

                    // Motorcycle & Bio Grouped Card
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.account_section_bike_bio),
                                color = InkSecondary,
                                style = MaterialTheme.typography.labelSmall,
                                letterSpacing = 0.9.sp,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                            Surface(
                                color = PaperRaised,
                                shape = RoundedCornerShape(22.dp),
                                border = BorderStroke(1.dp, InkHairline),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    AccountInfoRow(
                                        label = stringResource(R.string.account_motorcycle),
                                        value = uiState.bikeModel,
                                        icon = Icons.Default.TwoWheeler
                                    )
                                    Divider(color = InkHairline, modifier = Modifier.padding(start = 66.dp, end = 16.dp))
                                    AccountInfoRow(
                                        label = stringResource(R.string.account_bio),
                                        value = uiState.bio,
                                        icon = Icons.Default.Notes
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                        PrimaryButton(
                            text = stringResource(R.string.account_edit_profile),
                            onClick = { showEditDialog = true },
                            leadingIcon = Icons.Default.Edit
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(20.dp))
                    }
                }
            }
        }

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

        val updateState by updateViewModel.uiState.collectAsStateWithLifecycle()
        UpdateDialog(
            uiState = updateState,
            onConfirmDownload = { updateViewModel.promptDownloadConfirmation(it) },
            onStartDownload = { updateViewModel.startDownload(it) },
            onCancelDownload = { updateViewModel.cancelDownload() },
            onInstallUpdate = { file, info -> updateViewModel.installUpdate(file, info) },
            onOpenSettings = { updateViewModel.openSettings() },
            onRetry = { updateViewModel.checkForUpdates() },
            onDismiss = { updateViewModel.dismiss() },
            onOpenBrowserDownload = { url ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        )
    }
}

@Composable
private fun InitialAvatarText(initial: String) {
    Text(
        text = initial,
        color = PaperOnPop,
        style = RvTypography.displayMetric,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun AccountInfoRow(
    label: String,
    value: String,
    icon: ImageVector
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(PaperSunken),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Ink, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = InkSecondary,
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                color = Ink,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditProfileDialog(
    initialDisplayName: String,
    initialHandle: String,
    initialPhone: String,
    initialBikeModel: String,
    initialBio: String,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (displayName: String, handle: String, phone: String, bikeModel: String, bio: String) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        EditProfileFormContent(
            initialDisplayName = initialDisplayName,
            initialHandle = initialHandle,
            initialPhone = initialPhone,
            initialBikeModel = initialBikeModel,
            initialBio = initialBio,
            isSaving = isSaving,
            onDismiss = onDismiss,
            onSave = onSave
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditProfileFormContent(
    initialDisplayName: String,
    initialHandle: String,
    initialPhone: String,
    initialBikeModel: String,
    initialBio: String,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (displayName: String, handle: String, phone: String, bikeModel: String, bio: String) -> Unit
) {
    val focusManager = LocalFocusManager.current
    var displayName by rememberSaveable { mutableStateOf(initialDisplayName) }
    var handle by rememberSaveable { mutableStateOf(initialHandle.removePrefix("@")) }
    var phone by rememberSaveable { mutableStateOf(initialPhone) }
    var bikeModel by rememberSaveable { mutableStateOf(initialBikeModel) }
    var bio by rememberSaveable { mutableStateOf(initialBio) }

    val fieldColors = TextFieldDefaults.outlinedTextFieldColors(
        focusedBorderColor = Pop,
        unfocusedBorderColor = InkHairline,
        containerColor = PaperSunken.copy(alpha = 0.65f),
        textColor = Ink,
        cursorColor = Pop
    )

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = PaperRaised,
        border = BorderStroke(1.dp, InkHairline),
        modifier = Modifier.fillMaxWidth()
    ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.account_edit_profile),
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                    fontWeight = FontWeight.Bold
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(stringResource(R.string.account_display_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth().rvBringIntoViewOnFocus().testTag("account_display_name_field")
                )

                OutlinedTextField(
                    value = handle,
                    onValueChange = { handle = it },
                    label = { Text(stringResource(R.string.account_callsign_handle)) },
                    leadingIcon = { Text("@", color = Pop, fontWeight = FontWeight.Bold) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth().rvBringIntoViewOnFocus().testTag("account_handle_field")
                )

                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text(stringResource(R.string.account_phone_number)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        keyboardType = KeyboardType.Phone,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth().rvBringIntoViewOnFocus().testTag("account_phone_field")
                )

                OutlinedTextField(
                    value = bikeModel,
                    onValueChange = { bikeModel = it },
                    label = { Text(stringResource(R.string.account_motorcycle_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth().rvBringIntoViewOnFocus().testTag("account_bike_field")
                )

                OutlinedTextField(
                    value = bio,
                    onValueChange = { bio = it },
                    label = { Text(stringResource(R.string.account_bio)) },
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        focusManager.clearFocus()
                        if (!isSaving) onSave(displayName, handle, phone, bikeModel, bio)
                    }),
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth().rvBringIntoViewOnFocus().testTag("account_bio_field")
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_cancel), color = Ink)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onSave(displayName, handle, phone, bikeModel, bio) },
                        enabled = !isSaving,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Pop)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(color = PaperOnPop, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(stringResource(R.string.action_save), color = PaperOnPop, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
}
