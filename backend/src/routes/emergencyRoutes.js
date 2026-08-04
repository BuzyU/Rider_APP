const express = require('express')
const router = express.Router()
const prisma = require('../db')
const notificationService = require('../services/notificationService')

// POST /api/emergency/alert
router.post('/alert', async (req, res, next) => {
    const userId = req.user.uid
    const { roomName, lat, lng } = req.body
    if (!roomName) return res.status(400).json({ error: 'roomName is required' })

    try {
        const room = await prisma.room.findUnique({
            where: { name: roomName },
            include: { invites: { where: { status: 'ACCEPTED' } } }
        })
        if (!room) return res.status(404).json({ error: 'Room not found' })

        const recipientIds = new Set([room.ownerId, ...room.invites.map(i => i.inviteeId)])
        recipientIds.delete(userId) // don't alert yourself

        await notificationService.sendEmergencyAlert(Array.from(recipientIds), 'SOS', lat, lng)
        res.status(200).json({ sent: true })
    } catch (error) {
        next(error)
    }
})

module.exports = router
