'use strict'

/**
 * requestLogger – HTTP Request Logger (V3-6)
 *
 * Logs method, path, and requestId.
 * Never logs raw auth tokens or sensitive request bodies.
 */
module.exports = (req, res, next) => {
    const isDebug = process.env.DEBUG_HTTP_LOGGING === 'true'
    const isProd = process.env.NODE_ENV === 'production'
    const reqId = req.id || req.requestId || '-'

    if (isDebug) {
        const sanitizedHeaders = { ...req.headers }
        if (sanitizedHeaders.authorization) {
            sanitizedHeaders.authorization = '[REDACTED]'
        }
        console.log(`[HTTP DEBUG] [${reqId}] ${req.method} ${req.originalUrl || req.url}`, {
            headers: sanitizedHeaders,
            bodyKeys: req.body && typeof req.body === 'object' ? Object.keys(req.body) : undefined
        })
    } else if (!isProd) {
        console.log(`[HTTP] [${reqId}] ${req.method} ${req.originalUrl || req.url}`)
    }

    next()
}
