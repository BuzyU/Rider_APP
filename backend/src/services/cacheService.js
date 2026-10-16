class CacheService {
    constructor() {
        this.cache = new Map()
        this.maxSize = 5000
        this.hits = 0
        this.misses = 0
        this.sweepInterval = setInterval(() => this.sweep(), 60000)
        if (this.sweepInterval.unref) this.sweepInterval.unref()
    }

    get(key) {
        const item = this.cache.get(key)
        if (!item) {
            this.misses++
            return null
        }
        if (item.expiresAt && item.expiresAt <= Date.now()) {
            this.cache.delete(key)
            this.misses++
            return null
        }
        this.hits++
        return item.value
    }

    set(key, value, ttlMs = 60000) {
        if (this.cache.size >= this.maxSize) {
            const oldestKey = this.cache.keys().next().value
            if (oldestKey) this.cache.delete(oldestKey)
        }
        this.cache.set(key, {
            value,
            expiresAt: ttlMs > 0 ? Date.now() + ttlMs : null
        })
        return true
    }

    del(key) {
        return this.cache.delete(key)
    }

    delPrefix(prefix) {
        let deleted = 0
        for (const key of this.cache.keys()) {
            if (key.startsWith(prefix)) {
                this.cache.delete(key)
                deleted++
            }
        }
        return deleted
    }

    async wrap(key, ttlMs, fetcher) {
        const cached = this.get(key)
        if (cached !== null && cached !== undefined) {
            return cached
        }
        const fresh = await fetcher()
        if (fresh !== undefined && fresh !== null) {
            this.set(key, fresh, ttlMs)
        }
        return fresh
    }

    sweep() {
        const now = Date.now()
        for (const [key, item] of this.cache.entries()) {
            if (item.expiresAt && item.expiresAt <= now) {
                this.cache.delete(key)
            }
        }
    }

    clear() {
        this.cache.clear()
        this.hits = 0
        this.misses = 0
    }

    stats() {
        return {
            size: this.cache.size,
            hits: this.hits,
            misses: this.misses,
            hitRate: this.hits + this.misses > 0 ? (this.hits / (this.hits + this.misses)).toFixed(2) : '0.00'
        }
    }
}

const cacheService = new CacheService()
module.exports = cacheService

