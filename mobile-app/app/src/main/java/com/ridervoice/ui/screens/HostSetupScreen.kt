package com.ridervoice.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ridervoice.R
import com.ridervoice.navigation.ComputedDirections
import com.ridervoice.navigation.PlaceSuggestion
import com.ridervoice.navigation.ResolvedPlace
import com.ridervoice.ui.components.*
import com.ridervoice.ui.theme.*
import com.ridervoice.ui.viewmodels.HostSetupViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.ridervoice.permissions.LocationGateStatus
import com.ridervoice.permissions.MandatoryLocationGate
import com.ridervoice.ui.components.rvBringIntoViewOnFocus
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import com.ridervoice.ui.components.RvTextField
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostSetupScreen(
    viewModel: HostSetupViewModel = hiltViewModel(),
    onConvoyCreated: (String) -> Unit,
    onBackClick: () -> Unit,
    onImportRouteClick: () -> Unit = {}
) {
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val createState by viewModel.createState.collectAsStateWithLifecycle()
    val originSuggestions = collectSafeSuggestions(viewModel.originSuggestions)
    val destinationSuggestions = collectSafeSuggestions(viewModel.destinationSuggestions)
    val meetupSuggestions = collectSafeSuggestions(viewModel.meetupSuggestions)
    val isSearchingOrigin = collectSafeBoolean(viewModel.isSearchingOrigin)
    val isSearchingDestination = collectSafeBoolean(viewModel.isSearchingDestination)
    val isSearchingMeetup = collectSafeBoolean(viewModel.isSearchingMeetup)
    val selectedOrigin = collectSafeTyped<ResolvedPlace>(viewModel.selectedOrigin)
    val selectedDestination = collectSafeTyped<ResolvedPlace>(viewModel.selectedDestination)
    val selectedMeetup = collectSafeTyped<ResolvedPlace>(viewModel.selectedMeetup)
    val routePreview = collectSafeTyped<ComputedDirections>(viewModel.routePreview)

    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val feedback = LocalRvFeedback.current
    val (locationStatus, refreshLocationStatus) = rememberMandatoryLocationStatus()
    var showLocationGateDialog by remember { mutableStateOf(false) }
    var pendingCreateAfterLocationReady by remember { mutableStateOf(false) }
    var convoyName by rememberSaveable { mutableStateOf("") }
    var meetupPoint by rememberSaveable { mutableStateOf("") }
    var destination by rememberSaveable { mutableStateOf("") }
    var origin by rememberSaveable { mutableStateOf("") }
    var diagnosticsEnabled by rememberSaveable { mutableStateOf(false) }
    var moreOptionsExpanded by rememberSaveable { mutableStateOf(false) }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    var showLearnMoreSheet by rememberSaveable { mutableStateOf(false) }

    val gpsUnavailableHint = stringResource(R.string.host_gps_unavailable_hint)
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            viewModel.useCurrentLocationAsOrigin(
                onResolved = { resolvedName -> origin = resolvedName },
                onUnavailable = { feedback.showInfo(gpsUnavailableHint) }
            )
        } else {
            feedback.showInfo(gpsUnavailableHint)
        }
    }

    val clientRequestId = rememberSaveable { viewModel.getOrCreateClientRequestId() }

    val isDirty = convoyName.isNotBlank() || origin.isNotBlank() || destination.isNotBlank() || meetupPoint.isNotBlank()
    val isNameValid = convoyName.trim().length in 3..40
    val isOriginValid = origin.trim().length >= 2
    val isDestinationValid = destination.trim().length >= 2
    val isSubmitting = createState is HostSetupViewModel.CreateRideState.Submitting || isLoading
    val canCreate = isNameValid && isOriginValid && isDestinationValid && !isSubmitting

    val dispatchCreate = {
        val gate = MandatoryLocationGate.evaluate(context)
        if (gate == LocationGateStatus.READY) {
            viewModel.createConvoy(
                convoyName = convoyName.trim(),
                origin = origin.trim().ifBlank { null },
                destination = destination.trim().ifBlank { null },
                meetupPoint = meetupPoint.trim().ifBlank { null },
                diagnosticsEnabled = diagnosticsEnabled,
                clientRequestId = clientRequestId
            )
        } else {
            refreshLocationStatus()
            pendingCreateAfterLocationReady = true
            showLocationGateDialog = true
        }
    }

    LaunchedEffect(locationStatus) {
        if (locationStatus == LocationGateStatus.READY && showLocationGateDialog) {
            showLocationGateDialog = false
            if (pendingCreateAfterLocationReady) {
                pendingCreateAfterLocationReady = false
                if (canCreate) dispatchCreate()
            }
        }
    }

    if (showLocationGateDialog && locationStatus != LocationGateStatus.READY) {
        MandatoryLocationGateDialog(
            status = locationStatus,
            onDismiss = {
                pendingCreateAfterLocationReady = false
                showLocationGateDialog = false
            },
            onReady = {
                refreshLocationStatus()
                showLocationGateDialog = false
                if (pendingCreateAfterLocationReady && canCreate) {
                    pendingCreateAfterLocationReady = false
                    dispatchCreate()
                }
            }
        )
    }

    BackHandler {
        if (isDirty) {
            showDiscardDialog = true
        } else {
            viewModel.clearPendingAttempt()
            onBackClick()
        }
    }

    LaunchedEffect(createState) {
        if (createState is HostSetupViewModel.CreateRideState.Succeeded) {
            val name = (createState as HostSetupViewModel.CreateRideState.Succeeded).convoyName
            viewModel.onNavigationHandled()
            onConvoyCreated(name)
        }
    }

    if (showDiscardDialog) {
        ConfirmDialog(
            title = stringResource(R.string.host_discard_title),
            body = stringResource(R.string.host_discard_body),
            confirmLabel = stringResource(R.string.action_discard),
            dismissLabel = stringResource(R.string.host_keep_editing),
            onConfirm = {
                showDiscardDialog = false
                viewModel.clearPendingAttempt()
                onBackClick()
            },
            onDismiss = { showDiscardDialog = false },
            destructive = true
        )
    }

    if (showLearnMoreSheet) {
        AlertDialog(
            onDismissRequest = { showLearnMoreSheet = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = PaperSunken,
            title = {
                Text(
                    text = stringResource(R.string.create_ride_diagnostics_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.host_diag_learn_more_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkSecondary,
                    lineHeight = 21.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { showLearnMoreSheet = false },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Pop)
                ) {
                    Text(stringResource(R.string.action_got_it), color = PaperOnPop, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    val fieldColors = TextFieldDefaults.outlinedTextFieldColors(
        focusedBorderColor = Pop,
        unfocusedBorderColor = InkBorderStrong,
        containerColor = PaperSunken,
        textColor = Ink,
        cursorColor = Pop
    )

    RvScreen(
        title = stringResource(R.string.create_ride_title),
        onBack = {
            if (isDirty) {
                showDiscardDialog = true
            } else {
                viewModel.clearPendingAttempt()
                onBackClick()
            }
        },
        bottomBar = {
            PrimaryButton(
                text = stringResource(R.string.action_create_ride),
                onClick = dispatchCreate,
                enabled = canCreate,
                loading = isSubmitting
            )
        }
    ) {
        Spacer(modifier = Modifier.height(RvSpacing.sm))

        if (locationStatus != LocationGateStatus.READY) {
            MandatoryLocationRequiredBanner(
                status = locationStatus,
                onActionClick = { showLocationGateDialog = true }
            )
            Spacer(modifier = Modifier.height(RvSpacing.sm))
        }

        Text(
            text = stringResource(R.string.host_intro_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = InkSecondary
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 1. Ride Name (required, 3-40 chars)
        RvTextField(
            value = convoyName,
            onValueChange = { convoyName = it.take(40) },
            label = { Text(stringResource(R.string.create_ride_name_label)) },
            placeholder = { Text(stringResource(R.string.create_ride_name_placeholder), color = InkSecondary) },
            supportingText = {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.create_ride_name_helper), color = InkSecondary)
                    Text("${convoyName.length}/40", color = InkSecondary)
                }
            },
            isError = convoyName.isNotBlank() && !isNameValid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) }
            ),
            modifier = Modifier.fillMaxWidth().rvBringIntoViewOnFocus().testTag("host_convoy_name_field"),
            shape = RoundedCornerShape(16.dp),
            colors = fieldColors
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 2. Destination * (with Google Maps location autocomplete)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.host_route_section_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Ink
            )
            TextButton(
                onClick = {
                    val tmp = origin
                    origin = destination
                    destination = tmp
                    viewModel.swapEndpoints()
                },
                enabled = origin.isNotBlank() || destination.isNotBlank(),
                contentPadding = PaddingValues(horizontal = RvSpacing.sm, vertical = 0.dp),
                modifier = Modifier.heightIn(min = RvTouchTarget.min)
            ) {
                Icon(
                    imageVector = RvIcons.SwapVert,
                    contentDescription = stringResource(R.string.cd_swap_route),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(RvSpacing.xs))
                Text(
                    text = stringResource(R.string.route_invert_swap),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        RvTextField(
            value = destination,
            onValueChange = {
                destination = it
                viewModel.onDestinationQueryChanged(it)
            },
            label = { Text(stringResource(R.string.create_ride_destination_label)) },
            placeholder = { Text(stringResource(R.string.host_dest_placeholder), color = InkSecondary) },
            leadingIcon = {
                Icon(
                    imageVector = RvIcons.LocationOn,
                    contentDescription = null,
                    tint = Brick
                )
            },
            trailingIcon = {
                if (isSearchingDestination) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Pop
                    )
                } else if (selectedDestination != null) {
                    Text(
                        text = stringResource(R.string.host_route_pin_selected),
                        style = MaterialTheme.typography.labelSmall,
                        color = Moss,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = RvSpacing.sm)
                    )
                }
            },
            isError = destination.isNotBlank() && !isDestinationValid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(
                onNext = {
                    viewModel.dismissSuggestions()
                    focusManager.moveFocus(FocusDirection.Down)
                }
            ),
            modifier = Modifier.fillMaxWidth().rvBringIntoViewOnFocus().testTag("host_destination_field"),
            shape = RoundedCornerShape(16.dp),
            colors = fieldColors
        )

        if (destinationSuggestions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            PlaceSuggestionsDropdown(
                suggestions = destinationSuggestions,
                testTagPrefix = "host_dest_suggestion",
                onSelect = { suggestion ->
                    viewModel.selectDestinationSuggestion(suggestion) { resolved ->
                        destination = resolved
                    }
                    focusManager.clearFocus()
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 3. Starting Point * (with Google Maps autocomplete + current GPS button)
        RvTextField(
            value = origin,
            onValueChange = {
                origin = it
                viewModel.onOriginQueryChanged(it)
            },
            label = { Text(stringResource(R.string.host_start_label)) },
            placeholder = { Text(stringResource(R.string.host_start_placeholder), color = InkSecondary) },
            supportingText = {
                Text(
                    text = stringResource(R.string.host_route_required_hint),
                    color = InkSecondary
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = RvIcons.MyLocation,
                    contentDescription = null,
                    tint = Moss
                )
            },
            trailingIcon = {
                if (isSearchingOrigin) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Pop
                    )
                } else {
                    IconButton(
                        onClick = {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        },
                        modifier = Modifier.testTag("host_use_gps_start_btn")
                    ) {
                        Icon(
                            imageVector = RvIcons.MyLocation,
                            contentDescription = stringResource(R.string.cd_use_current_gps_start),
                            tint = Pop
                        )
                    }
                }
            },
            isError = origin.isNotBlank() && !isOriginValid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    viewModel.dismissSuggestions()
                    focusManager.clearFocus()
                    if (canCreate) {
                        dispatchCreate()
                    }
                }
            ),
            modifier = Modifier.fillMaxWidth().rvBringIntoViewOnFocus().testTag("host_origin_field"),
            shape = RoundedCornerShape(16.dp),
            colors = fieldColors
        )

        if (originSuggestions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            PlaceSuggestionsDropdown(
                suggestions = originSuggestions,
                testTagPrefix = "host_origin_suggestion",
                onSelect = { suggestion ->
                    viewModel.selectOriginSuggestion(suggestion) { resolved ->
                        origin = resolved
                    }
                    focusManager.clearFocus()
                }
            )
        }

        if (routePreview != null || (selectedOrigin != null && selectedDestination != null)) {
            Spacer(modifier = Modifier.height(10.dp))
            val preview = routePreview
            Surface(
                color = MossContainer,
                shape = RoundedCornerShape(RvRadius.md),
                border = BorderStroke(1.dp, Moss.copy(alpha = 0.35f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("host_route_preview_card")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = RvSpacing.md, vertical = RvSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RvSpacing.sm)
                ) {
                    Icon(
                        imageVector = RvIcons.Map,
                        contentDescription = null,
                        tint = Moss,
                        modifier = Modifier.size(18.dp)
                    )
                    val summaryText = if (preview != null) {
                        val km = ((preview.distanceMeters / 100.0).roundToInt() / 10.0).toString()
                        stringResource(R.string.host_route_preview_summary, km, preview.durationMinutes)
                    } else {
                        "${selectedOrigin?.name ?: origin} → ${selectedDestination?.name ?: destination}"
                    }
                    Text(
                        text = summaryText,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 4. Meet-up point (optional with autocomplete for directional pin)
        RvTextField(
            value = meetupPoint,
            onValueChange = {
                meetupPoint = it
                viewModel.onMeetupQueryChanged(it)
            },
            label = { Text(stringResource(R.string.create_ride_meetup_label)) },
            placeholder = { Text(stringResource(R.string.host_meetup_placeholder), color = InkSecondary) },
            trailingIcon = {
                if (isSearchingMeetup) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Pop
                    )
                } else if (selectedMeetup != null) {
                    Text(
                        text = stringResource(R.string.host_route_pin_selected),
                        style = MaterialTheme.typography.labelSmall,
                        color = Moss,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = RvSpacing.sm)
                    )
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) }
            ),
            modifier = Modifier.fillMaxWidth().rvBringIntoViewOnFocus().testTag("host_meetup_field"),
            shape = RoundedCornerShape(16.dp),
            colors = fieldColors
        )

        if (meetupSuggestions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            PlaceSuggestionsDropdown(
                suggestions = meetupSuggestions,
                testTagPrefix = "host_meetup_suggestion",
                onSelect = { suggestion ->
                    viewModel.selectMeetupSuggestion(suggestion) { resolved ->
                        meetupPoint = resolved
                    }
                    focusManager.clearFocus()
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 5. Collapsible "More options" Bento Card
        Surface(
            color = PaperRaised,
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, InkHairline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            role = Role.Button,
                            onClickLabel = stringResource(R.string.create_ride_more_options)
                        ) { moreOptionsExpanded = !moreOptionsExpanded },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = RvIcons.Tune,
                            contentDescription = null,
                            tint = Pop,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.create_ride_more_options),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Ink
                        )
                    }
                    Icon(
                        imageVector = if (moreOptionsExpanded) RvIcons.ExpandLess else RvIcons.ExpandMore,
                        contentDescription = null,
                        tint = InkSecondary
                    )
                }

                AnimatedVisibility(visible = moreOptionsExpanded) {
                    Column(modifier = Modifier.padding(top = 14.dp)) {
                        Divider(color = InkHairline)
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = diagnosticsEnabled,
                                    role = Role.Switch,
                                    onValueChange = { diagnosticsEnabled = it }
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.create_ride_diagnostics_title),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Ink
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.create_ride_diagnostics_help),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = InkSecondary
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Switch(
                                checked = diagnosticsEnabled,
                                onCheckedChange = null,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = PaperOnPop,
                                    checkedTrackColor = Pop,
                                    uncheckedThumbColor = InkSecondary,
                                    uncheckedTrackColor = PaperSunken
                                )
                            )
                        }
                        TextButton(
                            onClick = { showLearnMoreSheet = true },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(stringResource(R.string.action_learn_more), color = Pop, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        SecondaryButton(
                            text = stringResource(R.string.host_import_route),
                            onClick = onImportRouteClick,
                            leadingIcon = RvIcons.Map
                        )
                    }
                }
            }
        }

        if (error != null) {
            Spacer(modifier = Modifier.height(14.dp))
            InlineError(
                message = error ?: stringResource(R.string.host_create_failed),
                onRetry = dispatchCreate
            )
        }

        Spacer(modifier = Modifier.height(RvSpacing.xl))
    }
}

