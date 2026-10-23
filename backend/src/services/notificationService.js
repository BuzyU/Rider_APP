const admin = require('../config/firebaseAdmin')
const prisma = require('../db')

class NotificationService {
    async sendToUser(userId, payload) {
        try {
            const tokens = await prisma.deviceToken.findMany({
                where: { userId },
                select: { token: true }
            })
            if (tokens.length === 0) return

            const messages = new Array(tokens.length)
            for (let i = 0; i < tokens.length; i++) {
                messages[i] = {
                    token: tokens[i].token,
                    data: payload.data,
                    ...(payload.notification ? { notification: payload.notification } : {}),
                    android: {
                        priority: payload.android?.priority || 'high',
                        ...(payload.android?.ttl !== undefined ? { ttl: payload.android.ttl } : {}),
                        ...(payload.android?.collapseKey ? { collapseKey: payload.android.collapseKey } : {}),
                        ...(payload.notification ? {
                            notification: {
                                channelId: payload.data?.channelId || 'CHANNEL_CONVOY',
                                priority: 'high',
                                defaultSound: true
                            }
                        } : {})
                    }
                }
            }

            const response = await admin.messaging().sendEach(messages)

            const staleTokens = []
            for (let i = 0; i < response.responses.length; i++) {
                const r = response.responses[i]
                if (!r.success && r.error) {
                    const code = r.error.code ?? ''
                    if (
                        code === 'messaging/registration-token-not-registered' ||
                        code === 'messaging/invalid-registration-token'
                    ) {
                        staleTokens.push(messages[i].token)
                    }
                }
            }

            if (staleTokens.length > 0) {
                await prisma.deviceToken.deleteMany({
                    where: { token: { in: staleTokens } }
                })
            }

        } catch (error) {
            console.error('FCM sendToUser error:', error.message)
        }
    }

    async sendRideInvite(inviterHandle, inviteeId, roomName, senderCallsign = null) {
        const displayName = senderCallsign || inviterHandle
        await this.sendToUser(inviteeId, {
            notification: {
                title: 'Ride invite',
                body: `${displayName} invited you to join ${roomName}`
            },
            data: {
                type: 'RIDE_INVITE',
                inviterHandle: String(inviterHandle),
                senderName: String(displayName),
                callsign: String(displayName),
                roomName: String(roomName),
                channelId: 'CHANNEL_CONVOY'
            },
            android: {
                priority: 'high'
            }
        })
    }

    async sendInviteAccepted(riderName, inviterId, roomName, riderCallsign = null) {
        const displayName = riderCallsign || riderName
        await this.sendToUser(inviterId, {
            notification: {
                title: 'Invite accepted',
                body: `${displayName} joined ${roomName}`
            },
            data: {
                type: 'INVITE_ACCEPTED',
                riderName: String(displayName),
                callsign: String(displayName),
                roomName: String(roomName),
                channelId: 'CHANNEL_CONVOY'
            },
            android: {
                priority: 'high'
            }
        })
    }

    async sendEmergencyAlert(userIds, alertType, lat, lng, senderId = '', senderName = '', roomName = '', alertId = '') {
        const payload = {
            notification: {
                title: 'EMERGENCY SOS',
                body: `${senderName || 'A rider'} sent an emergency SOS alert!`
            },
            data: {
                type: 'EMERGENCY',
                alertId: String(alertId || ''),
                alertType: String(alertType),
                lat: String(lat ?? ''),
                lng: String(lng ?? ''),
                senderId: String(senderId ?? ''),
                senderName: String(senderName ?? ''),
                callsign: String(senderName ?? ''),
                roomName: String(roomName ?? ''),
                channelId: 'CHANNEL_EMERGENCY'
            },
            android: {
                priority: 'high',
                ttl: 60 * 1000,
                collapseKey: alertId ? `emergency_${alertId}` : `emergency_${senderId}`
            }
        }
        await Promise.allSettled(
            userIds.map(uid => this.sendToUser(uid, payload))
        )
    }
}

module.exports = new NotificationService()
