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
