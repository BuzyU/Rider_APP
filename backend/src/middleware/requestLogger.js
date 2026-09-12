module.exports = (req, res, next) => {
    const isDebug = process.env.DEBUG_HTTP_LOGGING === 'true'
    const isProd = process.env.NODE_ENV === 'production'

    if (isDebug) {
        const sanitizedHeaders = { ...req.headers }
        if (sanitizedHeaders.authorization) {
            sanitizedHeaders.authorization = '[REDACTED]'
        }
        console.log(`[HTTP DEBUG] ${req.method} ${req.originalUrl || req.url}`, {
            headers: sanitizedHeaders,
            body: req.body
        })
    } else if (!isProd) {
        console.log(`[HTTP] ${req.method} ${req.originalUrl || req.url}`)
    }

    next()
}
