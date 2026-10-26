const { describe, it, before, after, beforeEach } = require('node:test')
const assert = require('node:assert/strict')
const prisma = require('../src/db')
const { resolveLkRoomName, resolveRoom } = require('../src/services/roomResolver')
const { issueRoomToken } = require('../src/services/tokenService')

process.env.LIVEKIT_API_KEY = 'test_livekit_key'
process.env.LIVEKIT_API_SECRET = 'test_livekit_secret_1234567890123456'
process.env.LIVEKIT_URL = 'http://localhost:7880'
process.env.NODE_ENV = 'test'

function decodeJwtPayload(token) {
    const parts = token.split('.')
    assert.strictEqual(parts.length, 3, 'JWT must have 3 parts')
    const payloadJson = Buffer.from(parts[1], 'base64').toString('utf8')
    return JSON.parse(payloadJson)
}

describe('Workstream 2: Staged LiveKit Room Identity Migration (RM-1)', () => {
    const savedRoomIdMode = process.env.ROOM_ID_MODE
    const savedStagedUuid = process.env.STAGED_LK_ROOM_UUID

    after(() => {
        if (savedRoomIdMode !== undefined) process.env.ROOM_ID_MODE = savedRoomIdMode
        else delete process.env.ROOM_ID_MODE

        if (savedStagedUuid !== undefined) process.env.STAGED_LK_ROOM_UUID = savedStagedUuid
        else delete process.env.STAGED_LK_ROOM_UUID
    })

    describe('resolveLkRoomName resolution logic', () => {
        it('returns persisted lkRoomName when present, ignoring ROOM_ID_MODE flag', () => {
            delete process.env.ROOM_ID_MODE
            const room = {
                id: '11111111-2222-3333-4444-555555555555',
                name: 'Ghat Morning Ride',
                lkRoomName: 'persisted-uuid-custom-identity'
            }
            assert.strictEqual(resolveLkRoomName(room), 'persisted-uuid-custom-identity')

            process.env.ROOM_ID_MODE = 'true'
            assert.strictEqual(resolveLkRoomName(room), 'persisted-uuid-custom-identity')

            process.env.ROOM_ID_MODE = 'false'
            assert.strictEqual(resolveLkRoomName(room), 'persisted-uuid-custom-identity')
        })

        it('falls back to room.name when lkRoomName is null and ROOM_ID_MODE is not true', () => {
            delete process.env.ROOM_ID_MODE
            const legacyRoom = {
                id: '11111111-2222-3333-4444-555555555555',
                name: 'Legacy Sunday Convoy',
                lkRoomName: null
            }
            assert.strictEqual(resolveLkRoomName(legacyRoom), 'Legacy Sunday Convoy')

            process.env.ROOM_ID_MODE = 'false'
            assert.strictEqual(resolveLkRoomName(legacyRoom), 'Legacy Sunday Convoy')
        })

        it('falls back to room.id when lkRoomName is null and ROOM_ID_MODE is true', () => {
            process.env.ROOM_ID_MODE = 'true'
            const legacyRoom = {
                id: '11111111-2222-3333-4444-555555555555',
                name: 'Legacy Sunday Convoy',
                lkRoomName: null
            }
            assert.strictEqual(resolveLkRoomName(legacyRoom), '11111111-2222-3333-4444-555555555555')
        })

        it('handles null room safely returning null', () => {
            assert.strictEqual(resolveLkRoomName(null), null)
        })
    })

    describe('issueRoomToken respects persisted lkRoomName', () => {
        const originals = {}

        before(() => {
            originals.userFindUnique = prisma.user?.findUnique
            if (prisma.user) prisma.user.findUnique = async () => null
        })

        after(() => {
            if (prisma.user && originals.userFindUnique) {
                prisma.user.findUnique = originals.userFindUnique
            }
        })

        it('issues JWT with video.room set to persisted lkRoomName for host', async () => {
            const hostUser = { uid: 'host_user_1', name: 'Convoy Lead' }
            const roomWithUuid = {
                id: '99999999-8888-7777-6666-555555555555',
                name: 'Western Ghats Loop',
                lkRoomName: 'staged-lk-uuid-room-xyz',
                status: 'ACTIVE',
                ownerId: 'host_user_1',
                diagnosticsEnabled: false
            }

            const tokenResult = await issueRoomToken(hostUser, roomWithUuid)
            assert.ok(tokenResult.token)
            assert.strictEqual(tokenResult.roomId, roomWithUuid.id)
            assert.strictEqual(tokenResult.roomName, 'Western Ghats Loop') // Display name preserved
            assert.strictEqual(tokenResult.lkRoomName, 'staged-lk-uuid-room-xyz')

            const payload = decodeJwtPayload(tokenResult.token)
            assert.strictEqual(payload.video.room, 'staged-lk-uuid-room-xyz')
            assert.strictEqual(payload.video.roomCreate, false, 'Never allow implicit room creation')
        })

        it('issues JWT with video.room set to room.name for legacy room with null lkRoomName', async () => {
            delete process.env.ROOM_ID_MODE
            const hostUser = { uid: 'host_user_2', name: 'Old Convoy Host' }
            const legacyRoom = {
                id: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
                name: 'Old Sunday Run',
                lkRoomName: null,
                status: 'ACTIVE',
                ownerId: 'host_user_2',
                diagnosticsEnabled: false
            }

            const tokenResult = await issueRoomToken(hostUser, legacyRoom)
            assert.strictEqual(tokenResult.lkRoomName, 'Old Sunday Run')
            const payload = decodeJwtPayload(tokenResult.token)
            assert.strictEqual(payload.video.room, 'Old Sunday Run')
        })
    })

    describe('resolveRoom lookup by lkRoomName', () => {
        const originals = {}

        before(() => {
            originals.roomFindUnique = prisma.room.findUnique
            originals.roomFindFirst = prisma.room.findFirst
        })

        after(() => {
            prisma.room.findUnique = originals.roomFindUnique
            prisma.room.findFirst = originals.roomFindFirst
        })

        it('resolves active room when queried by its persisted lkRoomName', async () => {
            const targetRoom = {
                id: '12345678-1234-1234-1234-123456789012',
                name: 'Ghat Riders',
                lkRoomName: 'lk-uuid-persisted-ident-1',
                status: 'LOBBY',
                ownerId: 'user_host'
            }

            prisma.room.findUnique = async ({ where }) => {
                if (where.id === targetRoom.id) return targetRoom
                return null
            }

            prisma.room.findFirst = async ({ where }) => {
                if (where.lkRoomName === 'lk-uuid-persisted-ident-1') return targetRoom
                return null
            }

            const resolved = await resolveRoom('lk-uuid-persisted-ident-1')
            assert.strictEqual(resolved.id, targetRoom.id)
            assert.strictEqual(resolved.lkRoomName, 'lk-uuid-persisted-ident-1')
        })
    })

    describe('P0-A & F-18: Immutable Room Identity, Sweeper & Webhook Scoping', () => {
        it('sequential rides with the same display name get distinct lkRoomNames and distinct JWT room grants', async () => {
            const hostUser = { uid: 'host_user_sunday', name: 'Sunday Lead' }
            const rideId1 = '11111111-1111-1111-1111-111111111111'
            const rideId2 = '22222222-2222-2222-2222-222222222222'

            const room1 = {
                id: rideId1,
                name: 'Sunday Morning Ride',
                lkRoomName: `rv_${rideId1}`,
                status: 'ACTIVE',
                ownerId: hostUser.uid
            }
            const tokenResult1 = await issueRoomToken(hostUser, room1)
            const jwt1 = decodeJwtPayload(tokenResult1.token)

            const room2 = {
                id: rideId2,
                name: 'Sunday Morning Ride',
                lkRoomName: `rv_${rideId2}`,
                status: 'ACTIVE',
                ownerId: hostUser.uid
            }
            const tokenResult2 = await issueRoomToken(hostUser, room2)
            const jwt2 = decodeJwtPayload(tokenResult2.token)

            assert.strictEqual(room1.name, room2.name, 'Display names match')
            assert.notStrictEqual(tokenResult1.lkRoomName, tokenResult2.lkRoomName, 'LiveKit room names must be distinct')
            assert.strictEqual(tokenResult1.lkRoomName, `rv_${rideId1}`)
            assert.strictEqual(tokenResult2.lkRoomName, `rv_${rideId2}`)
            assert.notStrictEqual(jwt1.video.room, jwt2.video.room, 'JWT video.room must never collide')
        })

        it('LiveKit webhook does not kick participant when LiveKit room matches ENDED room only via display-name fallback', async () => {
            const { handleLiveKitEvent } = require('../src/routes/livekitWebhook')
            const endedRoom = {
                id: 'ended-uuid-1234',
                name: 'Sunday Express',
                lkRoomName: 'rv_ended-uuid-1234',
                status: 'ENDED',
                ownerId: 'host_1'
            }

            const originals = {}
            originals.roomFindUnique = prisma.room.findUnique
            originals.roomFindFirst = prisma.room.findFirst
            prisma.room.findUnique = async () => null
            // Querying by display name "Sunday Express" finds the ended room
            prisma.room.findFirst = async () => endedRoom

            let removedParticipant = null
            const fakeClient = {
                removeParticipant: async (roomName, identity) => {
                    removedParticipant = { roomName, identity }
                }
            }

            // Webhook receives participant_joined in a room named "Sunday Express" (which was an old display name)
            await handleLiveKitEvent({
                event: 'participant_joined',
                room: { name: 'Sunday Express' },
                participant: { identity: 'rider_victim' }
            }, { roomServiceClient: fakeClient })

            assert.strictEqual(removedParticipant, null, 'Must NOT kick participant when matched by display-name fallback to ENDED room')

            prisma.room.findUnique = originals.roomFindUnique
            prisma.room.findFirst = originals.roomFindFirst
        })

        it('F-18: sweeper does not end a 12h+ ACTIVE room if riders are connected in LiveKit', async () => {
            const { sweepStaleRooms } = require('../src/services/roomSweeper')
            const thirteenHoursAgo = new Date(Date.now() - 13 * 60 * 60 * 1000)

            const longTourRoom = {
                id: 'tour-1234-uuid',
                name: 'Cross Country Tour',
                lkRoomName: 'rv_tour-1234-uuid',
                status: 'ACTIVE',
                startedAt: thirteenHoursAgo,
                createdAt: thirteenHoursAgo
            }

            const originals = {}
            originals.roomFindMany = prisma.room.findMany
            originals.roomUpdateMany = prisma.room.updateMany

            prisma.room.findMany = async (args) => {
                if (args?.where?.status === 'ACTIVE') return [longTourRoom]
                return []
            }
            let endedRoomId = null
            prisma.room.updateMany = async ({ where, data }) => {
                if (data.status === 'ENDED') endedRoomId = where.id
                return { count: 1 }
            }

            const fakeClient = {
                listParticipants: async (target) => {
                    if (target === 'rv_tour-1234-uuid') {
                        return [{ identity: 'rider_tourist_1' }, { identity: 'rider_tourist_2' }]
                    }
                    return []
                }
            }

            const swept = await sweepStaleRooms({ roomServiceClient: fakeClient })
            assert.strictEqual(endedRoomId, null, '12h active room with active riders must not be swept')
            assert.strictEqual(swept.length, 0)

            prisma.room.findMany = originals.roomFindMany
            prisma.room.updateMany = originals.roomUpdateMany
        })
    })
})

