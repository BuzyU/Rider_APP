const express = require('express')
const router = express.Router()
const cacheService = require('../services/cacheService')
const eventPipeline = require('../services/eventPipeline')

router.get(['/', '/health', '/api/health'], (req, res) => {
    res.json({
        status: 'ok',
        uptime: Math.floor(process.uptime()),
        timestamp: new Date().toISOString(),
        cache: cacheService.stats(),
        pipeline: eventPipeline.getStats(),
        memoryRssMb: (process.memoryUsage().rss / (1024 * 1024)).toFixed(1)
    })
})

module.exports = router
