/**
 * roomRoutes.js – Phase 1 & C4 rewrite
 *
 * Fixes:
 *   A1 – roomCreate:false (delegated to tokenService)
 *   A2 – all token paths use issueRoomToken()
 *   F3 – 2h TTL with expiresAt in response
 *   H3 – 410 for ENDED rooms (via resolveRoom)
 *   C4 – unified joinService delegation for link vs member tokens
 */

'use strict'

const express = require('express')
const router = express.Router()
const { resolveRoom, ResolverError } = require('../services/roomResolver')
const { issueRoomToken } = require('../services/tokenService')
const { joinWithLinkToken, joinAsMember, extractCleanToken } = require('../services/joinService')

// POST /api/rooms/room/token
// Issues a LiveKit token for a room.
// If ref is a link or token, delegates to joinWithLinkToken.
// Otherwise delegates to joinAsMember (members only; rejects non-members).
router.post('/room/token', async (req, res) => {
    try {
        const { roomName, roomId } = req.body
        const user = req.user

        const rawRef = roomId || roomName
        if (!rawRef) {
            return res.status(400).json({ error: 'roomId or roomName is required' })
        }

        const isLinkToken = typeof rawRef === 'string' && (
            rawRef.includes('://') ||
            rawRef.includes('/join/') ||
            rawRef.includes('token=') ||
            /^[a-f0-9]{16}$/i.test(rawRef.trim())
        )

        let result
        if (isLinkToken) {
            result = await joinWithLinkToken(rawRef, user)
        } else {
            result = await joinAsMember(rawRef, user)
        }

        return res.json(result)
    } catch (error) {
        if (error instanceof ResolverError) {
            return res.status(error.status).json({ error: error.message, code: error.code })
        }
        if (error.status) {
            return res.status(error.status).json({ error: error.message, code: error.code })
        }
        console.error('[RoomRoutes] Error generating token:', error)
        return res.status(500).json({ error: 'Failed to generate token' })
    }
})

// POST /api/rooms/token/refresh
// F3 – token refresh: exchange an expiring token for a fresh one.
// Caller must be an authorized member of a non-ENDED room.
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
        console.error('[RoomRoutes] Error refreshing token:', error)
        res.status(500).json({ error: 'Failed to refresh token' })
    }
})

module.exports = router
