/**
 * roomRoutes.js – Phase 1 rewrite
 *
 * Fixes:
 *   A1 – roomCreate:false (delegated to tokenService)
 *   A2 – all token paths use issueRoomToken()
 *   F3 – 2h TTL with expiresAt in response
 *   H3 – 410 for ENDED rooms (via resolveRoom)
 */

'use strict'

const express = require('express')
const router = express.Router()
const { resolveRoom, ResolverError } = require('../services/roomResolver')
const { issueRoomToken } = require('../services/tokenService')

const prisma = require('../db')

function extractCleanToken(input) {
    if (!input || typeof input !== 'string') return ''
    let cleaned = input.trim()
    if (cleaned.startsWith('ridervoice://join/')) {
        cleaned = cleaned.slice('ridervoice://join/'.length)
    } else if (cleaned.includes('/join/')) {
        cleaned = cleaned.slice(cleaned.indexOf('/join/') + '/join/'.length)
    }
    if (cleaned.includes('token=')) {
        cleaned = cleaned.slice(cleaned.indexOf('token=') + 'token='.length)
    }
    cleaned = cleaned.split('?')[0].split('&')[0].split('#')[0].replace(/\/+$/, '').trim()
    return cleaned
}

// POST /api/rooms/room/token
// Issues a LiveKit token for an ACTIVE room. The caller must already be a member.
router.post('/room/token', async (req, res) => {
    try {
        const { roomName, roomId } = req.body
        const user = req.user

        const rawRef = roomId || roomName
        if (!rawRef) {
            return res.status(400).json({ error: 'roomId or roomName is required' })
        }
        const ref = extractCleanToken(rawRef) || rawRef

        let room = null
        const isExplicitToken = typeof rawRef === 'string' && (rawRef.includes('://') || rawRef.includes('/join/') || rawRef.includes('token='))

        if (isExplicitToken) {
            let joinTokenRecord = typeof prisma.roomJoinToken?.findUnique === 'function' ? await prisma.roomJoinToken.findUnique({
                where: { token: ref },
                select: {
                    expiresAt: true,
                    room: {
                        select: {
                            id: true,
                            name: true,
                            ownerId: true,
                            diagnosticsEnabled: true,
                            status: true
                        }
                    }
                }
            }).catch(() => null) : null

            if (!joinTokenRecord && typeof prisma.roomJoinToken?.findUnique === 'function' && ref.toLowerCase() !== ref) {
                joinTokenRecord = await prisma.roomJoinToken.findUnique({
                    where: { token: ref.toLowerCase() },
                    select: {
                        expiresAt: true,
                        room: {
                            select: {
                                id: true,
                                name: true,
                                ownerId: true,
                                diagnosticsEnabled: true,
                                status: true
                            }
                        }
                    }
                }).catch(() => null)
            }

            if (joinTokenRecord && joinTokenRecord.room) {
                if (new Date() > joinTokenRecord.expiresAt) {
                    return res.status(410).json({ error: 'Convoy join link has expired' })
                }
                room = joinTokenRecord.room
                const existingInvite = typeof prisma.rideInvite?.findFirst === 'function' ? await prisma.rideInvite.findFirst({
                    where: { roomId: room.id, inviteeId: user.uid },
                    select: { id: true }
                }).catch(() => null) : null

                if (existingInvite && typeof prisma.rideInvite?.update === 'function') {
                    await prisma.rideInvite.update({
                        where: { id: existingInvite.id },
                        data: { status: 'ACCEPTED' }
                    }).catch(() => {})
                } else if (typeof prisma.rideInvite?.create === 'function') {
                    await prisma.rideInvite.create({
                        data: {
                            roomId: room.id,
                            inviterId: room.ownerId,
                            inviteeId: user.uid,
                            status: 'ACCEPTED'
                        }
                    }).catch(() => {})
                }
            }
        }

        if (!room) {
            try {
                room = await resolveRoom(ref)
            } catch (resolveErr) {
                // Check if ref is a roomJoinToken
                let joinTokenRecord = typeof prisma.roomJoinToken?.findUnique === 'function' ? await prisma.roomJoinToken.findUnique({
                    where: { token: ref },
                    select: {
                        expiresAt: true,
                        room: {
                            select: {
                                id: true,
                                name: true,
                                ownerId: true,
                                diagnosticsEnabled: true,
                                status: true
                            }
                        }
                    }
                }).catch(() => null) : null

                if (!joinTokenRecord && typeof prisma.roomJoinToken?.findUnique === 'function' && ref.toLowerCase() !== ref) {
                    joinTokenRecord = await prisma.roomJoinToken.findUnique({
                        where: { token: ref.toLowerCase() },
                        select: {
                            expiresAt: true,
                            room: {
                                select: {
                                    id: true,
                                    name: true,
                                    ownerId: true,
                                    diagnosticsEnabled: true,
                                    status: true
                                }
                            }
                        }
                    }).catch(() => null)
                }

                if (joinTokenRecord && joinTokenRecord.room) {
                    if (new Date() > joinTokenRecord.expiresAt) {
                        return res.status(410).json({ error: 'Convoy join link has expired' })
                    }
                    room = joinTokenRecord.room
                    const existingInvite = typeof prisma.rideInvite?.findFirst === 'function' ? await prisma.rideInvite.findFirst({
                        where: { roomId: room.id, inviteeId: user.uid },
                        select: { id: true }
                    }).catch(() => null) : null

                    if (existingInvite && typeof prisma.rideInvite?.update === 'function') {
                        await prisma.rideInvite.update({
                            where: { id: existingInvite.id },
                            data: { status: 'ACCEPTED' }
                        }).catch(() => {})
                    } else if (typeof prisma.rideInvite?.create === 'function') {
                        await prisma.rideInvite.create({
                            data: {
                                roomId: room.id,
                                inviterId: room.ownerId,
                                inviteeId: user.uid,
                                status: 'ACCEPTED'
                            }
                        }).catch(() => {})
                    }
                } else {
                    throw resolveErr
                }
            }
        }

        const result = await issueRoomToken(user, room)
        res.json(result)
    } catch (error) {
        if (error instanceof ResolverError) {
            return res.status(error.status).json({ error: error.message, code: error.code })
        }
        if (error.status) {
            return res.status(error.status).json({ error: error.message, code: error.code })
        }
        console.error('Error generating token:', error)
        res.status(500).json({ error: 'Failed to generate token' })
    }
})

// POST /api/rooms/token/refresh
// F3 – token refresh: exchange an expiring token for a fresh one.
// Client should call this ~5 minutes before token expiry.
router.post('/token/refresh', async (req, res) => {
    try {
        const { roomId, roomName } = req.body
        const user = req.user

        const rawRef = roomId || roomName
        if (!rawRef) {
            return res.status(400).json({ error: 'roomId or roomName is required' })
        }
        const ref = extractCleanToken(rawRef) || rawRef

        const room = await resolveRoom(ref)
        const result = await issueRoomToken(user, room)
        res.json(result)
    } catch (error) {
        if (error instanceof ResolverError) {
            return res.status(error.status).json({ error: error.message, code: error.code })
        }
        if (error.status) {
            return res.status(error.status).json({ error: error.message, code: error.code })
        }
        console.error('Error refreshing token:', error)
        res.status(500).json({ error: 'Failed to refresh token' })
    }
})

module.exports = router
