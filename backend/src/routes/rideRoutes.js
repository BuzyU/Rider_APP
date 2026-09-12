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

        const ride = await prisma.rideSession.create({
            data: {
                riderId,
                roomName: roomName || null,
                startTime: new Date(startTime),
                endTime: endTime ? new Date(endTime) : null,
                distanceKm: distanceKm || 0,
                privacyState: privacyState || 'PRIVATE',
                routeJson: routeJson || null
            }
        })

        if (events && Array.isArray(events) && events.length > 0) {
            const eventPayload = events.map(e => ({
                rideId: ride.id,
                type: e.eventType,
                lat: e.lat,
                lng: e.lng,
                timestamp: new Date(e.timestamp)
            }))

            await prisma.convoyEvent.createMany({
                data: eventPayload
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
            orderBy: { startTime: 'desc' }
        })
        res.json(rides)
    } catch (error) {
        console.error('Ride History Error:', error)
        res.status(500).json({ error: 'Internal server error fetching history' })
    }
})

module.exports = router
