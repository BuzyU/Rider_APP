/**
 * rideRoutes.js – Phase 8 H10 fix
 *
 * H10 – /rides/sync was non-idempotent: every retry created a new row + sent email.
 * Fix: clientRideId is used as an upsert key (riderId, clientRideId unique).
 *   - First call with endTime → creates + sends email once
 *   - Retry with same clientRideId → updates only, email NOT resent
 * Legacy clients (no clientRideId) continue to use the create path.
 */

'use strict'

const express = require('express')
const prisma = require('../db')
const emailService = require('../services/emailService')

const router = express.Router()

router.post('/sync', async (req, res) => {
    try {
        const riderId = req.user.uid
        const {
            roomName, startTime, endTime, distanceKm,
            privacyState, routeJson, events,
            clientRideId   // H10 – idempotency key; device-generated UUID per ride session
        } = req.body

        if (!startTime) {
            return res.status(400).json({ error: 'Missing required fields: startTime' })
        }

        const parsedStartTime = new Date(startTime)
        const parsedEndTime = endTime ? new Date(endTime) : null
        const parsedDistance = typeof distanceKm === 'number' ? distanceKm : (distanceKm ? parseFloat(distanceKm) : 0)

        let ride
        let isFirstEndTime = false

        if (clientRideId) {
            // H10 – upsert by (riderId, clientRideId)
            const existing = await prisma.rideSession.findUnique({
                where: { riderId_clientRideId: { riderId, clientRideId } },
                select: { id: true, endTime: true }
            })

            if (existing) {
                const hadEndTime = !!existing.endTime
                isFirstEndTime = parsedEndTime != null && !hadEndTime
                await prisma.rideSession.update({
                    where: { id: existing.id },
                    data: {
                        endTime: parsedEndTime ?? existing.endTime,
                        distanceKm: parsedDistance,
                        routeJson: routeJson || null,
                        privacyState: privacyState || 'PRIVATE'
                    }
                })
                ride = existing
            } else {
                isFirstEndTime = parsedEndTime != null
                ride = await prisma.$transaction(async (tx) => {
                    const created = await tx.rideSession.create({
                        data: {
                            riderId,
                            clientRideId,
                            roomName: roomName || null,
                            startTime: parsedStartTime,
                            endTime: parsedEndTime,
                            distanceKm: parsedDistance,
                            privacyState: privacyState || 'PRIVATE',
                            routeJson: routeJson || null
                        },
                        select: { id: true }
                    })
                    if (events && Array.isArray(events) && events.length > 0) {
                        await tx.convoyEvent.createMany({
                            data: events.map(e => ({
                                rideId: created.id,
                                type: e.eventType || 'STOP',
                                lat: typeof e.lat === 'number' ? e.lat : parseFloat(e.lat || 0),
                                lng: typeof e.lng === 'number' ? e.lng : parseFloat(e.lng || 0),
                                timestamp: e.timestamp ? new Date(e.timestamp) : new Date()
                            }))
                        })
                    }
                    return created
                })
            }
        } else {
            // No clientRideId: legacy path; always creates (pre-v0.0.3.15 clients)
            isFirstEndTime = parsedEndTime != null
            ride = await prisma.$transaction(async (tx) => {
                const created = await tx.rideSession.create({
                    data: {
                        riderId,
                        roomName: roomName || null,
                        startTime: parsedStartTime,
                        endTime: parsedEndTime,
                        distanceKm: parsedDistance,
                        privacyState: privacyState || 'PRIVATE',
                        routeJson: routeJson || null
                    },
                    select: { id: true }
                })
                if (events && Array.isArray(events) && events.length > 0) {
                    await tx.convoyEvent.createMany({
                        data: events.map(e => ({
                            rideId: created.id,
                            type: e.eventType || 'STOP',
                            lat: typeof e.lat === 'number' ? e.lat : parseFloat(e.lat || 0),
                            lng: typeof e.lng === 'number' ? e.lng : parseFloat(e.lng || 0),
                            timestamp: e.timestamp ? new Date(e.timestamp) : new Date()
                        }))
                    })
                }
                return created
            })
        }

        // H10 – send email only on first endTime (not on subsequent syncs)
        if (parsedEndTime && isFirstEndTime) {
            emailService.sendRideEndedEmailSafe({
                riderId,
                riderEmail: req.user.email,
                riderName: req.user.name,
                roomName,
                startTime: parsedStartTime,
                endTime: parsedEndTime,
                distanceKm: parsedDistance,
                privacyState,
                rideId: ride.id
            })
        }

        res.status(201).json({ success: true, rideId: ride.id })
    } catch (error) {
        console.error('Ride Sync Error:', error)
        res.status(500).json({ error: 'Internal server error during ride sync' })
    }
})

router.get('/history', async (req, res) => {
    try {
        const userId = req.user.uid
        const rides = await prisma.rideSession.findMany({
            where: { riderId: userId },
            select: {
                id: true,
                riderId: true,
                startTime: true,
                endTime: true,
                distanceKm: true,
                privacyState: true,
                routeJson: true,
                roomName: true
            },
            orderBy: { startTime: 'desc' }
        })

        res.json(rides.map(r => ({
            id: r.id,
            riderId: r.riderId,
            startTime: r.startTime.toISOString(),
            endTime: r.endTime ? r.endTime.toISOString() : null,
            distanceKm: r.distanceKm,
            privacyState: r.privacyState,
            routeJson: r.routeJson,
            roomName: r.roomName
        })))
    } catch (error) {
        console.error('Ride History Error:', error)
        res.status(500).json({ error: 'Internal server error fetching history' })
    }
})

module.exports = router
