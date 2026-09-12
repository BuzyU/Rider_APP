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
                    android: { priority: 'high' }
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

    async sendRideInvite(inviterHandle, inviteeId, roomName) {
        await this.sendToUser(inviteeId, {
            data: {
                type: 'RIDE_INVITE',
                inviterHandle: String(inviterHandle),
                roomName: String(roomName),
                channelId: 'CHANNEL_CONVOY'
            }
        })
    }

    async sendEmergencyAlert(userIds, alertType, lat, lng) {
        const payload = {
            data: {
                type: 'EMERGENCY',
                alertType: String(alertType),
                lat: String(lat ?? ''),
                lng: String(lng ?? ''),
                channelId: 'CHANNEL_EMERGENCY'
            }
        }
        await Promise.allSettled(
            userIds.map(uid => this.sendToUser(uid, payload))
        )
    }
}

module.exports = new NotificationService()
