const express = require('express')
const router = express.Router()
const prisma = require('../db')
const emailService = require('../services/emailService')

router.post('/', async (req, res, next) => {
    try {
        const { name, email, phone, deviceModel, motorcycleModel, experienceNotes } = req.body

        if (!name || typeof name !== 'string' || name.trim().length === 0) {
            return res.status(400).json({ error: 'Please enter your name.' })
        }

        if (!email || typeof email !== 'string' || !email.includes('@')) {
            return res.status(400).json({ error: 'Please enter a valid email address.' })
        }

        const normalizedEmail = email.trim().toLowerCase()
        const normalizedName = name.trim()

        let application = null
        try {
            const existing = await prisma.testerApplication.findFirst({
                where: { email: normalizedEmail }
            })

            if (existing) {
                application = await prisma.testerApplication.update({
                    where: { id: existing.id },
                    data: {
                        name: normalizedName,
                        phone: phone ? phone.trim() : null,
                        deviceModel: deviceModel ? deviceModel.trim() : null,
                        motorcycleModel: motorcycleModel ? motorcycleModel.trim() : null,
                        experienceNotes: experienceNotes ? experienceNotes.trim() : null,
                        downloadCount: (existing.downloadCount || 1) + 1
                    }
                })
            } else {
                application = await prisma.testerApplication.create({
                    data: {
                        name: normalizedName,
                        email: normalizedEmail,
                        phone: phone ? phone.trim() : null,
                        deviceModel: deviceModel ? deviceModel.trim() : null,
                        motorcycleModel: motorcycleModel ? motorcycleModel.trim() : null,
                        experienceNotes: experienceNotes ? experienceNotes.trim() : null,
                        status: 'PENDING',
                        downloadCount: 1
                    }
                })
            }
        } catch (dbErr) {
            console.warn('[TesterApplication] DB notice:', dbErr.message)
        }

        emailService.sendTesterWelcomeEmailSafe({
            name: normalizedName,
            email: normalizedEmail,
            version: 'v0.0.3.12',
            releasePageUrl: 'https://github.com/BuzyU/Rider_APP/releases/latest'
        })

        res.status(200).json({
            success: true,
            message: 'Application received! You are on our priority waitlist.',
            application
        })
    } catch (error) {
        next(error)
    }
})

router.get('/health', (req, res) => {
    res.json({ status: 'ok', service: 'tester-application' })
})

module.exports = router

