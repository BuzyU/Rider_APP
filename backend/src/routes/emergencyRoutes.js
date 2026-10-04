const express = require('express')
const router = express.Router()
const prisma = require('../db')
const notificationService = require('../services/notificationService')

const alertTimestamps = new Map()

const emergencyRateLimiter = (req, res, next) => {
    const userId = req.user?.uid || req.ip
    const now = Date.now()
    const cutoff = now - 60000

    let history = alertTimestamps.get(userId)
    if (!history) {
        history = [now]
        alertTimestamps.set(userId, history)
        return next()
    }

    let firstValidIndex = 0
    while (firstValidIndex < history.length && history[firstValidIndex] <= cutoff) {
        firstValidIndex++
    }

    if (firstValidIndex > 0) {
        history = history.slice(firstValidIndex)
    }

    if (history.length >= 5) {
        return res.status(429).json({ error: 'Too many emergency alerts. Maximum 5 per minute.' })
    }

    history.push(now)
    alertTimestamps.set(userId, history)
    next()
}

router.post('/alert', emergencyRateLimiter, async (req, res, next) => {
    const userId = req.user.uid
    const { roomName, lat, lng } = req.body
    if (!roomName) return res.status(400).json({ error: 'roomName is required' })

    try {
        const room = await prisma.room.findUnique({
            where: { name: roomName },
            select: {
                id: true,
                name: true,
                ownerId: true,
                invites: {
                    where: { status: 'ACCEPTED' },
                    select: { inviteeId: true }
                }
            }
        })
        if (!room) return res.status(404).json({ error: 'Room not found' })

        const recipientIds = new Set()
        if (room.ownerId !== userId) {
            recipientIds.add(room.ownerId)
        }
        for (let i = 0; i < room.invites.length; i++) {
            const inviteeId = room.invites[i].inviteeId
            if (inviteeId !== userId) {
                recipientIds.add(inviteeId)
            }
        }

        const recipientList = Array.from(recipientIds)
        const parsedLat = typeof lat === 'number' ? lat : (lat ? parseFloat(lat) : null)
        const parsedLng = typeof lng === 'number' ? lng : (lng ? parseFloat(lng) : null)

        const senderUser = await prisma.user.findUnique({
            where: { id: userId },
            select: { handle: true, displayName: true }
        })
        const senderName = senderUser?.displayName || (senderUser?.handle ? `@${senderUser.handle}` : 'Convoy Rider')

        const alert = await prisma.emergencyAlert.create({
            data: {
                senderId: userId,
                roomId: room.id,
                roomName: room.name,
                lat: parsedLat,
                lng: parsedLng,
                recipients: recipientList,
                status: 'SENT'
            },
            select: { id: true }
        })

        notificationService.sendEmergencyAlert(
            recipientList,
            'SOS',
            parsedLat,
            parsedLng,
            userId,
            senderName,
            room.name
        ).catch(err => console.error('[Emergency] sendEmergencyAlert non-fatal error:', err.message))

        res.status(200).json({ sent: true, alertId: alert.id })
    } catch (error) {
        next(error)
    }
})

router.post('/cancel', async (req, res, next) => {
    const userId = req.user?.uid
    const { roomName, alertId, reason } = req.body
    try {
        if (alertId) {
            const alert = await prisma.emergencyAlert.findUnique({
                where: { id: alertId }
            })
            if (!alert) return res.status(404).json({ error: 'Alert not found' })

            let canCancel = (alert.senderId === userId)
            if (!canCancel && alert.roomId) {
                const room = await prisma.room.findUnique({
                    where: { id: alert.roomId },
                    select: { ownerId: true }
                })
                if (room?.ownerId === userId) canCancel = true
            }

            if (!canCancel) {
                return res.status(403).json({ error: 'Forbidden: Only the alert sender or room host can cancel this alert' })
            }

            await prisma.emergencyAlert.update({
                where: { id: alertId },
                data: { status: 'CANCELLED' }
            })
        } else if (roomName && userId) {
            await prisma.emergencyAlert.updateMany({
                where: { roomName: roomName, senderId: userId },
                data: { status: 'CANCELLED' }
            })
        } else {
            return res.status(400).json({ error: 'alertId or roomName is required' })
        }
        res.status(200).json({ cancelled: true, reason: reason || 'FALSE_ALARM' })
    } catch (error) {
        next(error)
    }
})

module.exports = router
