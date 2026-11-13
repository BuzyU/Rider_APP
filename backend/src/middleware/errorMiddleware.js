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

    let statusCode = err?.status || err?.statusCode || 500
    let code = err?.code || 'INTERNAL_ERROR'
    let message = err?.message || 'Internal server error'

    // Prisma error mapping (WP-142)
    if (err?.code === 'P2002') {
        statusCode = 409
        code = 'CONFLICT'
        message = 'A resource with this identifier or unique constraint already exists'
    } else if (err?.code === 'P2025') {
        statusCode = 404
        code = 'NOT_FOUND'
        message = 'Record not found'
    }

    res.status(statusCode).json({
        error: message,
        code,
        requestId
    })
}
