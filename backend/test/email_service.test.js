process.env.NODE_ENV = 'test'
const { describe, it } = require('node:test')
const assert = require('node:assert')
const emailService = require('../src/services/emailService')

describe('RiderVoice Tactical Email Notification Service', () => {
    it('initializes mock transporter in test mode without throwing', () => {
        const transporter = emailService.getTransporter()
        assert.ok(transporter, 'Transporter must be defined')
        assert.strictEqual(typeof transporter.sendMail, 'function', 'Transporter must have sendMail function')
    })

    it('renders master email layout with brand banner, pill badge, and typography', () => {
        const html = emailService.renderEmailLayout({
            badgeText: 'TEST BADGE',
            title: 'Test Email Title',
            introText: 'Test introductory text for tactical rider.',
            specsTitle: 'Test Specs',
            specs: [
                { label: 'Convoy', value: 'Squad Alpha' },
                { label: 'Speed', value: '85 km/h', color: '#ff5500' }
            ],
            stepsTitle: 'Checklist',
            steps: [
                { title: 'Step 1', desc: 'Do something first' }
            ],
            ctaText: 'Test CTA',
            ctaUrl: 'https://ridervoice.com/test'
        })

        assert.ok(html.includes('RIDERVOICE'), 'HTML must include brand header RIDERVOICE')
        assert.ok(html.includes('TEST BADGE'), 'HTML must include badge text')
        assert.ok(html.includes('Test Email Title'), 'HTML must include title')
        assert.ok(html.includes('Squad Alpha'), 'HTML must render spec values')
        assert.ok(html.includes('Test CTA'), 'HTML must render CTA button')
        assert.ok(html.includes('https://ridervoice.com/test'), 'HTML must render CTA URL')
    })

    it('formats duration correctly across different time spans', () => {
        const start = new Date('2026-10-15T10:00:00Z')
        const end45m = new Date('2026-10-15T10:45:00Z')
        const end2h = new Date('2026-10-15T12:15:00Z')

        assert.strictEqual(emailService.formatDuration(start, end45m), '45 mins')
        assert.strictEqual(emailService.formatDuration(start, end2h), '2 hrs 15 mins')
        assert.strictEqual(emailService.formatDuration(null, end45m), 'N/A')
    })

    it('formats date and time accurately', () => {
        const d = new Date('2026-10-15T14:30:00Z')
        const formatted = emailService.formatDateTime(d)
        assert.ok(typeof formatted === 'string' && formatted.length > 5, 'Date string must be formatted')
        assert.strictEqual(emailService.formatDateTime(null), 'Just now')
    })

    it('sendTesterWelcomeEmail sends email to applicant with waitlist details', async () => {
        const result = await emailService.sendTesterWelcomeEmail({
            name: 'Piyush Dukare',
            email: 'piyushdukare789@gmail.com',
            version: 'v0.0.3.10'
        })
        assert.strictEqual(result, true, 'sendTesterWelcomeEmail must return true')
    })

    it('sendRoomCreatedEmail sends email to host with convoy details and pre-ride checklist', async () => {
        const result = await emailService.sendRoomCreatedEmail({
            hostId: 'test-host-uid',
            hostEmail: 'host@ridervoice.test',
            hostName: 'Captain Ghost',
            convoyName: 'Ghost Squadron High-Pass',
            roomId: 'room-123',
            createdAt: new Date()
        })
        assert.strictEqual(result, true, 'sendRoomCreatedEmail must return true')
    })

    it('sendRoomClosedEmail sends debrief email to host with stand-down stats', async () => {
        const result = await emailService.sendRoomClosedEmail({
            roomName: 'Ghost Squadron High-Pass',
            hostId: 'test-host-uid',
            hostEmail: 'host@ridervoice.test',
            hostName: 'Captain Ghost',
            closedAt: new Date()
        })
        assert.strictEqual(result, true, 'sendRoomClosedEmail must return true')
    })

    it('sendUserJoinedEmail sends welcome comms email to joining rider', async () => {
        const result = await emailService.sendUserJoinedEmail({
            userId: 'rider-unique-join-1',
            userEmail: 'rider@ridervoice.test',
            userName: 'LoneWolf',
            roomName: 'Highland Trail Pack',
            hostName: 'Captain Ghost',
            joinedAt: new Date()
        })
        assert.strictEqual(result, true, 'sendUserJoinedEmail must return true on first join')

        const throttledResult = await emailService.sendUserJoinedEmail({
            userId: 'rider-unique-join-1',
            userEmail: 'rider@ridervoice.test',
            userName: 'LoneWolf',
            roomName: 'Highland Trail Pack',
            hostName: 'Captain Ghost',
            joinedAt: new Date()
        })
        assert.strictEqual(throttledResult, false, 'sendUserJoinedEmail must be throttled on rapid reconnect')
    })

    it('sendRideInviteEmail sends tactical invite callout to invitee', async () => {
        const result = await emailService.sendRideInviteEmail({
            inviterId: 'test-inviter-1',
            inviteeId: 'test-invitee-1',
            roomName: 'Coastal Thunder Run',
            roomId: 'room-coastal-1'
        })
        assert.strictEqual(typeof result, 'boolean', 'sendRideInviteEmail must return boolean')
    })

    it('sendRideEndedEmail sends telemetry recap and debrief email', async () => {
        const result = await emailService.sendRideEndedEmail({
            riderId: 'rider-recap-unique-1',
            riderEmail: 'recap.rider@ridervoice.test',
            riderName: 'Speedy Demon',
            roomName: 'Sunday Morning Canyon Carve',
            startTime: new Date(Date.now() - 3600000),
            endTime: new Date(),
            distanceKm: 58.4,
            privacyState: 'PUBLIC',
            rideId: 'ride-session-abc-123'
        })
        assert.strictEqual(result, true, 'sendRideEndedEmail must return true')
    })

    it('safe methods execute asynchronously without throwing or interrupting', () => {
        assert.doesNotThrow(() => {
            emailService.sendTesterWelcomeEmailSafe({ name: 'Safe Tester', email: 'tester@safe.test' })
            emailService.sendRoomCreatedEmailSafe({ hostId: 'safe-host', hostEmail: 'host@safe.test', convoyName: 'Safe Convoy' })
            emailService.sendRoomClosedEmailSafe({ roomName: 'Safe Convoy', hostId: 'safe-host', hostEmail: 'host@safe.test' })
            emailService.sendUserJoinedEmailSafe({ userId: 'safe-user', userEmail: 'user@safe.test', roomName: 'Safe Convoy' })
            emailService.sendRideInviteEmailSafe({ inviterId: 'safe-host', inviteeId: 'safe-user', roomName: 'Safe Convoy' })
            emailService.sendRideEndedEmailSafe({ riderId: 'safe-rider', riderEmail: 'rider@safe.test', roomName: 'Safe Convoy' })
        }, 'Safe async dispatch wrappers must never throw')
    })
})
