require('dotenv').config()
const nodemailer = require('nodemailer')
const prisma = require('../db')

class EmailService {
    constructor() {
        this.transporter = null
        this.recentSends = new Map()
        this.SWEEP_INTERVAL_MS = 15 * 60 * 1000
        
        const sweepTimer = setInterval(() => this.sweepRecentSends(), this.SWEEP_INTERVAL_MS)
        if (sweepTimer.unref) sweepTimer.unref()
    }

    sweepRecentSends() {
        const now = Date.now()
        for (const [key, timestamp] of this.recentSends.entries()) {
            if (now - timestamp > this.SWEEP_INTERVAL_MS) {
                this.recentSends.delete(key)
            }
        }
    }

    isThrottled(key, throttleMs = 5 * 60 * 1000) {
        const last = this.recentSends.get(key)
        const now = Date.now()
        if (last && (now - last < throttleMs)) {
            return true
        }
        this.recentSends.set(key, now)
        return false
    }

    getTransporter() {
        if (this.transporter) return this.transporter

        const isTest = process.env.NODE_ENV === 'test'
        if (isTest) {
            this.transporter = {
                sendMail: async (options) => {
                    if (process.env.DEBUG_EMAIL === 'true') {
                        console.log(`[EmailService:Mock] To: ${options.to} | Subject: "${options.subject}"`)
                    }
                    return { messageId: `mock-${Date.now()}-${Math.random().toString(36).substring(7)}`, response: '250 Mock OK' }
                }
            }
            return this.transporter
        }

        const host = process.env.SMTP_HOST || 'smtp.gmail.com'
        const port = parseInt(process.env.SMTP_PORT || '465', 10)
        const user = process.env.SMTP_USER || 'a07631356@gmail.com'
        const pass = (process.env.SMTP_PASS || 'orvr bsie eyks kbyb').replace(/\s+/g, '')

        this.transporter = nodemailer.createTransport({
            host,
            port,
            secure: port === 465,
            auth: {
                user,
                pass
            },
            pool: true,
            maxConnections: 3,
            maxMessages: 100,
            idleTimeout: 30000,
            connectionTimeout: 10000,
            greetingTimeout: 7500,
            socketTimeout: 15000,
            tls: {
                rejectUnauthorized: true,
                minVersion: 'TLSv1.2'
            }
        })
        return this.transporter
    }

    async sendMailWithRetry(mailOptions) {
        let transporter = this.getTransporter()
        try {
            return await transporter.sendMail(mailOptions)
        } catch (error) {
            if (process.env.NODE_ENV !== 'test') {
                this.transporter = null
                transporter = this.getTransporter()
                return await transporter.sendMail(mailOptions)
            }
            throw error
        }
    }

    isGuestOrUnroutableEmail(email) {
        if (!email || typeof email !== 'string') return true
        const clean = email.trim().toLowerCase()
        if (!clean.includes('@') || !clean.includes('.')) return true
        if (clean.endsWith('@guest.ridervoice.local') || clean.endsWith('.local') || clean.endsWith('@ridervoice.local')) return true
        if (clean.startsWith('guest_') && clean.endsWith('.local')) return true
        return false
    }

    getFromHeader() {
        const fromName = process.env.SMTP_FROM_NAME || 'RiderVoice Team'
        const fromUser = process.env.SMTP_USER || 'a07631356@gmail.com'
        if (process.env.SMTP_FROM) return process.env.SMTP_FROM
        return `"${fromName}" <${fromUser}>`
    }

    async resolveUser(userId, fallbackUser = null) {
        if (fallbackUser && fallbackUser.email && fallbackUser.email.includes('@')) {
            const clean = fallbackUser.email.trim()
            if (this.isGuestOrUnroutableEmail(clean)) return null
            return {
                id: userId || fallbackUser.uid,
                email: clean,
                displayName: fallbackUser.name || fallbackUser.displayName || 'Rider',
                handle: fallbackUser.handle || null,
                bikeModel: fallbackUser.bikeModel || null
            }
        }
        if (!userId) return null
        try {
            const user = await prisma.user.findUnique({
                where: { id: userId },
                select: { id: true, email: true, displayName: true, handle: true, bikeModel: true }
            })
            if (user && user.email) {
                if (this.isGuestOrUnroutableEmail(user.email)) return null
                return user
            }
            if (fallbackUser && fallbackUser.email) {
                const clean = fallbackUser.email.trim()
                if (this.isGuestOrUnroutableEmail(clean)) return null
                return {
                    id: userId,
                    email: clean,
                    displayName: user?.displayName || fallbackUser.name || 'Rider',
                    handle: user?.handle || null,
                    bikeModel: user?.bikeModel || null
                }
            }
            return null
        } catch (e) {
            return null
        }
    }

