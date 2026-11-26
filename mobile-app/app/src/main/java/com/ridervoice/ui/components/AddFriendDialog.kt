package com.ridervoice.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ridervoice.R
import com.ridervoice.models.ProfileResponse
import com.ridervoice.models.RiderRelation
import com.ridervoice.models.RiderSearchState
import com.ridervoice.ui.theme.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.ridervoice.ui.components.rvBringIntoViewOnFocus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFriendDialog(
    onDismiss: () -> Unit,
    onAddFriend: (handle: String) -> Unit = {},
    searchState: RiderSearchState = RiderSearchState.Idle,
    isSendingRequest: Boolean = false,
    onSearchQueryChange: ((query: String, immediate: Boolean) -> Unit)? = null,
    onSendRequestToProfile: ((ProfileResponse) -> Unit)? = null,
    onAcceptIncomingProfile: ((ProfileResponse) -> Unit)? = null
) {
    var query by rememberSaveable { mutableStateOf("") }
    val supportsLiveSearch = onSearchQueryChange != null

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
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = PaperRaised,
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.dp, InkHairline),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 440.dp)
                    .testTag("add_rider_dialog_card")
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 10.dp, top = 18.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MossContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = RvIcons.PersonAdd,
                                contentDescription = null,
                                tint = Moss,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Text(
                            text = stringResource(R.string.dialog_add_rider_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Ink,
                            modifier = Modifier.weight(1f)
                        )

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = RvIcons.Close,
                                contentDescription = stringResource(R.string.action_close),
                                tint = InkSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Divider(color = InkHairline, thickness = 1.dp)

                    // Body
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        RvTextField(
                            value = query,
                            onValueChange = { raw ->
                                val cleaned = raw.trim().removePrefix("@")
                                query = cleaned
                                onSearchQueryChange?.invoke(cleaned, false)
                            },
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.dialog_add_rider_placeholder),
                                    color = InkSecondary,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            },
                            leadingIcon = {
                                Text(
                                    text = "@",
                                    color = Moss,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(start = 10.dp)
                                )
                            },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (query.isNotBlank()) {
                                        IconButton(
                                            onClick = {
                                                query = ""
                                                onSearchQueryChange?.invoke("", true)
                                            },
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(
                                                imageVector = RvIcons.Close,
                                                contentDescription = stringResource(R.string.cd_clear),
                                                tint = InkSecondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    if (!supportsLiveSearch && query.isNotBlank()) {
                                        IconButton(
                                            onClick = {
                                                onAddFriend(query)
                                                onDismiss()
                                            },
                                            modifier = Modifier
                                                .padding(end = 4.dp)
                                                .size(RvTouchTarget.min)
                                                .clip(CircleShape)
                                                .background(MossContainer)
                                                .border(1.dp, Moss.copy(alpha = 0.45f), CircleShape)
                                                .testTag("add_rider_send_icon_btn")
                                        ) {
                                            Icon(
                                                imageVector = RvIcons.PersonAdd,
                                                contentDescription = stringResource(R.string.cd_send_squad_request, query),
                                                tint = Moss,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Search
                            ),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    if (supportsLiveSearch) {
                                        onSearchQueryChange?.invoke(query, true)
                                    } else if (query.isNotBlank()) {
                                        onAddFriend(query)
                                        onDismiss()
                                    }
                                }
                            ),
                            colors = TextFieldDefaults.outlinedTextFieldColors(
                                focusedBorderColor = Moss,
                                unfocusedBorderColor = InkBorderStrong,
                                containerColor = PaperSunken,
                                textColor = Ink,
                                cursorColor = Moss
                            ),
                            shape = RoundedCornerShape(16.dp),
                            singleLine = true,
                            modifier = Modifier
                                .rvBringIntoViewOnFocus()
                                .fillMaxWidth()
                                .testTag("add_rider_search_input")
                        )

                        if (supportsLiveSearch) {
                            when (searchState) {
                                is RiderSearchState.Idle -> {
                                    Text(
                                        text = stringResource(R.string.squad_search_hint),
                                        color = InkSecondary,
                                        style = MaterialTheme.typography.bodySmall,
                                        lineHeight = 18.sp
                                    )
                                }

                                is RiderSearchState.Searching -> {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        CircularProgressIndicator(
                                            color = Moss,
                                            strokeWidth = 2.5.dp,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }

                                is RiderSearchState.NotFound -> {
                                    Surface(
                                        color = Paper,
                                        shape = RoundedCornerShape(16.dp),
                                        border = BorderStroke(1.dp, InkHairline),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = RvIcons.PersonSearch,
                                                contentDescription = null,
                                                tint = InkSecondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(
                                                text = stringResource(R.string.squad_search_not_found, searchState.query),
                                                color = InkSecondary,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }

                                is RiderSearchState.Error -> {
                                    Surface(
                                        color = BrickContainer.copy(alpha = 0.4f),
                                        shape = RoundedCornerShape(16.dp),
                                        border = BorderStroke(1.dp, Brick.copy(alpha = 0.35f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = searchState.message,
                                            color = Brick,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                                        )
                                    }
                                }

                                is RiderSearchState.Found -> {
                                    RiderLookupResultCard(
                                        profile = searchState.profile,
                                        relation = searchState.relation,
                                        isSending = isSendingRequest,
                                        onSendRequest = {
                                            onSendRequestToProfile?.invoke(searchState.profile)
                                                ?: run {
                                                    val handle = searchState.profile.handle ?: query
                                                    onAddFriend(handle)
                                                }
                                        },
                                        onAcceptIncoming = {
                                            onAcceptIncomingProfile?.invoke(searchState.profile)
                                                ?: onSendRequestToProfile?.invoke(searchState.profile)
                                        }
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.dialog_add_rider_desc),
                                color = InkSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RiderLookupResultCard(
    profile: ProfileResponse,
    relation: RiderRelation,
    isSending: Boolean,
    onSendRequest: () -> Unit,
    onAcceptIncoming: () -> Unit
) {
    val cleanHandle = profile.handle?.removePrefix("@").orEmpty()
    val title = profile.displayName?.takeIf { it.isNotBlank() }
        ?: if (cleanHandle.isNotBlank()) "@$cleanHandle" else profile.id
    val subtitle = buildList {
        if (!profile.displayName.isNullOrBlank() && cleanHandle.isNotBlank()) add("@$cleanHandle")
        if (!profile.bikeModel.isNullOrBlank()) add(profile.bikeModel)
    }.joinToString(" · ")

    val initials = (profile.displayName?.takeIf { it.isNotBlank() } ?: cleanHandle)
        .split(" ")
        .filter { it.isNotBlank() }
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercase() }
        .joinToString("")
        .ifBlank { "R" }

    Surface(
        color = Paper,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, InkHairline),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("add_rider_result_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(PaperSunken)
                    .border(1.dp, InkHairline, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initials,
                    color = Ink,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Ink,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        color = InkSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            when (relation) {
                RiderRelation.ALREADY_IN_SQUAD -> {
                    Surface(
                        color = MossContainer,
                        shape = RoundedCornerShape(RvRadius.pill),
                        border = BorderStroke(1.dp, Moss.copy(alpha = 0.25f)),
                        modifier = Modifier.testTag("add_rider_already_in_squad_badge")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = RvIcons.Check,
                                contentDescription = null,
                                tint = Moss,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.squad_already_member),
                                color = Moss,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                RiderRelation.PENDING_SENT -> {
                    Surface(
                        color = PaperSunken,
                        shape = RoundedCornerShape(RvRadius.pill),
                        border = BorderStroke(1.dp, InkHairline),
                        modifier = Modifier.testTag("add_rider_pending_badge")
                    ) {
                        Text(
                            text = stringResource(R.string.squad_status_pending),
                            color = InkSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                RiderRelation.SELF -> {
                    Surface(
                        color = PaperSunken,
                        shape = RoundedCornerShape(RvRadius.pill),
                        border = BorderStroke(1.dp, InkHairline)
                    ) {
                        Text(
                            text = stringResource(R.string.squad_status_you),
                            color = InkSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                RiderRelation.INCOMING_REQUEST -> {
                    IconButton(
                        onClick = onAcceptIncoming,
                        enabled = !isSending,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MossContainer)
                            .border(1.dp, Moss.copy(alpha = 0.45f), CircleShape)
                            .testTag("add_rider_accept_incoming_btn")
                    ) {
                        if (isSending) {
                            CircularProgressIndicator(
                                color = Moss,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Icon(
                                imageVector = RvIcons.Check,
                                contentDescription = stringResource(R.string.cd_accept_squad_request, cleanHandle),
                                tint = Moss,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                RiderRelation.AVAILABLE -> {
                    IconButton(
                        onClick = onSendRequest,
                        enabled = !isSending,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MossContainer)
                            .border(1.dp, Moss.copy(alpha = 0.45f), CircleShape)
                            .testTag("add_rider_send_icon_btn")
                    ) {
                        if (isSending) {
                            CircularProgressIndicator(
                                color = Moss,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Icon(
                                imageVector = RvIcons.PersonAdd,
                                contentDescription = stringResource(R.string.cd_send_squad_request, cleanHandle),
                                tint = Moss,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
