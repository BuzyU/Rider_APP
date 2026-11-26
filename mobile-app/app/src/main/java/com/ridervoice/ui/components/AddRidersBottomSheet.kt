package com.ridervoice.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ridervoice.R
import com.ridervoice.ui.screens.InviteFriendsContent
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.LobbyViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun AddRidersBottomSheet(
    convoyName: String,
    lobbyViewModel: LobbyViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetFeedback = rememberRvFeedbackController()
    var selectedTab by rememberSaveable { mutableStateOf(0) }

    val shareLink by lobbyViewModel.shareLink.collectAsStateWithLifecycle()
    val isGeneratingLink by lobbyViewModel.isGeneratingLink.collectAsStateWithLifecycle()
    val linkError by lobbyViewModel.linkError.collectAsStateWithLifecycle()

    var isCopied by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(selectedTab) {
        if (selectedTab == 1 && shareLink == null) {
            lobbyViewModel.generateShareLink(convoyName)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(RvScrim)
                .clickable(role = Role.Button) { onDismiss() },
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                color = PaperRaised,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                border = BorderStroke(1.dp, InkHairline),
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) { detectTapGestures { } }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(38.dp)
                                .height(4.dp)
                                .background(InkHairline, RoundedCornerShape(RvRadius.pill))
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.sheet_add_riders_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Ink,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(PaperRaised)
                                .border(1.dp, InkHairline, CircleShape)
                        ) {
                            Icon(RvIcons.Close, contentDescription = stringResource(R.string.cd_close), tint = Ink, modifier = Modifier.size(18.dp))
                        }
                    }

                    // Pill Segmented Tabs
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(RvRadius.pill))
                            .background(PaperSunken)
                            .border(1.dp, InkHairline, RoundedCornerShape(RvRadius.pill))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val tabItems = listOf(
                            stringResource(R.string.sheet_tab_from_squad) to RvIcons.Group,
                            stringResource(R.string.sheet_tab_share_link) to RvIcons.Link
                        )
                        tabItems.forEachIndexed { idx, (label, icon) ->
                            val selected = selectedTab == idx
                            Surface(
                                color = if (selected) PaperRaised else Color.Transparent,
                                shape = RoundedCornerShape(RvRadius.pill),
                                border = if (selected) BorderStroke(1.dp, InkHairline) else null,
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .selectable(
                                        selected = selected,
                                        role = Role.Tab,
                                        onClick = { selectedTab = idx }
                                    )
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = if (selected) Pop else InkSecondary,
                                        modifier = Modifier.size(17.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = label,
                                        color = if (selected) Pop else InkSecondary,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 320.dp, max = 500.dp)
                    ) {
                        if (selectedTab == 0) {
                            InviteFriendsContent(
                                convoyName = convoyName,
                                onInvitesSent = null,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Surface(
                                    color = PaperRaised,
                                    shape = RoundedCornerShape(22.dp),
                                    border = BorderStroke(1.dp, InkHairline),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(RvIcons.Link, contentDescription = null, tint = Pop, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = stringResource(R.string.sheet_invite_link_title),
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = Ink
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = stringResource(R.string.sheet_invite_link_desc),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = InkSecondary
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
                                            CircularProgressIndicator(color = Pop, strokeWidth = 3.dp)
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Text(
                                                text = stringResource(R.string.sheet_generating_link),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = InkSecondary
                                            )
                                        }
                                    }
                                } else if (shareLink != null) {
                                    val activeLink = shareLink ?: return@Surface

                                    Surface(
                                        color = PaperSunken,
                                        shape = RoundedCornerShape(16.dp),
                                        border = BorderStroke(1.dp, InkHairline),
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
                                                color = Ink,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.weight(1f)
                                            )
                                            IconButton(
                                                onClick = {
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    val clip = ClipData.newPlainText(context.getString(R.string.sheet_convoy_link_clip_label), activeLink)
                                                    clipboard.setPrimaryClip(clip)
                                                    isCopied = true
                                                    sheetFeedback.showSuccess(context.getString(R.string.toast_link_copied))
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = if (isCopied) RvIcons.Check else RvIcons.ContentCopy,
                                                    contentDescription = stringResource(R.string.cd_copy_link),
                                                    tint = if (isCopied) Moss else Pop
                                                )
                                            }
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                val clip = ClipData.newPlainText(context.getString(R.string.sheet_convoy_link_clip_label), activeLink)
                                                clipboard.setPrimaryClip(clip)
                                                isCopied = true
                                                sheetFeedback.showSuccess(context.getString(R.string.toast_link_copied))
                                            },
                                            shape = RoundedCornerShape(16.dp),
                                            border = BorderStroke(1.dp, InkHairline),
                                            colors = ButtonDefaults.outlinedButtonColors(containerColor = PaperRaised),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                        ) {
                                            Icon(
                                                RvIcons.ContentCopy,
                                                contentDescription = null,
                                                tint = if (isCopied) Moss else Ink,
                                                modifier = Modifier.size(17.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = if (isCopied) stringResource(R.string.action_copied) else stringResource(R.string.action_copy_link),
                                                style = MaterialTheme.typography.labelLarge,
                                                color = Ink,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }

                                        Button(
                                            onClick = {
                                                val sendIntent = Intent().apply {
                                                    action = Intent.ACTION_SEND
                                                    putExtra(
                                                        Intent.EXTRA_TEXT,
                                                        context.getString(R.string.share_convoy_invite_text, convoyName, activeLink)
                                                    )
                                                    type = "text/plain"
                                                }
                                                context.startActivity(Intent.createChooser(sendIntent, context.getString(R.string.share_convoy_invite_chooser)))
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Pop),
                                            shape = RoundedCornerShape(16.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                        ) {
                                            Icon(
                                                RvIcons.Share,
                                                contentDescription = null,
                                                tint = PaperOnPop,
                                                modifier = Modifier.size(17.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = stringResource(R.string.action_share),
                                                color = PaperOnPop,
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                } else {
                                    InlineError(
                                        message = linkError ?: stringResource(R.string.error_generate_link_failed),
                                        onRetry = { lobbyViewModel.generateShareLink(convoyName) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            RvSnackbarHost(
                controller = sheetFeedback,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}
