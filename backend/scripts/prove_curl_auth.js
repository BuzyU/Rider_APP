const { spawn, execSync } = require('child_process')
const path = require('path')

async function testMode(name, envOverrides, port) {
    console.log(`\n======================================================`)
    console.log(` PROOF: ${name} (Port ${port})`)
    console.log(`======================================================`)

    const env = { ...process.env, PORT: String(port), ...envOverrides }
    if (envOverrides.NODE_ENV === undefined) delete env.NODE_ENV

    const serverProc = spawn('node', ['src/server.js'], {
        cwd: path.join(__dirname, '..'),
        env,
        stdio: ['ignore', 'pipe', 'pipe']
    })

    await new Promise((resolve) => {
        serverProc.stdout.on('data', data => {
            if (data.toString().includes('Server running on port')) resolve()
        })
        setTimeout(resolve, 2000)
    })

    try {
        const curlCmd = `curl.exe -i -X POST http://localhost:${port}/api/diagnostics/join -H "Authorization: Bearer test_x" -H "Content-Type: application/json" -d "{\\"convoyId\\":\\"Alpha-Convoy-Test\\"}"`
        const output = execSync(curlCmd, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
        console.log(output)
    } catch (e) {
        if (e.stdout) console.log(e.stdout.toString())
        else console.error('Curl error:', e.message)
    } finally {
        serverProc.kill('SIGTERM')
    }
}

async function main() {
    await testMode('1. NODE_ENV=production', { NODE_ENV: 'production', FIREBASE_SERVICE_ACCOUNT: '{"type":"service_account"}' }, 3001)
    await testMode('2. NODE_ENV=unset', { NODE_ENV: undefined }, 3002)
    await testMode('3. NODE_ENV=development', { NODE_ENV: 'development' }, 3003)
    await testMode('4. NODE_ENV=test (local DB)', { NODE_ENV: 'test', DATABASE_URL: 'postgresql://localhost:5432/test' }, 3004)
    await testMode('5. NODE_ENV=test (remote DB host db.supabase.co)', { NODE_ENV: 'test', DATABASE_URL: 'postgresql://postgres:pass@db.supabase.co:5432/postgres' }, 3005)
}

main().catch(console.error)
