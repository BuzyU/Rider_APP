'use strict'

const crypto = require('crypto')

/**
 * requestIdMiddleware – Observability middleware (V3-6)
 *
 * Accepts X-Request-Id header from client (e.g. Android OkHttp client)
 * or generates a new RFC 4122 v4 UUID if not provided.
 * Attaches requestId to req.id, req.requestId and sets X-Request-Id response header.
 */
module.exports = (req, res, next) => {
    const incomingId = req.headers['x-request-id']
    const requestId = (typeof incomingId === 'string' && incomingId.trim().length > 0 && incomingId.trim().length <= 128)
        ? incomingId.trim()
        : crypto.randomUUID()

    req.id = requestId
    req.requestId = requestId
    res.setHeader('X-Request-Id', requestId)
    next()
}