@Composable
private fun collectSafeBoolean(flow: kotlinx.coroutines.flow.Flow<Boolean>): Boolean {
    val anyFlow: kotlinx.coroutines.flow.Flow<Any?> = flow
    val raw by anyFlow.collectAsStateWithLifecycle(initialValue = false)
    return raw as? Boolean ?: false
}

@Composable
private fun collectSafeSuggestions(
    flow: kotlinx.coroutines.flow.Flow<List<PlaceSuggestion>>
): List<PlaceSuggestion> {
    val anyFlow: kotlinx.coroutines.flow.Flow<Any?> = flow
    val raw by anyFlow.collectAsStateWithLifecycle(initialValue = emptyList<PlaceSuggestion>())
    return (raw as? List<*>)?.filterIsInstance<PlaceSuggestion>().orEmpty()
}

@Composable
private inline fun <reified T : Any> collectSafeTyped(
    flow: kotlinx.coroutines.flow.Flow<T?>
): T? {
    val anyFlow: kotlinx.coroutines.flow.Flow<Any?> = flow
    val raw by anyFlow.collectAsStateWithLifecycle(initialValue = null)
    return raw as? T
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceSuggestionsDropdown(
    suggestions: List<PlaceSuggestion>,
    testTagPrefix: String,
    onSelect: (PlaceSuggestion) -> Unit
) {
    Surface(
        color = PaperRaised,
        shape = RoundedCornerShape(RvRadius.md),
        border = BorderStroke(1.dp, InkHairline),
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            suggestions.take(5).forEachIndexed { idx, item ->
                if (idx > 0) {
                    Divider(color = InkHairline)
                }
                Surface(
                    onClick = { onSelect(item) },
                    color = PaperRaised,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = RvTouchTarget.min)
                        .testTag("${testTagPrefix}_$idx")
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
                            tint = Pop,
                            modifier = Modifier.size(18.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.primaryText,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (item.secondaryText.isNotBlank()) {
                                Text(
                                    text = item.secondaryText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = InkSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
