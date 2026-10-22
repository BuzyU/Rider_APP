/**
 * rateLimiter.js – Phase 8 rewrite (H11)
 *
 * Bug H11: 60/min per-IP before auth. Lobby poll = ~20 req/min/rider → 429s.
 *
 * Fix:
 *   - Unauthenticated (pre-auth): 600/min per IP (generous for health/tester endpoints)
 *   - Authenticated (post-auth, uid available as req.user.uid): 120/min per user
 *   - Rate limit is checked per-layer:
 *       • Global IP rate limit applies to all routes (hard limit for DDoS)
 *       • Per-user rate limit applies only to authenticated routes
 *   - Separate limits for emergency alerts (handled in emergencyRoutes.js already)
 *   - Test mode (NODE_ENV=test): limits bypassed entirely
 *
 * Implementation: two maps — ipRequests (per-IP) and userRequests (per-uid).
 * Sliding window (1-min bucket).
 */

'use strict'

const IP_LIMIT_PER_MIN = parseInt(process.env.RATE_LIMIT_IP_PER_MIN || '600', 10)
const USER_LIMIT_PER_MIN = parseInt(process.env.RATE_LIMIT_USER_PER_MIN || '120', 10)
const WINDOW_MS = 60_000

// In-memory sliding window buckets
const ipRequests = new Map()
const userRequests = new Map()

function countInWindow(timestamps, now) {
    const cutoff = now - WINDOW_MS
    let i = 0
    while (i < timestamps.length && timestamps[i] <= cutoff) i++
    return i > 0 ? timestamps.splice(0, i) && timestamps : timestamps
}

function addAndCheck(map, key, limit, now) {
    let ts = map.get(key)
    if (!ts) {
        ts = []
        map.set(key, ts)
    }
    // Slide window
    const cutoff = now - WINDOW_MS
    let firstValid = 0
    while (firstValid < ts.length && ts[firstValid] <= cutoff) firstValid++
    if (firstValid > 0) ts.splice(0, firstValid)

    ts.push(now)
    return ts.length > limit
}

function sweepMap(map, now) {
    const cutoff = now - WINDOW_MS
    for (const [key, ts] of map.entries()) {
        let firstValid = 0
        while (firstValid < ts.length && ts[firstValid] <= cutoff) firstValid++
        if (firstValid >= ts.length) {
            map.delete(key)
        } else if (firstValid > 0) {
            ts.splice(0, firstValid)
        }
    }
}

const sweepInterval = setInterval(() => {
    const now = Date.now()
    sweepMap(ipRequests, now)
    sweepMap(userRequests, now)
}, 2 * 60_000)
if (sweepInterval.unref) sweepInterval.unref()

const rateLimiter = (req, res, next) => {
    // Bypass entirely in test mode
    if (process.env.NODE_ENV === 'test') return next()

    const now = Date.now()
    const ip = req.ip || req.socket?.remoteAddress || 'unknown'

    // 1. IP-level limit (applies to everyone including unauthenticated)
    if (addAndCheck(ipRequests, ip, IP_LIMIT_PER_MIN, now)) {
        res.setHeader('Retry-After', '60')
        return res.status(429).json({
            error: 'Too many requests from this IP. Retry in 60 seconds.',
            code: 'IP_RATE_LIMIT'
        })
    }

    // 2. Per-user limit (only if authenticated; uid attached by authMiddleware)
    const uid = req.user?.uid
    if (uid) {
        if (addAndCheck(userRequests, uid, USER_LIMIT_PER_MIN, now)) {
            res.setHeader('Retry-After', '60')
            return res.status(429).json({
                error: 'Too many requests. Slow down and retry in 60 seconds.',
                code: 'USER_RATE_LIMIT'
            })
        }
    }

    next()
}

// Export internals for testing
rateLimiter._ipRequests = ipRequests
rateLimiter._userRequests = userRequests
rateLimiter._sweep = () => sweepMap(ipRequests, Date.now())

module.exports = rateLimiter
