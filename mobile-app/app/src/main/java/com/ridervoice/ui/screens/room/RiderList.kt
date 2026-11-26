package com.ridervoice.ui.screens.room

import android.location.Location
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ridervoice.R
import com.ridervoice.models.Participant
import com.ridervoice.models.RiderLocation
import com.ridervoice.ui.components.EmptyState
import com.ridervoice.ui.components.ParticipantCard
import com.ridervoice.ui.theme.InkSecondary
import com.ridervoice.ui.theme.rvResponsiveContentWidth
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex

@Composable
fun RiderList(
    participants: List<Participant>,
    currentUid: String,
    activeSpeaker: String?,
    isCurrentUserTransmitting: Boolean,
    isHost: Boolean,
    remoteLocations: Map<String, RiderLocation>,
    currentLocation: Location?,
    modifier: Modifier = Modifier
) {
    val activeParticipants = remember(participants) {
        participants.filter { !it.isGhost }
    }

    if (activeParticipants.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth().padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.room_no_other_riders),
                style = MaterialTheme.typography.bodyMedium,
                color = InkSecondary
            )
        }
        return
    }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .rvResponsiveContentWidth()
                .semantics {
                    isTraversalGroup = true
                    traversalIndex = 2f
                }
                .testTag("rider_list_lazy"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(
                items = activeParticipants,
                key = { it.identity }
            ) { participant ->
                val isCurrentUser = (participant.identity == currentUid)
                val isSpeaking = (activeSpeaker != null && activeSpeaker == participant.identity) ||
                    (isCurrentUser && isCurrentUserTransmitting)
                val isParticipantHost = (isHost && isCurrentUser)
                val rLoc = remoteLocations[participant.identity]

                val distanceMeters = remember(currentLocation?.latitude, currentLocation?.longitude, rLoc?.lat, rLoc?.lng, isCurrentUser) {
                    if (isCurrentUser || rLoc == null || currentLocation == null) null else {
                        val results = FloatArray(1)
                        Location.distanceBetween(
                            currentLocation.latitude,
                            currentLocation.longitude,
                            rLoc.lat,
                            rLoc.lng,
                            results
                        )
                        results[0]
                    }
                }

                val speedKmh = if (isCurrentUser) {
                    currentLocation?.takeIf { it.hasSpeed() }?.speed?.times(3.6f)
                } else {
                    rLoc?.speed?.times(3.6f)
                }

                ParticipantCard(
                    participant = participant,
                    isFaded = participant.isGhost,
                    isSpeaking = isSpeaking,
                    isCurrentUser = isCurrentUser,
                    isHost = isParticipantHost,
                    distanceMeters = distanceMeters,
                    speedKmh = speedKmh
                )
            }
        }
    }
}
