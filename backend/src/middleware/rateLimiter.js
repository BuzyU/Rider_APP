const requests = new Map()

module.exports = (req, res, next) => {

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
        return res.status(429).json({
            error: 'Too many requests'
        })
    }

    next()
}
