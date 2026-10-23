const express = require('express')
const router = express.Router()
const prisma = require('../db')

function escapeHtml(str) {
    if (!str) return ''
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;')
}

router.get('/:token', async (req, res, next) => {
    res.set('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate')
    res.set('X-Robots-Tag', 'noindex, nofollow')

    const rawToken = req.params.token ? req.params.token.trim() : ''
    const apkUrl = process.env.APK_DOWNLOAD_URL || 'https://github.com/BuzyU/Rider_APP/releases/latest'

    if (!rawToken || !/^[A-Za-z0-9_-]{4,64}$/.test(rawToken)) {
        return res.status(404).send(renderInvalidPage(apkUrl))
    }

    try {
        const joinToken = await prisma.roomJoinToken.findUnique({
            where: { token: rawToken },
            include: {
                room: {
                    select: {
                        id: true,
                        name: true,
                        status: true
                    }
                }
            }
        })

        const now = new Date()
        const isExpired = joinToken && joinToken.expiresAt && new Date(joinToken.expiresAt) <= now
        const isRevoked = joinToken && joinToken.revokedAt != null
        const isMaxExceeded = joinToken && joinToken.maxUses != null && joinToken.useCount >= joinToken.maxUses
        const isRoomEnded = joinToken && joinToken.room && joinToken.room.status === 'ENDED'

        if (!joinToken || isExpired || isRevoked || isMaxExceeded || isRoomEnded) {
            return res.status(410).send(renderInvalidPage(apkUrl))
        }

        const safeConvoyName = escapeHtml(joinToken.room.name)
        const safeToken = escapeHtml(rawToken)
        const deepLinkIntent = `intent://join/${encodeURIComponent(rawToken)}#Intent;scheme=ridervoice;package=com.ridervoice;S.browser_fallback_url=${encodeURIComponent(apkUrl)};end`

        res.status(200).send(`<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Join ${safeConvoyName} - RiderVoice</title>
    <style>
        body {
            margin: 0;
            padding: 0;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
            background-color: #0F1216;
            color: #F0F4F8;
            display: flex;
            align-items: center;
            justify-content: center;
            min-height: 100vh;
        }
        .card {
            background-color: #171C22;
            border: 1px solid #2B3542;
            border-radius: 16px;
            padding: 32px 24px;
            max-width: 400px;
            width: 90%;
            text-align: center;
            box-shadow: 0 8px 32px rgba(0,0,0,0.4);
        }
        .badge {
            display: inline-block;
            background-color: #FF5500;
            color: #FFFFFF;
            font-size: 12px;
            font-weight: 700;
            padding: 4px 12px;
            border-radius: 20px;
            letter-spacing: 0.5px;
            margin-bottom: 16px;
        }
        h1 {
            font-size: 24px;
            font-weight: 800;
            margin: 0 0 12px 0;
            color: #FFFFFF;
        }
        p {
            font-size: 15px;
            color: #9BA3AF;
            margin: 0 0 28px 0;
            line-height: 1.5;
        }
        .btn {
            display: block;
            width: 100%;
            box-sizing: border-box;
            padding: 14px 20px;
            border-radius: 10px;
            font-size: 16px;
            font-weight: 700;
            text-decoration: none;
            cursor: pointer;
            border: none;
            margin-bottom: 12px;
            transition: opacity 0.2s;
        }
        .btn:hover { opacity: 0.9; }
        .btn-primary {
            background-color: #FF5500;
            color: #FFFFFF;
        }
        .btn-secondary {
            background-color: #242D38;
            color: #E2E8F0;
        }
        .btn-outline {
            background-color: transparent;
            color: #9BA3AF;
            border: 1px solid #334155;
            font-size: 14px;
        }
        .toast {
            display: none;
            margin-top: 10px;
            font-size: 13px;
            color: #00E676;
        }
    </style>
</head>
<body>
    <div class="card">
        <div class="badge">RIDERVOICE</div>
        <h1>Join Ride</h1>
        <p>You're invited to ride with <strong>${safeConvoyName}</strong> on RiderVoice.</p>
        
        <a href="${deepLinkIntent}" class="btn btn-primary">Open in RiderVoice</a>
        <button onclick="copyLink()" class="btn btn-secondary">Copy Invite Link</button>
        <a href="${escapeHtml(apkUrl)}" class="btn btn-outline">Download App</a>
        <div id="toast" class="toast">Link copied to clipboard!</div>
    </div>
    <script>
        function copyLink() {
            if (navigator.clipboard) {
                navigator.clipboard.writeText(window.location.href).then(function() {
                    var t = document.getElementById('toast');
                    t.style.display = 'block';
                    setTimeout(function() { t.style.display = 'none'; }, 2500);
                });
            }
        }
    </script>
</body>
</html>`)
    } catch (error) {
        return res.status(500).send(renderInvalidPage(apkUrl))
    }
})

function renderInvalidPage(apkUrl) {
    return `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Ride Invite Expired - RiderVoice</title>
    <style>
        body {
            margin: 0;
            padding: 0;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
            background-color: #0F1216;
            color: #F0F4F8;
            display: flex;
            align-items: center;
            justify-content: center;
            min-height: 100vh;
        }
        .card {
            background-color: #171C22;
            border: 1px solid #2B3542;
            border-radius: 16px;
            padding: 32px 24px;
            max-width: 400px;
            width: 90%;
            text-align: center;
        }
        h1 {
            font-size: 22px;
            font-weight: 700;
            margin: 0 0 12px 0;
            color: #FFFFFF;
        }
        p {
            font-size: 15px;
            color: #9BA3AF;
            margin: 0 0 24px 0;
            line-height: 1.5;
        }
        .btn {
            display: block;
            width: 100%;
            box-sizing: border-box;
            padding: 14px 20px;
            border-radius: 10px;
            font-size: 16px;
            font-weight: 700;
            text-decoration: none;
            cursor: pointer;
            background-color: #FF5500;
            color: #FFFFFF;
        }
    </style>
</head>
<body>
    <div class="card">
        <h1>Invite Unavailable</h1>
        <p>This ride invite link has expired or is no longer valid. Ask the host for a new link or ride code.</p>
        <a href="${escapeHtml(apkUrl)}" class="btn">Download RiderVoice</a>
    </div>
</body>
</html>`
}

module.exports = router
