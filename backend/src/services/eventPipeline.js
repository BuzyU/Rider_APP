const EventEmitter = require('events')
const crypto = require('crypto')

class EventPipeline extends EventEmitter {
    constructor(maxQueueSize = 1000) {
        super()
        this.maxQueueSize = maxQueueSize
        this.queues = new Map()
        this.handlers = new Map()
        this.processing = new Map()
        this.stats = {
            published: 0,
            processed: 0,
            failed: 0
        }
    }

    publish(topic, payload) {
        const eventId = crypto.randomUUID()
        const event = {
            id: eventId,
            topic,
            payload,
            timestamp: Date.now()
        }

        if (!this.queues.has(topic)) {
            this.queues.set(topic, [])
        }
        const queue = this.queues.get(topic)
        if (queue.length >= this.maxQueueSize) {
            queue.shift()
        }
        queue.push(event)
        this.stats.published++

        setImmediate(() => this.dispatch(topic))
        return eventId
    }

    subscribe(topic, handler) {
        if (!this.handlers.has(topic)) {
            this.handlers.set(topic, [])
        }
        this.handlers.get(topic).push(handler)

        if (this.queues.has(topic) && this.queues.get(topic).length > 0) {
            setImmediate(() => this.dispatch(topic))
        }
    }

    async dispatch(topic) {
        if (this.processing.get(topic)) return
        const queue = this.queues.get(topic)
        if (!queue || queue.length === 0) return

        const handlers = this.handlers.get(topic)
        if (!handlers || handlers.length === 0) return

        this.processing.set(topic, true)

        while (queue.length > 0) {
            const event = queue.shift()
            for (const handler of handlers) {
                try {
                    await handler(event.payload, event)
                    this.stats.processed++
                } catch (err) {
                    this.stats.failed++
                    console.error(`[EventPipeline] Error processing event on topic '${topic}':`, err.message)
                }
            }
        }

        this.processing.set(topic, false)
    }

    getQueueDepth(topic) {
        if (topic) return this.queues.get(topic)?.length || 0
        let total = 0
        for (const q of this.queues.values()) {
            total += q.length
        }
        return total
    }

    getStats() {
        return {
            published: this.stats.published,
            processed: this.stats.processed,
            failed: this.stats.failed,
            queueDepth: this.getQueueDepth(),
            topics: Array.from(this.queues.keys())
        }
    }

    clear() {
        this.queues.clear()
        this.handlers.clear()
        this.processing.clear()
        this.stats.published = 0
        this.stats.processed = 0
        this.stats.failed = 0
    }
}

const eventPipeline = new EventPipeline()
module.exports = eventPipeline
