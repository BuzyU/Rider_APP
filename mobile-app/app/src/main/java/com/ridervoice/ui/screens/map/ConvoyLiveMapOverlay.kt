@file:OptIn(ExperimentalMaterial3Api::class)

package com.ridervoice.ui.screens.map

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.ridervoice.R
import com.ridervoice.navigation.GoogleMapsProvider
import com.ridervoice.navigation.Waypoint
import com.ridervoice.ui.components.LocalRvFeedback
import com.ridervoice.ui.theme.Ink
import com.ridervoice.ui.theme.InkBorderStrong
import com.ridervoice.ui.theme.InkDisabled
import com.ridervoice.ui.theme.InkHairline
import com.ridervoice.ui.theme.InkSecondary
import com.ridervoice.ui.theme.Moss
import com.ridervoice.ui.theme.MossContainer
import com.ridervoice.ui.theme.Paper
import com.ridervoice.ui.theme.PaperOnPop
import com.ridervoice.ui.theme.PaperRaised
import com.ridervoice.ui.theme.PaperSunken
import com.ridervoice.ui.theme.Pop
import com.ridervoice.ui.theme.PopContainer
import com.ridervoice.ui.theme.RvIcons
import com.ridervoice.ui.theme.RvRadius
import com.ridervoice.ui.theme.RvSpacing
import com.ridervoice.ui.theme.RvTouchTarget
import com.ridervoice.ui.theme.StatusWarn
import com.ridervoice.ui.theme.StatusWarnContainer
import com.ridervoice.ui.theme.ZeroWindowInsets
import com.ridervoice.ui.theme.rvBottomSafeArea
import com.ridervoice.ui.theme.rvTopSafeArea
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Wraps the active ride voice HUD without altering frozen RoomScreen.kt,
 * providing a clean toggle between voice controls and the live convoy Google Map.
 */
@Composable
fun ConvoyRoomWithMapHost(
    roomName: String,
    isOfflineMeshActive: Boolean = false,
    viewModel: ConvoyMapViewModel = hiltViewModel(),
    voiceContent: @Composable () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var isMapOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(roomName) {
        viewModel.refreshTrackingIfPermitted()
        viewModel.refreshRoute(roomName)
    }

    LaunchedEffect(isMapOpen) {
        if (isMapOpen) {
            viewModel.refreshRoute(roomName)
        }
    }

    BackHandler(enabled = isMapOpen) {
        isMapOpen = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Keep the active voice room composable mounted so voice audio and GPS sync stay uninterrupted.
        voiceContent()

        if (!isMapOpen) {
            ConvoyMapEntryPill(
                ridersOnMapCount = state.ridersWithLocation.size,
                totalRidersCount = state.riders.size,
                onClick = {
                    viewModel.refreshTrackingIfPermitted()
                    isMapOpen = true
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .rvBottomSafeArea()
                    .padding(end = RvSpacing.lg, bottom = 128.dp)
            )
        }

        AnimatedVisibility(
            visible = isMapOpen,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val feedback = LocalRvFeedback.current
            val context = LocalContext.current
            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { grants ->
                val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                viewModel.onLocationPermissionResult(granted)
            }
            val noNavAppError = stringResource(R.string.map_no_nav_app_error)

            ConvoyLiveMapScreen(
                roomName = roomName,
                state = state,
                isOfflineMeshActive = isOfflineMeshActive,
                onBackToVoice = { isMapOpen = false },
                onSelectRider = { viewModel.selectRider(it) },
                onCycleMapStyle = { viewModel.cycleMapStyle() },
                onFitConvoy = { viewModel.requestFitConvoy() },
                onCenterSelf = { viewModel.requestCenterOnSelf() },
                onUserCameraGesture = { viewModel.disableAutoFollow() },
                onRequestLocationPermission = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                },
                onNavigateToRider = { rider ->
                    val lat = rider.lat
                    val lng = rider.lng
                    if (lat != null && lng != null) {
                        val opened = launchExternalNavigation(context, lat, lng)
                        if (!opened) {
                            feedback.showError(noNavAppError)
                        }
                    }
                },
                onNavigateToRouteDestination = {
                    val dest = state.route?.destinationLatLng
                    if (dest != null) {
                        val opened = launchExternalNavigation(context, dest.latitude, dest.longitude)
                        if (!opened) {
                            feedback.showError(noNavAppError)
                        }
                    }
                }
            )
        }
    }
}