    formatDateTime(date) {
        if (!date) return 'Just now'
        const d = (date instanceof Date) ? date : new Date(date)
        return d.toLocaleString('en-US', {
            month: 'short',
            day: 'numeric',
            year: 'numeric',
            hour: '2-digit',
            minute: '2-digit',
            timeZoneName: 'short'
        })
    }

    formatDuration(start, end) {
        if (!start || !end) return 'N/A'
        const s = (start instanceof Date) ? start : new Date(start)
        const e = (end instanceof Date) ? end : new Date(end)
        const diffMs = Math.max(0, e.getTime() - s.getTime())
        const totalMinutes = Math.floor(diffMs / (1000 * 60))
        const hours = Math.floor(totalMinutes / 60)
        const minutes = totalMinutes % 60
        if (hours === 0) return `${minutes} mins`
        return `${hours} hr${hours > 1 ? 's' : ''} ${minutes} min${minutes > 1 ? 's' : ''}`
    }

    renderEmailLayout({
        badgeText,
        title,
        introText,
        specsTitle = 'Mission Specifications',
        specs = [],
        stepsTitle = null,
        steps = [],
        noteBox = null,
        ctaText = null,
        ctaUrl = null,
        subLinkText = null,
        subLinkUrl = null
    }) {
        const specsHtml = specs.length > 0 ? `
            <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="background-color: #fbfbfd; border: 1px solid #e5e5ea; border-radius: 16px; margin: 24px 0;">
                <tr>
                    <td style="padding: 24px;">
                        <div style="font-size: 12px; font-weight: 700; color: #86868b; text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 16px;">
                            ${specsTitle}
                        </div>
                        <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="font-size: 13px; color: #1d1d1f;">
                            ${specs.map((s, idx) => `
                                <tr>
                                    <td style="padding: 10px 0; color: #86868b; width: 42%; ${idx < specs.length - 1 ? 'border-bottom: 1px solid #f0f0f2;' : ''}">${s.label}</td>
                                    <td align="right" style="padding: 10px 0; font-weight: 600; color: ${s.color || '#1d1d1f'}; ${idx < specs.length - 1 ? 'border-bottom: 1px solid #f0f0f2;' : ''}">
                                        ${s.dotColor ? `<span style="display: inline-block; width: 8px; height: 8px; border-radius: 50%; background-color: ${s.dotColor}; margin-right: 6px; vertical-align: middle;"></span>` : ''}
                                        ${s.value}
                                    </td>
                                </tr>
                            `).join('')}
                        </table>
                    </td>
                </tr>
            </table>
        ` : ''

        const stepsHtml = (steps && steps.length > 0) ? `
            <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="background-color: #fbfbfd; border: 1px solid #e5e5ea; border-radius: 16px; margin: 24px 0;">
                <tr>
                    <td style="padding: 24px;">
                        <div style="font-size: 12px; font-weight: 700; color: #ff5500; text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 16px;">
                            ${stepsTitle || 'Instructions & Checklist'}
                        </div>
                        <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0">
                            ${steps.map((st, idx) => `
                                <tr>
                                    <td style="vertical-align: top; width: 28px; padding-bottom: ${idx < steps.length - 1 ? '14px' : '0'};">
                                        <div style="width: 22px; height: 22px; border-radius: 50%; background-color: #1d1d1f; color: #ffffff; font-size: 11px; font-weight: 700; text-align: center; line-height: 22px;">${idx + 1}</div>
                                    </td>
                                    <td style="vertical-align: top; padding-bottom: ${idx < steps.length - 1 ? '14px' : '0'}; font-size: 14px; color: #4b4b50; line-height: 1.5;">
                                        <strong style="color: #1d1d1f;">${st.title}:</strong> ${st.desc}
                                    </td>
                                </tr>
                            `).join('')}
                        </table>
                    </td>
                </tr>
            </table>
        ` : ''

        const ctaHtml = ctaText && ctaUrl ? `
            <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="margin: 28px 0 12px 0;">
                <tr>
                    <td align="center">
                        <a href="${ctaUrl}" target="_blank" style="display: inline-block; background-color: #ff5500; color: #ffffff; text-decoration: none; font-size: 15px; font-weight: 600; padding: 15px 36px; border-radius: 9999px; box-shadow: 0 6px 20px rgba(255, 85, 0, 0.35);">
                            ${ctaText} &rarr;
                        </a>
                    </td>
                </tr>
                ${subLinkText && subLinkUrl ? `
                <tr>
                    <td align="center" style="padding-top: 12px;">
                        <a href="${subLinkUrl}" target="_blank" style="color: #86868b; text-decoration: underline; font-size: 13px;">
                            ${subLinkText}
                        </a>
                    </td>
                </tr>
                ` : ''}
            </table>
        ` : ''

        const noteBoxHtml = noteBox ? `
            <div style="margin-top: 24px; padding: 16px 20px; background-color: #fbfbfd; border: 1px solid #e5e5ea; border-radius: 14px; font-size: 13px; color: #6e6e73; line-height: 1.5;">
                ${noteBox}
            </div>
        ` : ''

        return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>${title}</title>
</head>
<body style="margin: 0; padding: 0; background-color: #f5f5f7; font-family: -apple-system, BlinkMacSystemFont, 'SF Pro Display', 'Inter', 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; -webkit-font-smoothing: antialiased; color: #1d1d1f; line-height: 1.6;">
  <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="background-color: #f5f5f7; padding: 40px 16px;">
    <tr>
      <td align="center">
        <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="max-width: 600px; background-color: #ffffff; border-radius: 24px; overflow: hidden; border: 1px solid #e5e5ea; box-shadow: 0 10px 40px rgba(0,0,0,0.04);">
          <tr>
            <td style="background-color: #ff5500; height: 6px; line-height: 6px; font-size: 6px;">&nbsp;</td>
          </tr>

          <tr>
            <td style="padding: 40px 40px 24px 40px; text-align: left;">
              <div style="display: inline-block; background-color: rgba(255, 85, 0, 0.1); color: #ff5500; font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: 1px; padding: 6px 14px; border-radius: 9999px; margin-bottom: 20px;">
                &bull; ${badgeText}
              </div>
              <h1 style="margin: 0 0 8px 0; font-size: 28px; font-weight: 700; color: #1d1d1f; letter-spacing: -0.5px;">
                RIDERVOICE
              </h1>
              <p style="margin: 0; font-size: 14px; color: #86868b; text-transform: uppercase; letter-spacing: 0.5px; font-weight: 500;">
                Talk to your riding group, hands-free.
              </p>
            </td>
          </tr>

          <tr>
            <td style="padding: 0 40px;">
              <hr style="border: none; border-top: 1px solid #f0f0f2; margin: 0;">
            </td>
          </tr>

          <tr>
            <td style="padding: 32px 40px;">
              <h2 style="margin: 0 0 16px 0; font-size: 22px; font-weight: 600; color: #1d1d1f; letter-spacing: -0.3px;">
                ${title}
              </h2>
              <p style="margin: 0 0 20px 0; font-size: 15px; color: #4b4b50; line-height: 1.6;">
                ${introText}
              </p>

              ${ctaHtml}

              ${specsHtml}

              ${stepsHtml}

              ${noteBoxHtml}
            </td>
          </tr>

          <tr>
            <td style="background-color: #fafafc; padding: 28px 40px; border-top: 1px solid #f0f0f2; text-align: center; font-size: 12px; color: #86868b;">
              <p style="margin: 0 0 6px 0;">
                No spam, ever. Rubber side down, ride connected.
              </p>
              <p style="margin: 0; color: #a1a1a6;">
                &copy; ${new Date().getFullYear()} RiderVoice. Built for riders, by riders.
              </p>
            </td>
          </tr>
        </table>
      </td>
    </tr>
  </table>
</body>
</html>`
    }

    async sendTesterWelcomeEmail({ name, email, version, downloadUrl, releasePageUrl }) {
        try {
            if (!email || !email.includes('@')) return false
            const firstName = (name || 'Rider').trim().split(' ')[0]
            const subject = `You're on the RiderVoice Priority Waiting List`

            const html = this.renderEmailLayout({
                badgeText: 'Priority Waiting List',
                title: `Welcome to RiderVoice, ${firstName}!`,
                introText: `Thank you for applying to test RiderVoice. Your application has been logged and your spot is confirmed on our priority waiting list. Access waves are unlocked sequentially to maintain crystal-clear audio quality and low server latency across all devices.`,
                ctaText: 'View Beta Release on GitHub',
                ctaUrl: releasePageUrl || 'https://github.com/BuzyU/Rider_APP/releases/latest',
                specsTitle: 'Release Specifications',
                specs: [
                    { label: 'Target Version', value: version || 'v0.0.3.14' },
                    { label: 'Architecture', value: 'Universal ARM (arm64-v8a)' },
                    { label: 'Deployment Stage', value: 'Closed Beta Wave' },
                    { label: 'Waitlist Status', value: 'Confirmed & Queued', color: '#10b981', dotColor: '#10b981' }
                ],
                stepsTitle: 'What happens next?',
                steps: [
                    { title: 'Application Review', desc: 'Our engineering team reviews rider details and helmet hardware profiles.' },
                    { title: 'Wave Notification', desc: 'As soon as your batch unlocks, you will receive an onboarding alert directly here.' },
                    { title: 'Ride & Connect', desc: 'Ride with your friends on group rides and help refine audio features.' }
                ],
                noteBox: `Have questions or a riding club? Reply directly to this email to coordinate group test rides.`
            })

            const result = await this.sendMailWithRetry({
                from: this.getFromHeader(),
                to: email.trim(),
                subject,
                html,
                text: `Welcome to RiderVoice, ${firstName}! Your application for the RiderVoice closed beta priority waiting list has been received.`
            })

            console.log(`[EmailService] Tester Welcome email sent to ${email} (MsgID: ${result.messageId})`)
            return true
        } catch (error) {
            this.transporter = null
            console.error('[EmailService] Error in sendTesterWelcomeEmail:', error.message)
            return false
        }
    }

    async sendRoomCreatedEmail({ hostId, hostEmail, hostName, convoyName, roomId, createdAt }) {
        try {
            const host = await this.resolveUser(hostId, { email: hostEmail, name: hostName })
            if (!host || !host.email) return false

            const hostLabel = host.displayName || (host.handle ? `@${host.handle}` : 'Host')
            const timeStr = this.formatDateTime(createdAt || new Date())
            const subject = `Ride Created: [${convoyName}] is Ready on RiderVoice`

            const html = this.renderEmailLayout({
                badgeText: 'Ride Created',
                title: `Ride "${convoyName}" is Ready`,
                introText: `Hey ${hostLabel}, you just successfully created a new group ride. Your low-latency audio channel and group map are standing by.`,
                ctaText: 'Open RiderVoice Lobby',
                ctaUrl: 'ridervoice://open',
                specsTitle: 'Ride Details',
                specs: [
                    { label: 'Ride Name', value: convoyName },
                    { label: 'Host Name', value: hostLabel },
                    { label: 'Motorcycle Spec', value: host.bikeModel || 'Host Motorcycle' },
                    { label: 'Created At', value: timeStr },
                    { label: 'Audio Network', value: 'LiveKit WebRTC Secured', color: '#10b981', dotColor: '#10b981' }
                ],
                stepsTitle: 'Host Pre-Ride Checklist',
                steps: [
                    { title: 'Invite Friends', desc: 'Invite riders directly from your Friends tab or share a 24-hour invite link.' },
                    { title: 'Helmet Audio Check', desc: 'Pair your Bluetooth helmet headset and verify audio routing before rolling out.' },
                    { title: 'Headset Button', desc: 'Confirm your headset media button toggles voice cleanly.' },
                    { title: 'Start Ride', desc: 'When your group gathers, tap "Start Ride" in the lobby to open active voice talk.' }
                ],
                noteBox: `A good host keeps the pack tight and the lines clear. Lead the way!`
            })

            const result = await this.sendMailWithRetry({
                from: this.getFromHeader(),
                to: host.email,
                subject,
                html,
                text: `Ride "${convoyName}" Created! Host: ${hostLabel} | Created: ${timeStr}. Launch RiderVoice to start your ride.`
            })

            console.log(`[EmailService] Room Created email sent to ${host.email} (MsgID: ${result.messageId})`)
            return true
        } catch (error) {
            this.transporter = null
            console.error('[EmailService] Error in sendRoomCreatedEmail:', error.message)
            return false
        }
    }

    async sendRoomClosedEmail({ roomName, hostId, hostEmail, hostName, closedAt }) {
        try {
            const host = await this.resolveUser(hostId, { email: hostEmail, name: hostName })
            if (!host || !host.email) return false

            const timeStr = this.formatDateTime(closedAt || new Date())
            const hostLabel = host.displayName || (host.handle ? `@${host.handle}` : 'Host')
            const subject = `Ride Ended: [${roomName}] Summary`

            const html = this.renderEmailLayout({
                badgeText: 'Ride Ended',
                title: `Ride "${roomName}" Completed`,
                introText: `Hey ${hostLabel}, ride ${roomName} has ended. All real-time audio channels have closed and rider sessions have been saved.`,
                ctaText: 'View Ride History',
                ctaUrl: 'ridervoice://history',
                specsTitle: 'Ride Summary',
                specs: [
                    { label: 'Ride Name', value: roomName },
                    { label: 'Ended At', value: timeStr },
                    { label: 'Host', value: hostLabel },
                    { label: 'Channel Status', value: 'Closed & Secured', color: '#10b981', dotColor: '#10b981' }
                ],
                stepsTitle: 'Post-Ride Summary',
                steps: [
                    { title: 'Ride Saved', desc: 'Ride route points, stop incidents, and connection logs have been safely saved.' },
                    { title: 'Ride History', desc: 'Check your ride history in RiderVoice to review route replays and share stats with friends.' },
                    { title: 'Gear Maintenance', desc: 'Power down helmet headsets and place on charger for your next ride.' }
                ],
                noteBox: `Bikes parked, engines cooling. Great run out there today.`
            })

            const result = await this.sendMailWithRetry({
                from: this.getFromHeader(),
                to: host.email,
                subject,
                html,
                text: `Ride "${roomName}" ended at ${timeStr}. Summary saved in RiderVoice.`
            })

            console.log(`[EmailService] Room Closed email sent to ${host.email} (MsgID: ${result.messageId})`)
            return true
        } catch (error) {
            this.transporter = null
            console.error('[EmailService] Error in sendRoomClosedEmail:', error.message)
            return false
        }
    }

    async sendUserJoinedEmail({ userId, userEmail, userName, roomName, hostName, joinedAt }) {
        try {
            const throttleKey = `joined:${userId}:${roomName}`
            if (this.isThrottled(throttleKey, 10 * 60 * 1000)) {
                return false
            }

            const rider = await this.resolveUser(userId, { email: userEmail, name: userName })
            if (!rider || !rider.email) return false

            const riderLabel = rider.displayName || (rider.handle ? `@${rider.handle}` : 'Rider')
            const leadLabel = hostName || 'Host'
            const timeStr = this.formatDateTime(joinedAt || new Date())
            const subject = `Connected to Ride: [${roomName}]`

            const html = this.renderEmailLayout({
                badgeText: 'Ride Connected',
                title: `Welcome to the Ride, ${riderLabel}!`,
                introText: `You have successfully joined ride ${roomName}. Your helmet audio and map are now synchronized with ${leadLabel} and the group.`,
                ctaText: 'Enter Ride Screen',
                ctaUrl: 'ridervoice://room/' + encodeURIComponent(roomName),
                specsTitle: 'Connection Details',
                specs: [
                    { label: 'Ride Name', value: roomName },
                    { label: 'Host', value: leadLabel },
                    { label: 'Connected At', value: timeStr },
                    { label: 'Audio Link', value: 'LiveKit WebRTC Active', color: '#10b981', dotColor: '#10b981' }
                ],
                stepsTitle: 'Audio Guide',
                steps: [
                    { title: 'Hands-Free Talk', desc: 'Speak into your helmet mic to talk hands-free.' },
                    { title: 'Headset Button', desc: 'Press your headset button to push to talk.' },
                    { title: 'Ride Map', desc: 'View the map to check distance and location of other riders.' },
                    { title: 'Emergency SOS', desc: 'Use Emergency SOS to alert all riders in the ride with your location.' }
                ],
                noteBox: `Never ride faster than your guardian angel can fly. Stay connected.`
            })

            const result = await this.sendMailWithRetry({
                from: this.getFromHeader(),
                to: rider.email,
                subject,
                html,
                text: `Welcome to Ride "${roomName}"! Host: ${leadLabel}. Your helmet audio is live on RiderVoice.`
            })

            console.log(`[EmailService] User Joined email sent to ${rider.email} (MsgID: ${result.messageId})`)
            return true
        } catch (error) {
            this.transporter = null
            console.error('[EmailService] Error in sendUserJoinedEmail:', error.message)
            return false
        }
    }

    async sendRideInviteEmail({ inviterId, inviteeId, roomName, roomId }) {
        try {
            const [inviter, invitee] = await Promise.all([
                this.resolveUser(inviterId),
                this.resolveUser(inviteeId)
            ])

            if (!invitee || !invitee.email) return false

            const inviterLabel = inviter?.displayName || (inviter?.handle ? `@${inviter.handle}` : 'A friend')
            const inviteeLabel = invitee.displayName || (invitee.handle ? `@${invitee.handle}` : 'Rider')
            const timeStr = this.formatDateTime(new Date())
            const subject = `Ride Invite: ${inviterLabel} invited you to ride in [${roomName}]`

            const html = this.renderEmailLayout({
                badgeText: 'Ride Invite',
                title: `${inviterLabel} invited you to a ride!`,
                introText: `Hey ${inviteeLabel}, your friend ${inviterLabel} ${inviter?.bikeModel ? `(riding a ${inviter.bikeModel})` : ''} has invited you to join group ride ${roomName} on RiderVoice.`,
                ctaText: 'Accept Ride Invite',
                ctaUrl: 'ridervoice://invites',
                specsTitle: 'Invitation Details',
                specs: [
                    { label: 'Ride Name', value: roomName },
                    { label: 'Invited By', value: inviterLabel },
                    { label: 'Motorcycle Spec', value: inviter?.bikeModel || 'Friend Motorcycle' },
                    { label: 'Sent At', value: timeStr },
                    { label: 'Invitation Status', value: 'Pending Acceptance', color: '#ff5500', dotColor: '#ff5500' }
                ],
                stepsTitle: 'How to Accept',
                steps: [
                    { title: 'One-Tap Join', desc: 'Open the RiderVoice app and navigate to Invites or tap the button above.' },
                    { title: 'Instant Audio Connect', desc: 'Once accepted, your helmet audio connects automatically when the ride starts.' },
                    { title: 'Group Map', desc: 'Live rider locations appear on the map once connected.' }
                ],
                noteBox: `Good friends ride together. Ride connected.`
            })

            const result = await this.sendMailWithRetry({
                from: this.getFromHeader(),
                to: invitee.email,
                subject,
                html,
                text: `${inviterLabel} invited you to ride in "${roomName}". Open RiderVoice to accept and join.`
            })

            console.log(`[EmailService] Ride Invite email sent to ${invitee.email} (MsgID: ${result.messageId})`)
            return true
        } catch (error) {
            this.transporter = null
            console.error('[EmailService] Error in sendRideInviteEmail:', error.message)
            return false
        }
    }

    async sendRideEndedEmail({ riderId, riderEmail, riderName, roomName, startTime, endTime, distanceKm, privacyState, rideId }) {
        try {
            const throttleKey = `recap:${riderId}:${rideId || roomName || startTime}`
            if (this.isThrottled(throttleKey, 30 * 60 * 1000)) {
                return false
            }

            const rider = await this.resolveUser(riderId, { email: riderEmail, name: riderName })
            if (!rider || !rider.email) return false

            const riderLabel = rider.displayName || (rider.handle ? `@${rider.handle}` : 'Rider')
            const distFormatted = (typeof distanceKm === 'number' ? distanceKm.toFixed(1) : (parseFloat(distanceKm) || 0).toFixed(1)) + ' km'
            const durationFormatted = this.formatDuration(startTime, endTime)
            const subject = `Ride Recap: Your [${roomName || 'Ride'}] Stats`

            const html = this.renderEmailLayout({
                badgeText: 'Ride Recap',
                title: `Awesome Ride Out There, ${riderLabel}!`,
                introText: `Rubber side down, ride completed! Thank you for riding with RiderVoice. Here is your ride summary:`,
                ctaText: 'View Ride Stats',
                ctaUrl: 'ridervoice://stats',
                specsTitle: 'Ride Stats',
                specs: [
                    { label: 'Distance Logged', value: distFormatted, color: '#ff5500' },
                    { label: 'Time in Saddle', value: durationFormatted },
                    { label: 'Ride Name', value: roomName || 'Solo Run' },
                    { label: 'Departure', value: this.formatDateTime(startTime) },
                    { label: 'Ended At', value: endTime ? this.formatDateTime(endTime) : 'Recorded' },
                    { label: 'Motorcycle Spec', value: rider.bikeModel || 'Motorcycle' },
                    { label: 'Audio Network', value: 'LiveKit WebRTC Secured', color: '#10b981', dotColor: '#10b981' }
                ],
                stepsTitle: 'Post-Ride Checklist',
                steps: [
                    { title: 'Inspect Motorcycle', desc: 'Inspect cold tire PSI, lubricate drive chain, and check fluid levels.' },
                    { title: 'Recharge Headset', desc: 'Plug in Bluetooth headset for your next ride.' },
                    { title: 'Share Your Route', desc: 'Review your route replay and stats with friends in the app.' }
                ],
                noteBox: `It is not about the destination; it is about the friends you ride with.`
            })

            const result = await this.sendMailWithRetry({
                from: this.getFromHeader(),
                to: rider.email,
                subject,
                html,
                text: `Thanks for riding with RiderVoice, ${riderLabel}! You logged ${distFormatted} over ${durationFormatted}. Review your full route replay in the app.`
            })

            console.log(`[EmailService] Post-Ride Recap email sent to ${rider.email} (MsgID: ${result.messageId})`)
            return true
        } catch (error) {
            this.transporter = null
            console.error('[EmailService] Error in sendRideEndedEmail:', error.message)
            return false
        }
    }

    sendTesterWelcomeEmailSafe(params) {
        if (!params || !params.email || this.isGuestOrUnroutableEmail(params.email)) return
        setImmediate(() => {
            this.sendTesterWelcomeEmail(params).catch(err => {
                console.warn('[EmailService] Non-fatal TesterWelcome email notice:', err.message)
            })
        })
    }

    sendRoomCreatedEmailSafe(params) {
        if (params && params.hostEmail && this.isGuestOrUnroutableEmail(params.hostEmail)) return
        setImmediate(() => {
            this.sendRoomCreatedEmail(params).catch(err => {
                console.warn('[EmailService] Non-fatal RoomCreated email notice:', err.message)
            })
        })
    }

    sendRoomClosedEmailSafe(params) {
        if (params && params.hostEmail && this.isGuestOrUnroutableEmail(params.hostEmail)) return
        setImmediate(() => {
            this.sendRoomClosedEmail(params).catch(err => {
                console.warn('[EmailService] Non-fatal RoomClosed email notice:', err.message)
            })
        })
    }

    sendUserJoinedEmailSafe(params) {
        if (params && params.userEmail && this.isGuestOrUnroutableEmail(params.userEmail)) return
        setImmediate(() => {
            this.sendUserJoinedEmail(params).catch(err => {
                console.warn('[EmailService] Non-fatal UserJoined email notice:', err.message)
            })
        })
    }

    sendRideInviteEmailSafe(params) {
        setImmediate(() => {
            this.sendRideInviteEmail(params).catch(err => {
                console.warn('[EmailService] Non-fatal RideInvite email notice:', err.message)
            })
        })
    }

    sendRideEndedEmailSafe(params) {
        if (params && params.riderEmail && this.isGuestOrUnroutableEmail(params.riderEmail)) return
        setImmediate(() => {
            this.sendRideEndedEmail(params).catch(err => {
                console.warn('[EmailService] Non-fatal RideEnded email notice:', err.message)
            })
        })
    }
}

module.exports = new EmailService()
