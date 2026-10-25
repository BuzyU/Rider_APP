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
})
