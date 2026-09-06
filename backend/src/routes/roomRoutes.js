const express = require('express')
const { AccessToken } = require('livekit-server-sdk')
const prisma = require('../db')

const router = express.Router()


router.post('/room/token', async (req, res) => {
    try {
        const { roomName } = req.body
        const user = req.user // Provided by authMiddleware

        if (!roomName) {
            return res.status(400).json({ error: 'Missing roomName in request body' })
        }

        // Sync user to Supabase
        await prisma.user.upsert({
            where: { id: user.uid },
            update: { email: user.email },
            create: {
                id: user.uid,
                email: user.email || null,
                displayName: user.name || 'Rider'
            }
        })

        // Check if room already exists
        const existingRoom = await prisma.room.findUnique({
            where: { name: roomName },
            include: { invites: { where: { status: 'ACCEPTED' } } }
        })

        if (existingRoom) {
            // Verify membership: caller must be room owner or have an ACCEPTED invite
            const isOwner = existingRoom.ownerId === user.uid
            const hasAcceptedInvite = existingRoom.invites.some(
                i => i.inviteeId === user.uid && (!i.status || i.status === 'ACCEPTED')
            )

            if (!isOwner && !hasAcceptedInvite) {
                return res.status(403).json({ error: 'Access denied: you are not a member of this room' })
            }
        } else {
            // Create the room for the caller (legacy quick-join creation)
            await prisma.room.create({
                data: {
                    name: roomName,
                    ownerId: user.uid
                }
            })
        }

        // Generate LiveKit Token
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
