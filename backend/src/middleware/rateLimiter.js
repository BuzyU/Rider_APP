const requests = new Map()

// Periodic eviction to prevent unbounded memory growth from stale IP keys
function sweepExpiredIps(now = Date.now()) {
    for (const [ip, timestamps] of requests.entries()) {
        const active = timestamps.filter(time => now - time < 60000)
        if (active.length === 0) {
            requests.delete(ip)
        } else {
            requests.set(ip, active)
        }
    }
}

const sweepInterval = setInterval(() => sweepExpiredIps(), 2 * 60 * 1000)
if (sweepInterval.unref) sweepInterval.unref() // Don't keep event loop alive on shutdown

const rateLimiter = (req, res, next) => {
    const ip = req.ip
    const now = Date.now()

    if (!requests.has(ip)) {
        requests.set(ip, [])
    }

    const timestamps = requests
        .get(ip)
        .filter(time => now - time < 60000)

    timestamps.push(now)
    requests.set(ip, timestamps)

    if (timestamps.length > 60) {
        return res.status(429).json({ error: 'Too many requests' })
    }

    next()
}

rateLimiter._requests = requests
rateLimiter._sweep = sweepExpiredIps

module.exports = rateLimiter

