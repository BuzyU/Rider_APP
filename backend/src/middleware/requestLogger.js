/**
 * Request logging middleware.
 * Gated behind DEBUG_HTTP_LOGGING or non-production environment.
 * Verbose header/body logging is strictly restricted to explicit DEBUG_HTTP_LOGGING === 'true'.
 */
module.exports = (req, res, next) => {
    const isDebug = process.env.DEBUG_HTTP_LOGGING === 'true';
    const isProd = process.env.NODE_ENV === 'production';

    if (isDebug) {
        // Redact Authorization header to prevent token leakage in debug logs
        const sanitizedHeaders = { ...req.headers };
        if (sanitizedHeaders.authorization) {
            sanitizedHeaders.authorization = '[REDACTED]';
        }
        console.log(`[HTTP DEBUG] ${req.method} ${req.originalUrl || req.url}`, {
            headers: sanitizedHeaders,
            body: req.body
        });
    } else if (!isProd) {
        // Concise summary logging in non-production
        console.log(`[HTTP] ${req.method} ${req.originalUrl || req.url}`);
    }

    next();
};
