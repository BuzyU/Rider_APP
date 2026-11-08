package com.ridervoice.mesh.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ridervoice.R
import com.ridervoice.mesh.link.MeshLinkState
import com.ridervoice.mesh.link.UdpMeshLink
import com.ridervoice.mesh.session.OfflineSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

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
    onEnterSession: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OfflineMeshViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var generatedCode by remember { mutableStateOf<String?>(null) }
    var joinCodeInput by remember { mutableStateOf("") }
    var joinError by remember { mutableStateOf<String?>(null) }
    var useAsBackup by remember { mutableStateOf(viewModel.isMicOwnedByLiveKit()) }

    val backupSession by viewModel.backupSession.collectAsState()
    val isDerivingKey by viewModel.isDerivingKey.collectAsState()
    val sessionError by viewModel.sessionError.collectAsState()
    val diagSnapshot by viewModel.transport.diagnostics.collectAsState()

    var localIp by remember { mutableStateOf<String?>(null) }
    var bcastAddr by remember { mutableStateOf("255.255.255.255") }
    val zeroNeighboursStartMs = remember { System.currentTimeMillis() }
    var elapsedZeroNeighboursSec by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val ip = UdpMeshLink.getLocalIpAddress()
            val bcast = UdpMeshLink.getLocalBroadcastAddress()
            localIp = ip
            bcastAddr = bcast
        }
        while (isActive) {
            delay(1_000L)
            elapsedZeroNeighboursSec = ((System.currentTimeMillis() - zeroNeighboursStartMs) / 1000L).coerceAtLeast(0L)
        }
    }

    val scrollState = rememberScrollState()

    val submitJoinCode: () -> Unit = {
        keyboardController?.hide()
        viewModel.joinWithCode(
            rawCode = joinCodeInput,
            saveAsBackupOnly = useAsBackup,
            onReady = {
                if (useAsBackup) {
                    Toast.makeText(context, context.getString(R.string.mesh_backup_saved_toast), Toast.LENGTH_SHORT).show()
                    onBackClick()
                } else {
                    onEnterSession()
                }
            },
            onInvalid = {
                joinError = context.getString(R.string.mesh_invalid_code)
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(GraphiteBase)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
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
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Active Backup Session Status Card (if configured)
            val currentBackup = backupSession
            if (currentBackup != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardDark),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, ElectricGreen.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .padding(bottom = 16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(
                                R.string.mesh_backup_active_label,
                                OfflineSession.format(currentBackup.pairingCode)
                            ),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            ),
                            color = ElectricGreen,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.clearSavedBackup() }) {
                            Text(
                                text = stringResource(R.string.mesh_backup_clear_action),
                                color = NeonOrange,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Numbered Setup Instructions Card
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MeshColors.LobbyBorder, RoundedCornerShape(12.dp))
                    .padding(bottom = 16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.mesh_network_setup_instructions_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = NeonOrange,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.mesh_network_step_1),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.mesh_network_step_2),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.mesh_network_step_3),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary
                    )
                }
            }

            // Self-Test HUD Card
            val resolvedLinkState = remember(diagSnapshot.linkState) {
                when (diagSnapshot.linkState) {
                    MeshLinkState.RUNNING.name -> MeshLinkState.RUNNING
                    MeshLinkState.STARTING.name -> MeshLinkState.STARTING
                    MeshLinkState.FAILED.name -> MeshLinkState.FAILED
                    else -> MeshLinkState.STOPPED
                }
            }
            val effectiveIp = diagSnapshot.localIp.takeIf { it.isNotBlank() } ?: localIp
            val effectiveBcast = diagSnapshot.broadcastAddress.takeIf { it.isNotBlank() } ?: bcastAddr
            val neighboursCount = diagSnapshot.authenticatedNeighborsLast10s
            val selfTestInput = remember(resolvedLinkState, effectiveIp, effectiveBcast, neighboursCount, elapsedZeroNeighboursSec) {
                MeshSelfTestInput(
                    linkState = resolvedLinkState,
                    localIp = effectiveIp,
                    broadcastAddress = effectiveBcast,
                    authenticatedNeighboursHeardLast10s = neighboursCount,
                    secondsWithZeroNeighbours = if (neighboursCount > 0) 0L else elapsedZeroNeighboursSec
                )
            }
            val selfTestState = MeshSelfTestMapper.map(selfTestInput)

            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MeshColors.LobbyBorder, RoundedCornerShape(12.dp))
                    .padding(bottom = 16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.mesh_selftest_hud_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = ElectricGreen,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.mesh_selftest_link_state, selfTestState.linkStateText),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.mesh_selftest_local_addresses, effectiveIp ?: "None", effectiveBcast),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.mesh_selftest_neighbours_heard, selfTestState.authenticatedNeighboursCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    if (selfTestState.authenticatedNeighboursCount == 0 && diagSnapshot.droppedBadVersion > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.mesh_selftest_bad_version_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = AlertRed
                        )
                    } else if (selfTestState.isolationWarningHint != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.mesh_selftest_no_neighbours_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = AlertRed
                        )
                    }
                    if (diagSnapshot.suspectedClockSkewMs > 0L && diagSnapshot.suspectedClockSkewPeerName != null) {
                        val skewSec = (diagSnapshot.suspectedClockSkewMs / 1000L).coerceAtLeast(1L)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.mesh_clock_skew_warning, diagSnapshot.suspectedClockSkewPeerName ?: "peer", skewSec),
                            style = MaterialTheme.typography.bodySmall,
                            color = AlertRed
                        )
                    }
                }
            }

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

            if (useAsBackup) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardDark),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, NeonOrange.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                ) {
                    Text(
                        text = stringResource(R.string.mesh_backup_setup_instructions),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(12.dp)
                    )
                }
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

                    val currentGeneratedCode = generatedCode
                    if (currentGeneratedCode == null) {
                        Button(
                            onClick = {
                                viewModel.createNewSession(saveAsBackup = useAsBackup) { code ->
                                    generatedCode = code
                                }
                            },
                            enabled = !isDerivingKey,
                            colors = ButtonDefaults.buttonColors(containerColor = NeonOrange),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isDerivingKey) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.Black,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(stringResource(R.string.mesh_lobby_generate_code), color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        val formattedCode = OfflineSession.format(currentGeneratedCode)

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
                                    val clip = ClipData.newPlainText(
                                        context.getString(R.string.mesh_lobby_channel_code_header),
                                        formattedCode
                                    )
                                    clip.description.extras = PersistableBundle().apply {
                                        putBoolean("android.content.extra.IS_SENSITIVE", true)
                                    }
                                    cm.setPrimaryClip(clip)
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
                                        viewModel.saveCurrentGeneratedAsBackupAndFinish {
                                            Toast.makeText(context, context.getString(R.string.mesh_backup_saved_toast), Toast.LENGTH_SHORT).show()
                                            onBackClick()
                                        }
                                    } else {
                                        onEnterSession()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricGreen),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1.5f)
                            ) {
                                val createCtaText = if (useAsBackup) {
                                    stringResource(R.string.mesh_settings_pair_code_action)
                                } else {
                                    stringResource(R.string.mesh_lobby_enter_room)
                                }
                                Text(createCtaText, color = Color.Black, fontWeight = FontWeight.Bold)
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
                            viewModel.clearSessionError()
                        },
                        label = { Text(stringResource(R.string.mesh_lobby_pairing_code_label)) },
                        placeholder = { Text(stringResource(R.string.mesh_lobby_pairing_code_placeholder)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { submitJoinCode() }
                        ),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            focusedBorderColor = NeonOrange,
                            unfocusedBorderColor = MeshColors.LobbyBorderUnfocused,
                            textColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (joinError != null || sessionError) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = stringResource(R.string.mesh_cd_error),
                                tint = AlertRed
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = joinError ?: stringResource(R.string.mesh_invalid_code),
                                color = AlertRed,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { submitJoinCode() },
                        enabled = !isDerivingKey && joinCodeInput.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonOrange),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isDerivingKey) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.Black,
                                strokeWidth = 2.dp
                            )
                        } else {
                            val joinCtaText = if (useAsBackup) {
                                stringResource(R.string.mesh_settings_pair_code_action)
                            } else {
                                stringResource(R.string.mesh_lobby_join_button)
                            }
                            Text(joinCtaText, color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
