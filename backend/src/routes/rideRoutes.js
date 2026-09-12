const express = require('express')
const prisma = require('../db')

const router = express.Router()

router.post('/sync', async (req, res) => {
    try {
        const riderId = req.user.uid
        const { roomName, startTime, endTime, distanceKm, privacyState, routeJson, events } = req.body

        if (!startTime) {
            return res.status(400).json({ error: 'Missing required fields' })
        }

        const parsedStartTime = new Date(startTime)
        const parsedEndTime = endTime ? new Date(endTime) : null
        const parsedDistance = typeof distanceKm === 'number' ? distanceKm : (distanceKm ? parseFloat(distanceKm) : 0)

        const ride = await prisma.$transaction(async (tx) => {
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
                const eventPayload = new Array(events.length)
                for (let i = 0; i < events.length; i++) {
                    const e = events[i]
                    eventPayload[i] = {
                        rideId: created.id,
                        type: e.eventType || 'STOP',
                        lat: typeof e.lat === 'number' ? e.lat : parseFloat(e.lat || 0),
                        lng: typeof e.lng === 'number' ? e.lng : parseFloat(e.lng || 0),
                        timestamp: e.timestamp ? new Date(e.timestamp) : new Date()
                    }
                }

                await tx.convoyEvent.createMany({
                    data: eventPayload
                })
            }

            return created
        })

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

        const formatted = new Array(rides.length)
        for (let i = 0; i < rides.length; i++) {
            const r = rides[i]
            formatted[i] = {
                id: r.id,
                riderId: r.riderId,
                startTime: r.startTime.toISOString(),
                endTime: r.endTime ? r.endTime.toISOString() : null,
                distanceKm: r.distanceKm,
                privacyState: r.privacyState,
                routeJson: r.routeJson,
                roomName: r.roomName
            }
        }

        res.json(formatted)
    } catch (error) {
        console.error('Ride History Error:', error)
        res.status(500).json({ error: 'Internal server error fetching history' })
    }
})

module.exports = router
