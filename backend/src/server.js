require('dotenv').config()

const express = require('express')
const cors = require('cors')

const roomRoutes = require('./routes/roomRoutes')
const healthRoute = require('./routes/healthRoute')

const app = express()

app.use(cors())
app.use(express.json())

app.use('/', roomRoutes)
app.use('/', healthRoute)

const PORT = process.env.PORT || 3000

app.listen(PORT, () => {
    console.log(`Server running on port ${PORT}`)
})
