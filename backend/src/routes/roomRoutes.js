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
            include: { invites: { where: { status: 'ACCEPTED' } } }
        })

        if (existingRoom) {
            const isOwner = existingRoom.ownerId === user.uid
            const hasAcceptedInvite = isOwner || existingRoom.invites.some(
                i => i.inviteeId === user.uid && (!i.status || i.status === 'ACCEPTED')
            )

            if (!hasAcceptedInvite) {
                return res.status(403).json({ error: 'Access denied: you are not a member of this room' })
            }
        } else {
            await prisma.room.create({
                data: {
                    name: roomName,
                    ownerId: user.uid
                }
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
