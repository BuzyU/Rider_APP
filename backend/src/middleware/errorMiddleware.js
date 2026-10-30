'use strict'

/**
 * errorMiddleware – Global Express error handler (V3-6)
 *
 * Logs structured error context: { requestId, method, path, userId, error }
 * and returns JSON { error, code, requestId } with 500 status.
 * Never logs tokens or full request bodies.
 */
module.exports = (err, req, res, _next) => {
    const requestId = req.id || req.requestId || 'unknown'
    const method = req.method
    const path = req.originalUrl || req.url
    const userId = req.user?.uid || req.user?.id || 'anonymous'

    const errorContext = {
        requestId,
        method,
        path,
        userId,
        error: err?.message || String(err),
        code: err?.code || 'INTERNAL_ERROR',
        stack: err?.stack
    }

    console.error('[HTTP ERROR]', JSON.stringify(errorContext))

    if (res.headersSent) {
        return _next(err)
    }

    const statusCode = err?.status || err?.statusCode || 500
    res.status(statusCode).json({
        error: err?.message || 'Internal server error',
        code: err?.code || 'INTERNAL_ERROR',
        requestId
    })
}
