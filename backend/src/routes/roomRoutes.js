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
                name: true,
                ownerId: true,
                diagnosticsEnabled: true,
                invites: {
                    where: { inviteeId: user.uid, status: 'ACCEPTED' },
                    select: { id: true },
                    take: 1
                }
            }
        })

        if (!existingRoom) {
            return res.status(404).json({ error: 'Room not found' })
        }

        // If caller is not owner, auto-enroll as an accepted participant
        if (existingRoom.ownerId !== user.uid) {
            const existingInvite = await prisma.rideInvite.findFirst({
                where: { roomId: existingRoom.id, inviteeId: user.uid },
                select: { id: true, status: true }
            })
            if (existingInvite) {
                if (existingInvite.status !== 'ACCEPTED') {
                    await prisma.rideInvite.update({
                        where: { id: existingInvite.id },
                        data: { status: 'ACCEPTED' },
                        select: { id: true }
                    })
                }
            } else {
                await prisma.rideInvite.create({
                    data: {
                        roomId: existingRoom.id,
                        inviterId: existingRoom.ownerId,
                        inviteeId: user.uid,
                        status: 'ACCEPTED'
                    },
                    select: { id: true }
                })
            }
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

        at.addGrant({ roomJoin: true, room: roomName, canPublish: true, canSubscribe: true, canPublishData: true })
        const token = await at.toJwt()

        const activeRun = await prisma.diagnosticRun.findFirst({
            where: { convoyId: existingRoom.id, status: 'ACTIVE' },
            select: { id: true, testNumber: true }
        })

        res.json({
            token,
            roomName,
            diagnosticsEnabled: existingRoom.diagnosticsEnabled ?? (activeRun != null),
            testNumber: activeRun?.testNumber || null
        })

    } catch (error) {
        console.error('Error generating token:', error)
        res.status(500).json({ error: 'Failed to generate token' })
    }
})

module.exports = router
