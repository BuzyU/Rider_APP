package com.ridervoice.mesh.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import com.ridervoice.mesh.session.OfflineSessionHolder
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.ridervoice.R
import com.ridervoice.mesh.session.OfflineSession

private val GraphiteBase = MeshColors.LobbyBackground
private val CardDark = MeshColors.LobbyCard
private val NeonOrange = MeshColors.NeonOrange
private val ElectricGreen = MeshColors.ElectricGreen
private val AlertRed = MeshColors.AlertRed
private val TextPrimary = MeshColors.TextPrimary
private val TextSecondary = MeshColors.TextSecondary

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun OfflineMeshLobbyScreen(
    onBackClick: () -> Unit,
    onEnterSession: (sessionCode: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var createdSession by remember { mutableStateOf<OfflineSession?>(null) }
    var joinCodeInput by remember { mutableStateOf("") }
    var joinError by remember { mutableStateOf<String?>(null) }
    var useAsBackup by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(GraphiteBase)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.Default.ArrowBackIosNew,
                        contentDescription = stringResource(R.string.mesh_cd_back),
                        tint = TextPrimary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = stringResource(R.string.mesh_lobby_header),
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonOrange,
                        letterSpacing = 1.5.sp
                    )
                    Text(
                        text = stringResource(R.string.mesh_lobby_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text(
                text = stringResource(R.string.mesh_lobby_description),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            // Backup mode toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardDark, RoundedCornerShape(12.dp))
                    .border(1.dp, MeshColors.LobbyBorder, RoundedCornerShape(12.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = stringResource(R.string.mesh_use_as_ride_backup),
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Switch(
                    checked = useAsBackup,
                    onCheckedChange = { useAsBackup = it }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Section 1: Create Session
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MeshColors.LobbyBorder, RoundedCornerShape(12.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.WifiTethering,
                            contentDescription = null,
                            tint = NeonOrange
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.mesh_lobby_create_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.mesh_lobby_create_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    if (createdSession == null) {
                        Button(
                            onClick = {
                                val session = OfflineSession.create(System.currentTimeMillis())
                                createdSession = session
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonOrange),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.mesh_lobby_generate_code), color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        val session = createdSession!!
                        val formattedCode = OfflineSession.format(session.pairingCode)

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MeshColors.LobbyInput, RoundedCornerShape(8.dp))
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(R.string.mesh_lobby_channel_code_header),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = formattedCode,
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 2.sp
                                ),
                                color = ElectricGreen
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.mesh_lobby_channel_code_header), formattedCode))
                                    Toast.makeText(context, context.getString(R.string.mesh_lobby_code_copied), Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.mesh_cd_copy), tint = TextPrimary)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.mesh_lobby_copy_code), color = TextPrimary)
                            }

                            Button(
                                onClick = {
                                    if (useAsBackup) {
                                        val backup = OfflineSession.fromCode(session.pairingCode, System.currentTimeMillis())
                                        OfflineSessionHolder.setBackup(backup)
                                        Toast.makeText(context, context.getString(R.string.mesh_use_as_ride_backup), Toast.LENGTH_SHORT).show()
                                        onBackClick()
                                    } else {
                                        onEnterSession(session.pairingCode)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricGreen),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1.5f)
                            ) {
                                Text(stringResource(R.string.mesh_lobby_enter_room), color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section 2: Join Session
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MeshColors.LobbyBorder, RoundedCornerShape(12.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.GroupAdd,
                            contentDescription = null,
                            tint = NeonOrange
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.mesh_lobby_join_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.mesh_lobby_join_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = joinCodeInput,
                        onValueChange = {
                            joinCodeInput = it.uppercase()
                            joinError = null
                        },
                        label = { Text(stringResource(R.string.mesh_lobby_pairing_code_label)) },
                        placeholder = { Text(stringResource(R.string.mesh_lobby_pairing_code_placeholder)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                keyboardController?.hide()
                                val session = OfflineSession.fromCode(joinCodeInput, System.currentTimeMillis())
                                if (session == null) {
                                    joinError = context.getString(R.string.mesh_invalid_code)
                                } else {
                                    if (useAsBackup) {
                                        val backup = OfflineSession.fromCode(session.pairingCode, System.currentTimeMillis())
                                        OfflineSessionHolder.setBackup(backup)
                                        Toast.makeText(context, context.getString(R.string.mesh_use_as_ride_backup), Toast.LENGTH_SHORT).show()
                                        onBackClick()
                                    } else {
                                        onEnterSession(session.pairingCode)
                                    }
                                }
                            }
                        ),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            focusedBorderColor = NeonOrange,
                            unfocusedBorderColor = MeshColors.LobbyBorderUnfocused,
                            textColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (joinError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = stringResource(R.string.mesh_cd_error),
                                tint = AlertRed
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = joinError!!,
                                color = AlertRed,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            keyboardController?.hide()
                            val session = OfflineSession.fromCode(joinCodeInput, System.currentTimeMillis())
                            if (session == null) {
                                joinError = context.getString(R.string.mesh_invalid_code)
                            } else {
                                if (useAsBackup) {
                                    val backup = OfflineSession.fromCode(session.pairingCode, System.currentTimeMillis())
                                    OfflineSessionHolder.setBackup(backup)
                                    Toast.makeText(context, context.getString(R.string.mesh_use_as_ride_backup), Toast.LENGTH_SHORT).show()
                                    onBackClick()
                                } else {
                                    onEnterSession(session.pairingCode)
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonOrange),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.mesh_lobby_join_button), color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