@Composable
fun ConvoyMapEntryPill(
    ridersOnMapCount: Int,
    totalRidersCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val pillText = if (totalRidersCount > 0) {
        stringResource(R.string.map_entry_pill_count, ridersOnMapCount, totalRidersCount)
    } else {
        stringResource(R.string.map_entry_pill_label)
    }
    val openCd = stringResource(R.string.cd_open_convoy_map)

    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        shape = RoundedCornerShape(RvRadius.pill),
        color = Pop,
        contentColor = PaperOnPop,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, InkHairline),
        modifier = modifier
            .heightIn(min = RvTouchTarget.min)
            .widthIn(min = 104.dp, max = 188.dp)
            .testTag("convoy_map_entry_pill")
            .semantics { contentDescription = openCd }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = RvSpacing.lg, vertical = RvSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RvSpacing.sm)
        ) {
            Icon(
                imageVector = RvIcons.Map,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = pillText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ConvoyLiveMapScreen(
    roomName: String,
    state: ConvoyMapUiState,
    isOfflineMeshActive: Boolean,
    onBackToVoice: () -> Unit,
    onSelectRider: (String?) -> Unit,
    onCycleMapStyle: () -> Unit,
    onFitConvoy: () -> Unit,
    onCenterSelf: () -> Unit,
    onUserCameraGesture: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    onNavigateToRider: (RiderMapItem) -> Unit,
    onNavigateToRouteDestination: () -> Unit = {},
    modifier: Modifier = Modifier,
    mapViewportOverride: (@Composable (
        state: ConvoyMapUiState,
        onMarkerClick: (String) -> Unit,
        onCameraGesture: () -> Unit,
        modifier: Modifier
    ) -> Unit)? = null
) {
    val effectiveTitle = state.rideName.ifBlank { roomName }
    val ridersOnMap = state.ridersWithLocation.size
    val totalRiders = state.riders.size

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("convoy_live_map_screen"),
        containerColor = Paper,
        contentWindowInsets = ZeroWindowInsets,
        topBar = {
            ConvoyMapTopBar(
                title = effectiveTitle,
                ridersOnMapCount = ridersOnMap,
                totalRidersCount = totalRiders,
                isOfflineMeshActive = isOfflineMeshActive,
                canFitAll = state.hasAnyMapCoordinates,
                onBackToVoice = onBackToVoice,
                onFitAll = onFitConvoy
            )
        },
        bottomBar = {
            ConvoyMapBottomDeck(
                state = state,
                onSelectRider = onSelectRider,
                onNavigateToRider = onNavigateToRider
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Paper)
        ) {
            if (mapViewportOverride != null) {
                mapViewportOverride(
                    state,
                    { riderId -> onSelectRider(riderId) },
                    onUserCameraGesture,
                    Modifier.fillMaxSize()
                )
            } else {
                ConvoyGoogleMapViewport(
                    state = state,
                    onMarkerClick = { riderId -> onSelectRider(riderId) },
                    onCameraGesture = onUserCameraGesture,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Contextual non-blocking status & hosted route banners near the top of the map
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = RvSpacing.md, vertical = RvSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(RvSpacing.sm)
            ) {
                if (state.route?.hasAnyRouteInfo == true) {
                    HostedRouteBanner(
                        route = state.route,
                        onNavigateClick = onNavigateToRouteDestination
                    )
                }
                if (!state.hasLocationPermission) {
                    LocationPermissionPromptBanner(
                        onGrantClick = onRequestLocationPermission
                    )
                } else if (ridersOnMap == 0 && state.route?.destinationLatLng == null) {
                    WaitingForGpsBanner()
                }
            }

            // Compact right-hand map tool rail (Fit Convoy / My Location / Map Type)
            ConvoyMapToolRail(
                mapStyle = state.mapStyle,
                hasSelfLocation = state.selfRider?.hasLocation == true,
                hasAnyRiderLocation = state.hasAnyMapCoordinates,
                onFitConvoy = onFitConvoy,
                onCenterSelf = {
                    if (state.hasLocationPermission) {
                        onCenterSelf()
                    } else {
                        onRequestLocationPermission()
                    }
                },
                onCycleMapStyle = onCycleMapStyle,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = RvSpacing.md, bottom = RvSpacing.md)
            )
        }
    }
}

@Composable
private fun ConvoyMapTopBar(
    title: String,
    ridersOnMapCount: Int,
    totalRidersCount: Int,
    isOfflineMeshActive: Boolean,
    canFitAll: Boolean,
    onBackToVoice: () -> Unit,
    onFitAll: () -> Unit
) {
    val subtitle = if (isOfflineMeshActive) {
        stringResource(R.string.map_offline_voice_gps_hint, ridersOnMapCount, totalRidersCount)
    } else {
        stringResource(R.string.map_riders_on_map_count, ridersOnMapCount, totalRidersCount)
    }

    Surface(
        color = PaperRaised,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, InkHairline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .rvTopSafeArea()
                .padding(horizontal = RvSpacing.md, vertical = RvSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RvSpacing.sm)
        ) {
            val backCd = stringResource(R.string.cd_back_to_voice)
            Surface(
                onClick = onBackToVoice,
                shape = RoundedCornerShape(RvRadius.pill),
                color = PopContainer,
                contentColor = Pop,
                border = BorderStroke(1.dp, Pop.copy(alpha = 0.25f)),
                modifier = Modifier
                    .heightIn(min = RvTouchTarget.min)
                    .testTag("map_back_to_voice_btn")
                    .semantics { contentDescription = backCd }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = RvSpacing.md, vertical = RvSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RvSpacing.xs)
                ) {
                    Icon(
                        imageVector = RvIcons.Back,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.map_back_to_voice),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isOfflineMeshActive) StatusWarn else InkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val fitCd = stringResource(R.string.cd_fit_convoy)
            Surface(
                onClick = onFitAll,
                enabled = canFitAll,
                shape = RoundedCornerShape(RvRadius.pill),
                color = if (canFitAll) PaperSunken else Paper,
                contentColor = if (canFitAll) Ink else InkDisabled,
                border = BorderStroke(1.dp, InkHairline),
                modifier = Modifier
                    .heightIn(min = RvTouchTarget.min)
                    .testTag("map_top_fit_all_btn")
                    .semantics { contentDescription = fitCd }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = RvSpacing.md, vertical = RvSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RvSpacing.xs)
                ) {
                    Icon(
                        imageVector = RvIcons.Group,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.map_fit_all_action),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

@Composable
private fun LocationPermissionPromptBanner(
    onGrantClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(RvRadius.md),
        color = StatusWarnContainer,
        border = BorderStroke(1.dp, StatusWarn.copy(alpha = 0.45f)),
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("map_permission_banner")
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
                tint = StatusWarn,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = stringResource(R.string.map_permission_banner_title),
                style = MaterialTheme.typography.bodySmall,
                color = Ink,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onGrantClick,
                modifier = Modifier.heightIn(min = RvTouchTarget.min)
            ) {
                Text(
                    text = stringResource(R.string.map_permission_grant_cta),
                    color = StatusWarn,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun WaitingForGpsBanner() {
    Surface(
        shape = RoundedCornerShape(RvRadius.pill),
        color = PaperRaised,
        border = BorderStroke(1.dp, InkHairline),
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("map_waiting_gps_banner")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = RvSpacing.md, vertical = RvSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RvSpacing.sm)
        ) {
            Icon(
                imageVector = RvIcons.MyLocation,
                contentDescription = null,
                tint = Pop,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = stringResource(R.string.map_waiting_for_gps),
                style = MaterialTheme.typography.bodySmall,
                color = InkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ConvoyMapToolRail(
    mapStyle: ConvoyMapStyle,
    hasSelfLocation: Boolean,
    hasAnyRiderLocation: Boolean,
    onFitConvoy: () -> Unit,
    onCenterSelf: () -> Unit,
    onCycleMapStyle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val styleLabel = when (mapStyle) {
        ConvoyMapStyle.STANDARD -> stringResource(R.string.map_type_standard)
        ConvoyMapStyle.TERRAIN -> stringResource(R.string.map_type_terrain)
        ConvoyMapStyle.SATELLITE -> stringResource(R.string.map_type_satellite)
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(RvSpacing.sm),
        horizontalAlignment = Alignment.End
    ) {
        val styleCd = stringResource(R.string.cd_cycle_map_type)
        Surface(
            onClick = onCycleMapStyle,
            shape = RoundedCornerShape(RvRadius.pill),
            color = PaperRaised,
            contentColor = Ink,
            border = BorderStroke(1.dp, InkHairline),
            shadowElevation = 4.dp,
            modifier = Modifier
                .heightIn(min = RvTouchTarget.min)
                .testTag("map_cycle_style_btn")
                .semantics { contentDescription = styleCd }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = RvSpacing.md, vertical = RvSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RvSpacing.xs)
            ) {
                Icon(
                    imageVector = RvIcons.Layers,
                    contentDescription = null,
                    tint = Pop,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = styleLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        val recenterCd = stringResource(R.string.cd_recenter_me)
        Surface(
            onClick = onCenterSelf,
            shape = CircleShape,
            color = if (hasSelfLocation) PaperRaised else PaperSunken,
            contentColor = if (hasSelfLocation) Pop else InkSecondary,
            border = BorderStroke(1.dp, InkHairline),
            shadowElevation = 4.dp,
            modifier = Modifier
                .size(RvTouchTarget.min)
                .testTag("map_recenter_me_btn")
                .semantics { contentDescription = recenterCd }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = RvIcons.MyLocation,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        if (hasAnyRiderLocation) {
            val fitCd = stringResource(R.string.cd_fit_convoy)
            Surface(
                onClick = onFitConvoy,
                shape = CircleShape,
                color = PaperRaised,
                contentColor = Ink,
                border = BorderStroke(1.dp, InkHairline),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .size(RvTouchTarget.min)
                    .testTag("map_rail_fit_all_btn")
                    .semantics { contentDescription = fitCd }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = RvIcons.Group,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ConvoyMapBottomDeck(
    state: ConvoyMapUiState,
    onSelectRider: (String?) -> Unit,
    onNavigateToRider: (RiderMapItem) -> Unit
) {
    Surface(
        color = PaperRaised,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, InkHairline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .rvBottomSafeArea()
                .padding(top = RvSpacing.sm, bottom = RvSpacing.md),
            verticalArrangement = Arrangement.spacedBy(RvSpacing.sm)
        ) {
            state.selectedRider?.let { selected ->
                SelectedRiderDetailCard(
                    rider = selected,
                    onDismiss = { onSelectRider(null) },
                    onNavigate = { onNavigateToRider(selected) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = RvSpacing.md)
                )
            }

            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("map_rider_chips_row"),
                contentPadding = PaddingValues(horizontal = RvSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(RvSpacing.sm)
            ) {
                items(state.riders, key = { it.riderId }) { rider ->
                    RiderMapChip(
                        rider = rider,
                        isSelected = rider.riderId == state.selectedRiderId,
                        onClick = { onSelectRider(rider.riderId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedRiderDetailCard(
    rider: RiderMapItem,
    onDismiss: () -> Unit,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val youLabel = stringResource(R.string.map_rider_you_label)
    val label = if (rider.isSelf) youLabel else rider.displayName
    val distanceLabel = formatDistanceText(rider.distanceFromSelfMeters)
    val speedLabel = stringResource(R.string.speed_kmh_value, rider.speedKmh)
    val freshnessLabel = formatFreshnessText(rider)
    val detailParts = buildList {
        if (!rider.isSelf && distanceLabel != null) add(distanceLabel)
        if (rider.hasLocation) add(speedLabel)
        add(freshnessLabel)
    }.joinToString(" · ")

    Surface(
        shape = RoundedCornerShape(RvRadius.md),
        color = PaperSunken,
        border = BorderStroke(1.dp, if (rider.isSpeaking) Moss else InkHairline),
        modifier = modifier.testTag("selected_rider_detail_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = RvSpacing.md, vertical = RvSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RvSpacing.sm)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            rider.isSpeaking -> MossContainer
                            rider.isSelf -> PopContainer
                            else -> PaperRaised
                        }
                    )
                    .border(
                        width = 1.5.dp,
                        color = when {
                            rider.isSpeaking -> Moss
                            rider.isSelf -> Pop
                            else -> InkBorderStrong
                        },
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = riderInitial(label),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        rider.isSpeaking -> Moss
                        rider.isSelf -> Pop
                        else -> Ink
                    }
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = detailParts,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (rider.isSpeaking) Moss else InkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (rider.hasLocation && !rider.isSelf) {
                val navCd = stringResource(R.string.cd_navigate_to_selected_rider, label)
                Button(
                    onClick = onNavigate,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Pop,
                        contentColor = PaperOnPop
                    ),
                    shape = RoundedCornerShape(RvRadius.pill),
                    contentPadding = PaddingValues(horizontal = RvSpacing.md, vertical = RvSpacing.xs),
                    modifier = Modifier
                        .heightIn(min = RvTouchTarget.min)
                        .testTag("selected_rider_navigate_btn")
                        .semantics { contentDescription = navCd }
                ) {
                    Icon(
                        imageVector = RvIcons.Navigation,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(RvSpacing.xs))
                    Text(
                        text = stringResource(R.string.map_navigate_action),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            } else {
                val closeCd = stringResource(R.string.cd_close)
                FilledTonalButton(
                    onClick = onDismiss,
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier
                        .size(RvTouchTarget.min)
                        .semantics { contentDescription = closeCd }
                ) {
                    Icon(
                        imageVector = RvIcons.Close,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RiderMapChip(
    rider: RiderMapItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val youLabel = stringResource(R.string.map_rider_you_label)
    val label = if (rider.isSelf) youLabel else rider.displayName
    val distanceText = formatDistanceText(rider.distanceFromSelfMeters)
    val subLine = when {
        rider.isSelf && rider.hasLocation -> stringResource(R.string.speed_kmh_value, rider.speedKmh)
        !rider.hasLocation -> stringResource(R.string.map_rider_no_gps)
        rider.isSpeaking -> stringResource(R.string.map_rider_speaking)
        distanceText != null -> distanceText
        else -> formatFreshnessText(rider)
    }

    val dotColor = Color(ConvoyMapViewModel.riderBlipColorArgb(rider))

    val borderColor = when {
        isSelected -> Pop
        rider.isSpeaking -> Moss
        else -> InkHairline
    }

    val containerColor = when {
        isSelected -> PopContainer
        else -> PaperSunken
    }

    Surface(
        shape = RoundedCornerShape(RvRadius.md),
        color = containerColor,
        border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, borderColor),
        modifier = Modifier
            .heightIn(min = 52.dp)
            .widthIn(min = 120.dp, max = 188.dp)
            .clip(RoundedCornerShape(RvRadius.md))
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("rider_map_chip_${rider.riderId}")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = RvSpacing.md, vertical = RvSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RvSpacing.sm)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(dotColor)
                    .border(
                        width = if (rider.isSpeaking) 2.dp else 1.dp,
                        color = if (rider.isSpeaking) Moss else PaperRaised,
                        shape = CircleShape
                    )
            )
            Column(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (rider.isSpeaking) Moss else InkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun HostedRouteBanner(
    route: ConvoyRouteUiState,
    onNavigateClick: () -> Unit
) {
    val routeText = listOfNotNull(
        route.originName?.takeIf { it.isNotBlank() },
        route.destinationName?.takeIf { it.isNotBlank() }
    ).joinToString(" → ")

    val etaParts = buildList {
        route.distanceMeters?.takeIf { it > 0 }?.let { dist ->
            val km = ((dist / 100.0).roundToInt() / 10.0).toString()
            add("$km km")
        }
        route.estimatedDurationMin?.takeIf { it > 0 }?.let { mins ->
            add("~$mins min")
        }
    }.joinToString(" · ")

    Surface(
        shape = RoundedCornerShape(RvRadius.md),
        color = PaperRaised,
        border = BorderStroke(1.dp, InkHairline),
        shadowElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("map_hosted_route_banner")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = RvSpacing.md, vertical = RvSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RvSpacing.sm)
        ) {
            Icon(
                imageVector = RvIcons.Map,
                contentDescription = null,
                tint = Pop,
                modifier = Modifier.size(18.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = routeText,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (etaParts.isNotBlank()) {
                    Text(
                        text = etaParts,
                        style = MaterialTheme.typography.labelSmall,
                        color = Moss,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (route.destinationLatLng != null) {
                val navRouteCd = stringResource(R.string.cd_navigate_to_route_dest)
                Surface(
                    onClick = onNavigateClick,
                    shape = RoundedCornerShape(RvRadius.pill),
                    color = PopContainer,
                    contentColor = Pop,
                    border = BorderStroke(1.dp, Pop.copy(alpha = 0.25f)),
                    modifier = Modifier
                        .heightIn(min = RvTouchTarget.min)
                        .testTag("map_route_directions_btn")
                        .semantics { contentDescription = navRouteCd }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = RvSpacing.md, vertical = RvSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(RvSpacing.xs)
                    ) {
                        Icon(
                            imageVector = RvIcons.Navigation,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.map_route_go_cta),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConvoyGoogleMapViewport(
    state: ConvoyMapUiState,
    onMarkerClick: (String) -> Unit,
    onCameraGesture: () -> Unit,
    modifier: Modifier = Modifier
) {
    val defaultLatLng = LatLng(20.5937, 78.9629)
    val initialFocus = state.ridersWithLocation.firstOrNull()?.latLng
        ?: state.route?.originLatLng
        ?: state.route?.destinationLatLng
        ?: defaultLatLng
    val cameraPositionState: CameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            initialFocus,
            if (state.hasAnyMapCoordinates) 13.5f else 4.5f
        )
    }
    var hasPerformedInitialFit by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val youLabel = stringResource(R.string.map_rider_you_label)
    val speakingLabel = stringResource(R.string.map_rider_speaking)
    val liveLabel = stringResource(R.string.map_rider_live_now)
    val startSnippet = stringResource(R.string.map_route_start_snippet)
    val destSnippet = stringResource(R.string.map_route_dest_snippet)

    val selfColor = Color(ConvoyMapViewModel.SELF_BLIP_COLOR_ARGB)
    val haloFill = selfColor.copy(alpha = 0.16f)
    val haloStroke = selfColor.copy(alpha = 0.65f)
    val markerBorderArgb = PaperRaised.toArgb()
    val markerTextArgb = PaperOnPop.toArgb()
    val routeStrokeColor = Pop
    val routeOutlineColor = Ink

    // Track user drag/pinch so camera never fights manual map exploration.
    LaunchedEffect(cameraPositionState.isMoving, cameraPositionState.cameraMoveStartedReason) {
        if (cameraPositionState.isMoving &&
            cameraPositionState.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE
        ) {
            onCameraGesture()
        }
    }

    // Perform an initial camera frame once GPS fixes or route bounds arrive.
    LaunchedEffect(
        state.ridersWithLocation.size,
        state.route?.originLatLng,
        state.route?.destinationLatLng
    ) {
        if (!hasPerformedInitialFit && state.hasAnyMapCoordinates) {
            hasPerformedInitialFit = true
            animateCameraToConvoy(cameraPositionState, state.ridersWithLocation, state.route)
        }
    }

    // Respond to explicit Fit Convoy clicks.
    LaunchedEffect(state.fitConvoySignal) {
        if (state.fitConvoySignal > 0 && state.hasAnyMapCoordinates) {
            animateCameraToConvoy(cameraPositionState, state.ridersWithLocation, state.route)
        }
    }

    // Respond to explicit Center on Self clicks or follow mode updates.
    LaunchedEffect(state.centerSelfSignal, state.selfRider?.lat, state.selfRider?.lng) {
        val selfLatLng = state.selfRider?.latLng
        if (selfLatLng != null && (state.centerSelfSignal > 0 && state.isFollowingSelf)) {
            cameraPositionState.animate(
                update = CameraUpdateFactory.newLatLngZoom(
                    selfLatLng,
                    maxOf(cameraPositionState.position.zoom, 15.5f)
                ),
                durationMs = 450
            )
        }
    }

    // Smoothly focus camera when user selects a rider from the bottom chip strip.
    LaunchedEffect(state.selectedRiderId) {
        val target = state.selectedRider?.latLng
        if (target != null) {
            cameraPositionState.animate(
                update = CameraUpdateFactory.newLatLngZoom(
                    target,
                    maxOf(cameraPositionState.position.zoom, 15.5f)
                ),
                durationMs = 400
            )
        }
    }

    val mapUiSettings = remember {
        MapUiSettings(
            compassEnabled = true,
            indoorLevelPickerEnabled = false,
            mapToolbarEnabled = false,
            myLocationButtonEnabled = false,
            rotationGesturesEnabled = true,
            scrollGesturesEnabled = true,
            tiltGesturesEnabled = false,
            zoomControlsEnabled = false,
            zoomGesturesEnabled = true
        )
    }

    val mapProperties = MapProperties(
        mapType = state.mapStyle.mapType,
        isMyLocationEnabled = state.hasLocationPermission
    )

    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraPositionState,
        properties = mapProperties,
        uiSettings = mapUiSettings
    ) {
        // 1. Hosted Convoy Route Polyline & Endpoint Pins (Start A -> Destination B)
        val activeRoute = state.route
        if (activeRoute != null) {
            if (activeRoute.polylinePoints.size >= 2) {
                Polyline(
                    points = activeRoute.polylinePoints,
                    color = routeOutlineColor.copy(alpha = 0.45f),
                    width = 16f,
                    zIndex = 0.5f
                )
                Polyline(
                    points = activeRoute.polylinePoints,
                    color = routeStrokeColor,
                    width = 10f,
                    zIndex = 0.6f
                )
            } else if (activeRoute.originLatLng != null && activeRoute.destinationLatLng != null) {
                Polyline(
                    points = listOf(activeRoute.originLatLng, activeRoute.destinationLatLng),
                    color = routeStrokeColor,
                    width = 8f,
                    pattern = listOf(Dash(22f), Gap(14f)),
                    zIndex = 0.6f
                )
            }

            activeRoute.originLatLng?.let { startLatLng ->
                val startIcon = remember(startLatLng, markerBorderArgb) {
                    createRiderBadgeDescriptor(
                        initial = "A",
                        fillColorArgb = ConvoyMapViewModel.START_PIN_COLOR_ARGB,
                        borderColorArgb = markerBorderArgb,
                        textColorArgb = markerTextArgb,
                        isHighlighted = false
                    )
                }
                Marker(
                    state = MarkerState(position = startLatLng),
                    title = activeRoute.originName ?: startSnippet,
                    snippet = startSnippet,
                    icon = startIcon,
                    zIndex = 1.5f
                )
            }

            activeRoute.destinationLatLng?.let { destLatLng ->
                val destIcon = remember(destLatLng, markerBorderArgb) {
                    createRiderBadgeDescriptor(
                        initial = "B",
                        fillColorArgb = ConvoyMapViewModel.DEST_PIN_COLOR_ARGB,
                        borderColorArgb = markerBorderArgb,
                        textColorArgb = markerTextArgb,
                        isHighlighted = true
                    )
                }
                Marker(
                    state = MarkerState(position = destLatLng),
                    title = activeRoute.destinationName ?: destSnippet,
                    snippet = destSnippet,
                    icon = destIcon,
                    zIndex = 2.0f
                )
            }
        }

        // 2. Self Blue Halo Ring
        state.selfRider?.latLng?.let { selfPos ->
            Circle(
                center = selfPos,
                radius = 45.0,
                fillColor = haloFill,
                strokeColor = haloStroke,
                strokeWidth = 2.5f
            )
        }

        // 3. Distinct Rider Blips (Blue for Self, Unique Non-Blue Colors for Remote Riders)
        state.ridersWithLocation.forEach { rider ->
            val pos = rider.latLng ?: return@forEach
            val markerTitle = if (rider.isSelf) youLabel else rider.displayName
            val markerSnippet = buildString {
                if (rider.isSpeaking) {
                    append(speakingLabel)
                    append(" · ")
                }
                append("${rider.speedKmh} km/h")
                if (!rider.isSelf) {
                    append(" · ")
                    append(if (rider.isLocationRecent) liveLabel else "")
                }
            }.trimEnd(' ', '·')

            val fillArgb = ConvoyMapViewModel.riderBlipColorArgb(rider)
            val borderArgb = if (rider.isSpeaking) ConvoyMapViewModel.SPEAKING_RING_COLOR_ARGB else markerBorderArgb

            val markerIcon = remember(
                rider.riderId,
                markerTitle,
                fillArgb,
                borderArgb,
                rider.isSelectedHighlight(state.selectedRiderId),
                rider.isSpeaking
            ) {
                createRiderBadgeDescriptor(
                    initial = if (rider.isSelf) youLabel.take(1).uppercase() else riderInitial(markerTitle),
                    fillColorArgb = fillArgb,
                    borderColorArgb = borderArgb,
                    textColorArgb = markerTextArgb,
                    isHighlighted = rider.riderId == state.selectedRiderId || rider.isSpeaking
                )
            }

            Marker(
                state = MarkerState(position = pos),
                title = markerTitle,
                snippet = markerSnippet,
                icon = markerIcon,
                zIndex = when {
                    rider.riderId == state.selectedRiderId -> 5f
                    rider.isSpeaking -> 4f
                    rider.isSelf -> 3.5f
                    else -> 2.5f
                },
                onClick = {
                    onMarkerClick(rider.riderId)
                    scope.launch {
                        cameraPositionState.animate(
                            update = CameraUpdateFactory.newLatLngZoom(
                                pos,
                                maxOf(cameraPositionState.position.zoom, 15.5f)
                            ),
                            durationMs = 350
                        )
                    }
                    false
                }
            )
        }
    }
}

private fun RiderMapItem.isSelectedHighlight(selectedRiderId: String?): Boolean =
    riderId == selectedRiderId

private suspend fun animateCameraToConvoy(
    cameraPositionState: CameraPositionState,
    ridersWithLocation: List<RiderMapItem>,
    route: ConvoyRouteUiState?
) {
    val allPoints = buildList {
        addAll(ridersWithLocation.mapNotNull { it.latLng })
        route?.originLatLng?.let { add(it) }
        route?.destinationLatLng?.let { add(it) }
    }
    if (allPoints.isEmpty()) return
    if (allPoints.size == 1) {
        cameraPositionState.animate(
            update = CameraUpdateFactory.newLatLngZoom(allPoints.first(), 15.5f),
            durationMs = 450
        )
        return
    }
    val bounds = ConvoyMapViewModel.buildBoundsForRiders(ridersWithLocation, route) ?: return
    cameraPositionState.animate(
        update = CameraUpdateFactory.newLatLngBounds(bounds, 150),
        durationMs = 500
    )
}

private fun createRiderBadgeDescriptor(
    initial: String,
    fillColorArgb: Int,
    borderColorArgb: Int,
    textColorArgb: Int,
    isHighlighted: Boolean
): BitmapDescriptor {
    val sizePx = if (isHighlighted) 104 else 88
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = sizePx / 2f
    val cy = sizePx / 2f
    val outerRadius = (sizePx / 2f) - 4f
    val innerRadius = outerRadius - if (isHighlighted) 7f else 5f

    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = borderColorArgb
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, outerRadius, borderPaint)

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColorArgb
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, innerRadius, fillPaint)

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColorArgb
        textSize = if (isHighlighted) 38f else 32f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val bounds = RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())
    val fontMetrics = textPaint.fontMetrics
    val textY = bounds.centerY() - (fontMetrics.ascent + fontMetrics.descent) / 2f
    canvas.drawText(initial, cx, textY, textPaint)

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

@Composable
private fun formatDistanceText(distanceMeters: Double?): String? {
    if (distanceMeters == null || distanceMeters.isNaN()) return null
    val rounded = distanceMeters.roundToInt()
    return if (rounded < 1000) {
        stringResource(R.string.map_distance_meters, rounded)
    } else {
        val km = ((distanceMeters / 100.0).roundToInt() / 10.0)
        stringResource(R.string.map_distance_km, km.toString())
    }
}

@Composable
private fun formatFreshnessText(rider: RiderMapItem): String {
    if (rider.isGhost) return stringResource(R.string.map_rider_offline)
    val ts = rider.locationTimestampMs ?: return stringResource(R.string.map_rider_no_gps)
    val ageSec = ((System.currentTimeMillis() - ts) / 1000L).coerceAtLeast(0L)
    return when {
        rider.isSelf || ageSec < 15L -> stringResource(R.string.map_rider_live_now)
        ageSec < 60L -> stringResource(R.string.map_rider_seen_sec_ago, ageSec.toInt())
        else -> stringResource(R.string.map_rider_seen_min_ago, (ageSec / 60L).toInt())
    }
}

private fun riderInitial(name: String): String =
    name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "R"

private fun launchExternalNavigation(context: Context, lat: Double, lng: Double): Boolean {
    val gmapsIntent = GoogleMapsProvider().buildNavigationIntent(Waypoint(lat, lng)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(gmapsIntent)
        true
    } catch (_: ActivityNotFoundException) {
        val fallbackIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("geo:$lat,$lng?q=$lat,$lng")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(fallbackIntent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
