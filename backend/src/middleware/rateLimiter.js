/**
 * rateLimiter.js – Unified IP and User Rate Limiting (Phase 8 / C6)
 */

'use strict'

const WINDOW_MS = 60_000

// In-memory sliding window buckets
const ipRequests = new Map()
const userRequests = new Map()
const failedCodeAttempts = new Map()

function addAndCheck(map, key, limit, windowMs = WINDOW_MS, now = Date.now()) {
    let ts = map.get(key)
    if (!ts) {
        ts = []
        map.set(key, ts)
    }
    const cutoff = now - windowMs
    let firstValid = 0
    while (firstValid < ts.length && ts[firstValid] <= cutoff) firstValid++
    if (firstValid > 0) ts.splice(0, firstValid)

    ts.push(now)
    return ts.length > limit
}

function sweepMap(map, windowMs = WINDOW_MS, now = Date.now()) {
    const cutoff = now - windowMs
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
    sweepMap(ipRequests, WINDOW_MS, now)
    sweepMap(userRequests, WINDOW_MS, now)
    sweepMap(failedCodeAttempts, 15 * 60_000, now)
}, 2 * 60_000)
if (sweepInterval.unref) sweepInterval.unref()

/**
 * Global IP limiter: checks IP request count.
 * Skips /health, /api/health, /j/, /api/app/config.
 */
function ipLimiter(req, res, next) {
    const path = req.path || req.url || ''
    if (
        path === '/health' ||
        path === '/api/health' ||
        path.startsWith('/j/') ||
        path === '/api/app/config' ||
        path === '/'
    ) {
        return next()
    }

    const ipLimit = parseInt(process.env.RATE_LIMIT_IP_PER_MIN || '600', 10)
    const ip = req.ip || req.socket?.remoteAddress || 'unknown'
    const now = Date.now()

    if (addAndCheck(ipRequests, ip, ipLimit, WINDOW_MS, now)) {
        res.setHeader('Retry-After', '60')
        return res.status(429).json({
            error: 'Too many requests from this IP. Retry in 60 seconds.',
            code: 'IP_RATE_LIMIT'
        })
    }

    next()
}

/**
 * Per-user rate limiter: applied AFTER authMiddleware where req.user.uid is populated.
 */
function userLimiter(req, res, next) {
    const uid = req.user?.uid
    if (!uid) return next()

    const userLimit = parseInt(process.env.RATE_LIMIT_USER_PER_MIN || '120', 10)
    const now = Date.now()

    if (addAndCheck(userRequests, uid, userLimit, WINDOW_MS, now)) {
        res.setHeader('Retry-After', '60')
        return res.status(429).json({
            error: 'Too many requests. Slow down and retry in 60 seconds.',
            code: 'USER_RATE_LIMIT'
        })
    }

    next()
}

/**
 * Record a failed join code attempt from an IP.
 */
function recordFailedJoinCode(ip) {
    if (!ip) return
    const now = Date.now()
    addAndCheck(failedCodeAttempts, ip, 1000, 15 * 60_000, now)
}

function isJoinCodeBlocked(ip) {
    if (!ip) return false
    const ts = failedCodeAttempts.get(ip)
    if (!ts) return false
    const cutoff = Date.now() - 10 * 60_000
    const recent = ts.filter(t => t > cutoff)
    return recent.length >= 10
}

module.exports = {
    ipLimiter,
    userLimiter,
    recordFailedJoinCode,
    isJoinCodeBlocked,
    rateLimiter: ipLimiter,
    _ipRequests: ipRequests,
    _userRequests: userRequests,
    _reset: () => {
        ipRequests.clear()
        userRequests.clear()
        failedCodeAttempts.clear()
    }
}
