const { AccessToken } = require('livekit-server-sdk')

async function createToken(roomName, participantName) {

    const at = new AccessToken(
        process.env.LIVEKIT_API_KEY,
        process.env.LIVEKIT_API_SECRET,
        {
            identity: participantName
        }
    )

    at.addGrant({
        roomJoin: true,
        room: roomName,
        canPublish: true,
        canSubscribe: true
    })

    return await at.toJwt()
}

module.exports = {
    createToken
}
