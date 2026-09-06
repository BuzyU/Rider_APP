require('dotenv').config()

const express = require('express')
const cors = require('cors')

const healthRoute = require('./routes/healthRoute')
const authMiddleware = require('./middleware/authMiddleware')
const rateLimiter = require('./middleware/rateLimiter')
const errorMiddleware = require('./middleware/errorMiddleware')
const requestLogger = require('./middleware/requestLogger')
const profileRoutes = require('./routes/userRoutes')
const friendRoutes = require('./routes/friendRoutes')
const inviteRoutes = require('./routes/inviteRoutes')
const rideRoutes = require('./routes/rideRoutes')
const roomRoutes = require('./routes/roomRoutes')
const lobbyRoutes = require('./routes/lobbyRoutes')   // NEW
const emergencyRoutes = require('./routes/emergencyRoutes')

const app = express()

app.use(cors())
app.use(express.json({ limit: '10mb' }))
app.use(requestLogger)
app.use(rateLimiter)

// Public health check
app.use('/api/health', healthRoute)
app.use('/health', healthRoute)
app.use('/', healthRoute)

// Authenticated routes
app.use('/api/rooms', authMiddleware, roomRoutes)
app.use('/api/users', authMiddleware, profileRoutes)
app.use('/api/friends', authMiddleware, friendRoutes)
app.use('/api/rides', authMiddleware, rideRoutes)
app.use('/api/invites', authMiddleware, inviteRoutes)
app.use('/api/lobby', authMiddleware, lobbyRoutes)       // NEW
app.use('/api/emergency', authMiddleware, emergencyRoutes)

app.use(errorMiddleware)

const PORT = process.env.PORT || 3000
app.listen(PORT, () => console.log(`Server running on port ${PORT}`))