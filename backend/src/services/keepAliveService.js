const prisma = require('../db')

class KeepAliveService {
    constructor() {
        this.heartbeatInterval = null
        this.pingerInterval = null
        this.initialPingTimeout = null
        this.tickCount = 0
    }

    start(options = {}) {
        if (process.env.NODE_ENV === 'test') return

        const heartbeatMs = options.heartbeatMs || 10000
        const pingerMs = options.pingerMs || 3 * 60 * 1000

        if (this.heartbeatInterval) clearInterval(this.heartbeatInterval)
        if (this.pingerInterval) clearInterval(this.pingerInterval)
        if (this.initialPingTimeout) clearTimeout(this.initialPingTimeout)

        this.heartbeatInterval = setInterval(async () => {
            this.tickCount++
            const startTime = Date.now()
            try {
                await prisma.$queryRawUnsafe('SELECT 1')
                const latency = Date.now() - startTime
                const memoryMb = Math.round(process.memoryUsage().rss / (1024 * 1024))
                console.log(`[KeepAlive] 10s Heartbeat #${this.tickCount} | DB Pulse: ${latency}ms | RSS: ${memoryMb}MB | Uptime: ${Math.floor(process.uptime())}s`)
            } catch (err) {
                console.warn(`[KeepAlive] DB pulse notice: ${err.message}`)
            }
        }, heartbeatMs)
        if (this.heartbeatInterval.unref) this.heartbeatInterval.unref()

        const executePing = async () => {
            const externalUrl = process.env.RENDER_EXTERNAL_URL || process.env.API_URL
            if (!externalUrl) return
            const targetUrl = `${externalUrl.replace(/\/+$/, '')}/api/health`
            try {
                const controller = new AbortController()
                const timeout = setTimeout(() => controller.abort(), 8000)
                const res = await fetch(targetUrl, {
                    headers: { 'User-Agent': 'RiderVoice-KeepAlive/1.0' },
                    signal: controller.signal
                })
                clearTimeout(timeout)
                if (res.ok) {
                    console.log(`[KeepAlive] Inbound self-ping verified: ${targetUrl} (Status: ${res.status})`)
                }
            } catch (err) {
                console.warn(`[KeepAlive] Inbound self-ping notice: ${err.message}`)
            }
        }

        this.initialPingTimeout = setTimeout(executePing, 5000)
        if (this.initialPingTimeout.unref) this.initialPingTimeout.unref()

        this.pingerInterval = setInterval(executePing, pingerMs)
        if (this.pingerInterval.unref) this.pingerInterval.unref()
    }

    stop() {
        if (this.heartbeatInterval) {
            clearInterval(this.heartbeatInterval)
            this.heartbeatInterval = null
        }
        if (this.pingerInterval) {
            clearInterval(this.pingerInterval)
            this.pingerInterval = null
        }
        if (this.initialPingTimeout) {
            clearTimeout(this.initialPingTimeout)
            this.initialPingTimeout = null
        }
    }
}

const keepAliveService = new KeepAliveService()
module.exports = keepAliveService
