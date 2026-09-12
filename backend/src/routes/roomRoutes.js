const express = require('express')
const { AccessToken } = require('livekit-server-sdk')
const prisma = require('../db')

const router = express.Router()

router.post('/room/token', async (req, res) => {
    try {
        const { roomName } = req.body
        const user = req.user

        if (!roomName) {
            return res.status(400).json({ error: 'Missing roomName in request body' })
        }

        const existingRoom = await prisma.room.findUnique({
            where: { name: roomName },
            select: {
                id: true,
                ownerId: true,
                invites: {
                    where: { inviteeId: user.uid, status: 'ACCEPTED' },
                    select: { id: true },
                    take: 1
                }
            }
        })

        if (existingRoom) {
            const isAuthorized = existingRoom.ownerId === user.uid || existingRoom.invites.length > 0
            if (!isAuthorized) {
                return res.status(403).json({ error: 'Access denied: you are not a member of this room' })
            }
        } else {
            await prisma.room.create({
                data: {
                    name: roomName,
                    ownerId: user.uid
                },
                select: { id: true }
            })
        }

        const participantIdentity = user.uid
        const at = new AccessToken(
            process.env.LIVEKIT_API_KEY,
            process.env.LIVEKIT_API_SECRET,
            {
                identity: participantIdentity,
                name: user.name || 'Rider'
            }
        )

        at.addGrant({ roomJoin: true, room: roomName })
        const token = await at.toJwt()

        res.json({ token, roomName })

    } catch (error) {
        console.error('Error generating token:', error)
        res.status(500).json({ error: 'Failed to generate token' })
    }
})

module.exports = router
