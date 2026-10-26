package com.ridervoice.ui.components

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ParticipantNameResolverTest {

    @Before
    fun setup() {
        ParticipantNameResolver.clear()
    }

    @Test
    fun currentUser_returnsLocalizedYou() {
        val defaultYou = ParticipantNameResolver.resolve(
            identity = "user_123456",
            isCurrentUser = true
        )
        assertEquals("You", defaultYou)

        val hindiYou = ParticipantNameResolver.resolve(
            identity = "user_123456",
            isCurrentUser = true,
            youLabel = "आप"
        )
        assertEquals("आप", hindiYou)
    }

    @Test
    fun rideSpecificCallsign_isolatedBetweenRides() {
        // Register callsign in ride-A
        ParticipantNameResolver.register(userId = "rider_abc", callsign = "GhostRider", rideId = "ride_A")

        // Resolving in ride-A should return GhostRider
        val inRideA = ParticipantNameResolver.resolve(identity = "rider_abc", rideId = "ride_A")
        assertEquals("GhostRider", inRideA)

        // Resolving in ride-B without registration falls back to display name or numbered fallback, NOT ride-A's callsign
        val inRideB = ParticipantNameResolver.resolve(identity = "rider_abc", livekitName = "Alex", rideId = "ride_B")
        assertEquals("Alex", inRideB)
    }

    @Test
    fun fallbackChain_respectsPriority() {
        val uid = "user_987654321"

        // 1. No info -> numbered fallback (never raw UID)
        val fallback1 = ParticipantNameResolver.resolve(identity = uid, rideId = "ride_1")
        assertEquals("Rider 1", fallback1)

        // Second unknown rider gets "Rider 2"
        val fallback2 = ParticipantNameResolver.resolve(identity = "user_other", rideId = "ride_1")
        assertEquals("Rider 2", fallback2)

        // Consistent numbering for same rider in same ride
        val fallback1Again = ParticipantNameResolver.resolve(identity = uid, rideId = "ride_1")
        assertEquals("Rider 1", fallback1Again)

        // 2. LiveKit display name present
        val withLkName = ParticipantNameResolver.resolve(identity = "user_lk", livekitName = "Speedy", rideId = "ride_1")
        assertEquals("Speedy", withLkName)

        // 3. Registered callsign overrides LiveKit display name
        ParticipantNameResolver.register("user_lk", "Captain", rideId = "ride_1")
        val withCallsign = ParticipantNameResolver.resolve(identity = "user_lk", livekitName = "Speedy", rideId = "ride_1")
        assertEquals("Captain", withCallsign)
    }

    @Test
    fun localizedRiderPrefix() {
        val uid = "user_xyz"
        val resolved = ParticipantNameResolver.resolve(
            identity = uid,
            rideId = "ride_hi",
            riderPrefix = "सवार"
        )
        assertEquals("सवार 1", resolved)
    }

    @Test
    fun clearRide_removesOnlyTargetRide() {
        ParticipantNameResolver.register("u1", "Racer1", rideId = "ride_1")
        ParticipantNameResolver.register("u2", "Racer2", rideId = "ride_2")

        ParticipantNameResolver.clearRide("ride_1")

        assertEquals("Rider 1", ParticipantNameResolver.resolve("u1", rideId = "ride_1"))
        assertEquals("Racer2", ParticipantNameResolver.resolve("u2", rideId = "ride_2"))
    }
}
