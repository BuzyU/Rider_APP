const express = require('express')
const router = express.Router()
const { createToken } = require('../services/tokenService')

router.get('/token', async (req, res) => {

    const room = req.query.room
    const user = req.query.user

    const token = createToken(room, user)

    res.json({
        roomName: room,
        token
    })
})

module.exports = router
