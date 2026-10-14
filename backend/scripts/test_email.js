require('dotenv').config()
const nodemailer = require('nodemailer')

async function testEmail() {
    const host = process.env.SMTP_HOST
    const port = parseInt(process.env.SMTP_PORT || '587', 10)
    const user = process.env.SMTP_USER
    const pass = process.env.SMTP_PASS
    const fromName = process.env.SMTP_FROM_NAME || 'RiderVoice Diagnostics'
    const from = process.env.SMTP_FROM || (user ? `"${fromName}" <${user}>` : `"${fromName}" <noreply@ridervoice.com>`)
    const to = process.env.DIAG_EMAIL_TO || user

    console.log('--- Network Diagnostics SMTP Test ---')
    console.log(`SMTP Host: ${host || '(not set)'}`)
    console.log(`SMTP Port: ${port}`)
    console.log(`SMTP User: ${user || '(not set)'}`)
    console.log(`Target Email: ${to || '(not set)'}`)

    if (!host || !user || !pass || !to) {
        console.error('\n[TestEmail] FAILED: Missing one or more required environment variables (SMTP_HOST, SMTP_USER, SMTP_PASS, DIAG_EMAIL_TO).')
        process.exit(1)
    }

    const transporter = nodemailer.createTransport({
        host,
        port,
        secure: port === 465,
        auth: { user, pass: (pass || '').replace(/\s+/g, '') }
    })

    console.log('\nVerifying SMTP connection credentials...')
    await transporter.verify()
    console.log('SMTP connection verified successfully.')

    console.log(`Sending test diagnostic report email to ${to}...`)
    const info = await transporter.sendMail({
        from,
        to,
        subject: `[Test] RiderVoice Diagnostics SMTP Verification (${new Date().toISOString()})`,
        text: 'This is a test email sent from npm run diag:test-email to verify SMTP relay functionality.'
    })

    console.log(`\n[TestEmail] SUCCESS! Message dispatched successfully.`)
    console.log(`Message ID: ${info.messageId}`)
    console.log(`Response: ${info.response || 'OK'}`)
}

testEmail().catch(err => {
    console.error(`\n[TestEmail] SMTP Relay Error: ${err.message}`)
    process.exit(1)
})
