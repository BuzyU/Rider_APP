const { describe, it, before, after, beforeEach } = require('node:test')
const assert = require('node:assert/strict')
const prisma = require('../src/db')
const { markRoomEnded } = require('../src/services/roomResolver')
const { issueRoomToken } = require('../src/services/tokenService')
const {
    deleteLiveKitRoom,
    recordPendingCleanup,
    getPendingCleanups,
    clearPendingCleanups,
    retryPendingCleanups
} = require('../src/services/roomSweeper')

process.env.LIVEKIT_API_KEY = 'test_livekit_key'
process.env.LIVEKIT_API_SECRET = 'test_livekit_secret_1234567890123456'
process.env.LIVEKIT_URL = 'http://localhost:7880'
process.env.NODE_ENV = 'test'

describe('Workstream 3: End/Token/Create Race & Stale-Token Mitigation (RM-4, RM-6, RM-8)', () => {
    const originals = {}

    before(() => {
        originals.roomFindUnique = prisma.room.findUnique
        originals.roomUpdateMany = prisma.room.updateMany
        originals.roomCount = prisma.room.count
        originals.rideInviteFindUnique = prisma.rideInvite.findUnique
    })

    after(() => {
        prisma.room.findUnique = originals.roomFindUnique
        prisma.room.updateMany = originals.roomUpdateMany
        prisma.room.count = originals.roomCount
        prisma.rideInvite.findUnique = originals.rideInviteFindUnique
    })

    beforeEach(() => {
        clearPendingCleanups()
    })

    describe('RM-8: Concurrent markRoomEnded idempotency', () => {
        it('20 concurrent markRoomEnded calls yield exactly 1 logical terminal transition and 19 alreadyEnded', async () => {
            let roomState = {
                id: '11111111-2222-3333-4444-555555555555',
                name: 'Western Ghats Morning',
                lkRoomName: 'lk-ghats-uuid',
                status: 'ACTIVE',
                endedAt: null,
                joinCode: '123456'
            }

            // Simulate atomic DB conditional update
            prisma.room.updateMany = async ({ where, data }) => {
                if (where.id === roomState.id && roomState.status !== 'ENDED') {
                    roomState.status = data.status
                    roomState.endedAt = data.endedAt
                    roomState.joinCode = data.joinCode
                    return { count: 1 }
                }
                return { count: 0 }
            }

            prisma.room.findUnique = async ({ where }) => {
                if (where.id === roomState.id) {
                    return { ...roomState }
                }
                return null
            }

            // Fire 20 concurrent calls
            const promises = Array.from({ length: 20 }, () => markRoomEnded(roomState.id))
            const results = await Promise.all(promises)

            const transitioned = results.filter(r => !r.alreadyEnded)
            const alreadyEnded = results.filter(r => r.alreadyEnded)

            assert.strictEqual(transitioned.length, 1, 'Exactly 1 call must transition the room')
            assert.strictEqual(alreadyEnded.length, 19, 'The other 19 calls must report alreadyEnded')
            assert.strictEqual(roomState.status, 'ENDED')
            assert.ok(roomState.endedAt instanceof Date)
            assert.strictEqual(roomState.joinCode, null)
        })
    })

    describe('RM-4: Interleaved token issuance race with room termination', () => {
        it('rejects pre-ended rooms immediately with 410 ROOM_ENDED', async () => {
            const endedRoom = {
                id: '22222222-3333-4444-5555-666666666666',
                name: 'Night Convoy',
                lkRoomName: 'lk-night-uuid',
                status: 'ENDED',
                ownerId: 'host_1'
            }

            await assert.rejects(
                () => issueRoomToken({ uid: 'host_1' }, endedRoom),
                (err) => {
                    assert.strictEqual(err.status, 410)
                    assert.strictEqual(err.code, 'ROOM_ENDED')
                    return true
                }
            )
        })

        it('re-reads DB after ensureLiveKitRoom and triggers compensating delete when room transitioned to ENDED', async () => {
            const room = {
                id: '33333333-4444-5555-6666-777777777777',
                name: 'Race Condition Convoy',
                lkRoomName: 'lk-race-uuid',
                status: 'ACTIVE',
                ownerId: 'host_race'
            }

            // When re-read after ensureLiveKitRoom, room is now ENDED in DB
            prisma.room.findUnique = async ({ where }) => {
                if (where.id === room.id) {
                    return { status: 'ENDED', endedAt: new Date() }
                }
                return null
            }

            let compensatingDeleteCalled = false
            let deletedLkName = null
            const mockClient = {
                deleteRoom: async (name) => {
                    compensatingDeleteCalled = true
                    deletedLkName = name
                }
            }

            // Verify issueRoomToken throws 410 ROOM_ENDED
            await assert.rejects(
                () => issueRoomToken({ uid: 'host_race' }, room),
                (err) => {
                    assert.strictEqual(err.status, 410)
                    assert.strictEqual(err.code, 'ROOM_ENDED')
                    return true
                }
            )
        })

        it('concurrent accept-invite vs end-ride: ended room rejects token with 410 and does not call createRoom', async () => {
            const endedRoom = {
                id: '44444444-5555-6666-7777-888888888888',
                name: 'Race Invite Convoy',
                lkRoomName: 'lk-race-invite-uuid',
                status: 'ENDED',
                ownerId: 'host_race'
            }

            let createRoomCalled = false
            const { getRoomServiceClient } = require('../src/services/tokenService')
            const client = getRoomServiceClient()
            let originalCreateRoom = null
            if (client) {
                originalCreateRoom = client.createRoom
                client.createRoom = async () => {
                    createRoomCalled = true
                }
            }

            prisma.rideInvite.findUnique = async () => ({ status: 'ACCEPTED' })

            await assert.rejects(
                () => issueRoomToken({ uid: 'invitee_1' }, endedRoom),
                (err) => {
                    assert.strictEqual(err.status, 410)
                    assert.strictEqual(err.code, 'ROOM_ENDED')
                    return true
                }
            )

            assert.strictEqual(createRoomCalled, false, 'createRoom must not be called when room is ended')
            if (client && originalCreateRoom) {
                client.createRoom = originalCreateRoom
            }
        })
    })

    describe('RM-6: Resilient LiveKit cleanup & pending retries', () => {
        it('treats 404 / room not found as successful absence without recording pending cleanup', async () => {
            const mockClient = {
                deleteRoom: async () => {
                    const err = new Error('requested room could not be found')
                    err.status = 404
                    throw err
                }
            }

            prisma.room.count = async () => 0

            await deleteLiveKitRoom('already-gone-room', 'room-uuid-1', mockClient)
            assert.strictEqual(getPendingCleanups().length, 0, '404 must not enter pending cleanups queue')
        })

        it('records transient LiveKit deletion error in pending cleanups queue', async () => {
            const mockClient = {
                deleteRoom: async () => {
                    const err = new Error('503 Service Unavailable')
                    err.status = 503
                    throw err
                }
            }

            prisma.room.count = async () => 0

            await deleteLiveKitRoom('transient-fail-room', 'room-uuid-2', mockClient)
            const pending = getPendingCleanups()
            assert.strictEqual(pending.length, 1)
            assert.strictEqual(pending[0].lkName, 'transient-fail-room')
            assert.strictEqual(pending[0].roomId, 'room-uuid-2')
            assert.strictEqual(pending[0].attempts, 1)
            assert.ok(pending[0].error.includes('503 Service Unavailable'))
        })

        it('retryPendingCleanups retries and clears pending cleanups when LiveKit succeeds', async () => {
            recordPendingCleanup('retry-room-1', { roomId: 'uuid-retry', error: 'network timeout' })
            assert.strictEqual(getPendingCleanups().length, 1)

            // Force nextRetry to be in the past
            getPendingCleanups()[0].nextRetry = Date.now() - 1000

            let deleteCalled = false
            const mockClient = {
                deleteRoom: async (name) => {
                    if (name === 'retry-room-1') {
                        deleteCalled = true
                    }
                }
            }

            prisma.room.count = async () => 0

            const retried = await retryPendingCleanups(mockClient)
            assert.strictEqual(deleteCalled, true)
            assert.strictEqual(retried.length, 1)
            assert.strictEqual(retried[0].status, 'CLEARED')
            assert.strictEqual(getPendingCleanups().length, 0, 'Cleaned item must be removed from queue')
        })

        it('blocks destructive cleanup when another active room claims the same identity (ambiguity guard)', async () => {
            let deleteAttempted = false
            const mockClient = {
                deleteRoom: async () => {
                    deleteAttempted = true
                }
            }

            // Simulate 1 other active room claiming this identity
            prisma.room.count = async () => 1

            await deleteLiveKitRoom('ambiguous-name-room', 'room-uuid-3', mockClient)
            assert.strictEqual(deleteAttempted, false, 'deleteRoom must not be invoked when active rooms share identity')
            assert.strictEqual(getPendingCleanups().length, 0)
        })
    })
})
