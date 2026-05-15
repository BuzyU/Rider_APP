const express = require('express')
const router = express.Router()
const { createToken } = require('../services/tokenService')

router.get('/token', async (req, res) => {

    try {
        const room = req.query.room
        const user = req.query.user

        const token = await createToken(room, user)

        res.json({
            roomName: room,
            token
        })
    } catch (error) {
        console.error(error)
        res.status(500).json({
            error: error.message
        })
    }
})

module.exports = router
