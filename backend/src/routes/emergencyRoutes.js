const express = require('express')
const router = express.Router()
const prisma = require('../db')
const notificationService = require('../services/notificationService')
const { resolveRoom, isReservedName, isMember, ResolverError } = require('../services/roomResolver')
const { riderCallsign } = require('../services/identity')

// H9 – regional emergency number fallback (replaces the old GLOBAL room SOS)
const REGIONAL_EMERGENCY = {
    IN: { number: '112', name: 'National Emergency' },
    US: { number: '911', name: 'Emergency Services' },
    default: { number: '112', name: 'Emergency Services' }
}

function getRegionalEmergency(req) {
    const rawCountry = req.headers['x-country'] || req.headers['x-country-code'] || ''
    const country = (typeof rawCountry === 'string' ? rawCountry.trim().toUpperCase() : '') || 'IN'
    return REGIONAL_EMERGENCY[country] || REGIONAL_EMERGENCY.default
}

const alertTimestamps = new Map()

function sweepAlertTimestamps() {
    const cutoff = Date.now() - 60000
    for (const [userId, history] of alertTimestamps.entries()) {
        let firstValid = 0
        while (firstValid < history.length && history[firstValid] <= cutoff) {
            firstValid++
        }
        if (firstValid >= history.length) {
            alertTimestamps.delete(userId)
        } else if (firstValid > 0) {
            alertTimestamps.set(userId, history.slice(firstValid))
        }
    }
}

const alertSweepInterval = setInterval(sweepAlertTimestamps, 2 * 60 * 1000)
if (alertSweepInterval.unref) alertSweepInterval.unref()

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

    // C3 - GLOBAL or missing room: return 200 with sent: false and regional emergency number
    if (!roomName || isReservedName(roomName)) {
        const emergency = getRegionalEmergency(req)
        return res.status(200).json({
            sent: false,
            reason: 'NO_ACTIVE_RIDE',
            emergencyNumber: emergency.number,
            emergencyName: emergency.name,
            message: 'No active convoy. Call your local emergency number immediately.',
            recipientsCount: 0
        })
    }

    try {
        // C3.1 - Resolve room allowing ENDED with 120s grace
        const room = await resolveRoom(roomName, {
            allowEnded: true,
            select: {
                id: true,
                name: true,
                status: true,
                endedAt: true,
                ownerId: true,
                invites: {
                    where: { status: 'ACCEPTED' },
                    select: { inviteeId: true }
                }
            }
        })

        // Check if room ended beyond 120s grace period
        if (room.status === 'ENDED') {
            const endedAtMs = room.endedAt ? new Date(room.endedAt).getTime() : 0
            if (Date.now() - endedAtMs > 120 * 1000) {
                return res.status(410).json({ error: 'This convoy has ended', code: 'ROOM_ENDED' })
            }
        }

        // C3.2 - Authorization: sender must be owner or ACCEPTED member
        const isOwner = room.ownerId === userId
        const isAccepted = await isMember(room.id, userId)
        if (!isOwner && !isAccepted) {
            return res.status(403).json({ error: 'Only convoy members can trigger emergency alerts', code: 'NOT_A_MEMBER' })
        }

        const recipientIds = new Set()
        if (room.ownerId !== userId) {
            recipientIds.add(room.ownerId)
        }
        const invites = Array.isArray(room.invites) ? room.invites : []
        for (let i = 0; i < invites.length; i++) {
            const inviteeId = invites[i].inviteeId
            if (inviteeId && inviteeId !== userId) {
                recipientIds.add(inviteeId)
            }
        }

        const recipientList = Array.from(recipientIds)
        const parsedLat = typeof lat === 'number' ? lat : (lat ? parseFloat(lat) : null)
        const parsedLng = typeof lng === 'number' ? lng : (lng ? parseFloat(lng) : null)

        const senderUser = typeof prisma.user?.findUnique === 'function' ? await prisma.user.findUnique({
            where: { id: userId },
            select: { handle: true, displayName: true }
        }).catch(() => null) : null
        const senderName = riderCallsign(senderUser || { id: userId })

        let alertId = null
        if (typeof prisma.emergencyAlert?.create === 'function') {
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
            }).catch(err => {
                console.warn('[Emergency] Alert create warning:', err.message)
                return null
            })
            alertId = alert?.id || null
        }

        notificationService.sendEmergencyAlert(
            recipientList,
            'SOS',
            parsedLat,
            parsedLng,
            userId,
            senderName,
            room.name
        ).catch(err => console.error('[Emergency] sendEmergencyAlert non-fatal error:', err.message))

        res.status(200).json({
            sent: true,
            alertId,
            recipientsCount: recipientList.length
        })
    } catch (error) {
        if (error instanceof ResolverError) {
            return res.status(error.status).json({ error: error.message, code: error.code })
        }
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
