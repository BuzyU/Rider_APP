const requests = new Map()

function sweepExpiredIps(now = Date.now()) {
    for (const [ip, timestamps] of requests.entries()) {
        const cutoff = now - 60000
        let firstValidIndex = 0
        while (firstValidIndex < timestamps.length && timestamps[firstValidIndex] <= cutoff) {
            firstValidIndex++
        }
        if (firstValidIndex >= timestamps.length) {
            requests.delete(ip)
        } else if (firstValidIndex > 0) {
            requests.set(ip, timestamps.slice(firstValidIndex))
        }
    }
}

const sweepInterval = setInterval(() => sweepExpiredIps(), 2 * 60 * 1000)
if (sweepInterval.unref) sweepInterval.unref()

const rateLimiter = (req, res, next) => {
    const ip = req.ip
    const now = Date.now()
    const cutoff = now - 60000

    let timestamps = requests.get(ip)
    if (!timestamps) {
        timestamps = [now]
        requests.set(ip, timestamps)
        return next()
    }

    let firstValidIndex = 0
    while (firstValidIndex < timestamps.length && timestamps[firstValidIndex] <= cutoff) {
        firstValidIndex++
    }

    if (firstValidIndex > 0) {
        timestamps = timestamps.slice(firstValidIndex)
    }

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
