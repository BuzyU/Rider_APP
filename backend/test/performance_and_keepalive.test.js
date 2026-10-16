const { describe, it, before, after } = require('node:test')
const assert = require('node:assert')
const cacheService = require('../src/services/cacheService')
const keepAliveService = require('../src/services/keepAliveService')
const eventPipeline = require('../src/services/eventPipeline')

describe('Cache, KeepAlive, and EventPipeline Performance Services', () => {
    before(() => {
        cacheService.clear()
        eventPipeline.clear()
    })

    after(() => {
        cacheService.clear()
        eventPipeline.clear()
        keepAliveService.stop()
    })

    it('stores and retrieves values with get and set', () => {
        cacheService.set('test_key', { foo: 'bar' }, 10000)
        const retrieved = cacheService.get('test_key')
        assert.deepStrictEqual(retrieved, { foo: 'bar' })
    })

    it('returns null on expired cache keys', async () => {
        cacheService.set('quick_expire', 'val', 50)
        assert.strictEqual(cacheService.get('quick_expire'), 'val')
        await new Promise(resolve => setTimeout(resolve, 60))
        assert.strictEqual(cacheService.get('quick_expire'), null)
    })

    it('deletes keys individually and by prefix', () => {
        cacheService.set('room_1', 'data1', 10000)
        cacheService.set('room_2', 'data2', 10000)
        cacheService.set('user_1', 'user1', 10000)

        cacheService.del('user_1')
        assert.strictEqual(cacheService.get('user_1'), null)

        const deletedCount = cacheService.delPrefix('room_')
        assert.strictEqual(deletedCount, 2)
        assert.strictEqual(cacheService.get('room_1'), null)
        assert.strictEqual(cacheService.get('room_2'), null)
    })

    it('wrap caches fetcher result on first call and serves cached on subsequent calls', async () => {
        let calls = 0
        const fetcher = async () => {
            calls++
            return { calculated: 42 }
        }

        const res1 = await cacheService.wrap('wrap_test', 5000, fetcher)
        const res2 = await cacheService.wrap('wrap_test', 5000, fetcher)

        assert.strictEqual(calls, 1)
        assert.deepStrictEqual(res1, { calculated: 42 })
        assert.deepStrictEqual(res2, { calculated: 42 })
    })

    it('reports cache hit and miss statistics accurately', () => {
        cacheService.clear()
        cacheService.set('k1', 'v1', 5000)
        cacheService.get('k1')
        cacheService.get('k1')
        cacheService.get('missing')

        const stats = cacheService.stats()
        assert.strictEqual(stats.hits, 2)
        assert.strictEqual(stats.misses, 1)
        assert.strictEqual(stats.size, 1)
        assert.strictEqual(stats.hitRate, '0.67')
    })

    it('keepAliveService starts and stops cleanly without throwing', () => {
        keepAliveService.start({ heartbeatMs: 50, pingerMs: 100 })
        keepAliveService.stop()
        assert.strictEqual(keepAliveService.heartbeatInterval, null)
        assert.strictEqual(keepAliveService.pingerInterval, null)
    })

    it('eventPipeline publishes and processes events asynchronously on subscribed topics', async () => {
        const received = []
        eventPipeline.subscribe('telemetry.batch', (payload) => {
            received.push(payload)
        })

        const eventId = eventPipeline.publish('telemetry.batch', { rider: 'r1', seq: 1 })
        assert.ok(typeof eventId === 'string' && eventId.length > 0)

        await new Promise(resolve => setTimeout(resolve, 50))
        assert.strictEqual(received.length, 1)
        assert.deepStrictEqual(received[0], { rider: 'r1', seq: 1 })
    })

    it('eventPipeline tracks processing stats and queue depth', async () => {
        eventPipeline.clear()
        let handled = 0
        eventPipeline.subscribe('audit.log', async (data) => {
            handled++
        })

        eventPipeline.publish('audit.log', { action: 'join' })
        eventPipeline.publish('audit.log', { action: 'leave' })

        await new Promise(resolve => setTimeout(resolve, 50))
        const stats = eventPipeline.getStats()
        assert.strictEqual(stats.published, 2)
        assert.strictEqual(stats.processed, 2)
        assert.strictEqual(stats.failed, 0)
        assert.strictEqual(stats.queueDepth, 0)
        assert.ok(stats.topics.includes('audit.log'))
    })
})
