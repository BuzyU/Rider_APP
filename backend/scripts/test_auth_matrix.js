const authMiddleware = require('../src/middleware/authMiddleware')

async function runAuthMatrixTest() {
    console.log('====================================================')
    console.log(' Running Test-Auth Proof Matrix (authMiddleware.js)')
    console.log('====================================================\n')

    const originalEnv = process.env.NODE_ENV
    const originalDb = process.env.DATABASE_URL

    const scenarios = [
        { name: '1. NODE_ENV=production', env: 'production', db: 'postgresql://localhost:5432/test', expectedStatus: 401 },
        { name: '2. NODE_ENV=unset', env: undefined, db: 'postgresql://localhost:5432/test', expectedStatus: 401 },
        { name: '3. NODE_ENV=development', env: 'development', db: 'postgresql://localhost:5432/test', expectedStatus: 401 },
        { name: '4. NODE_ENV=test (localhost DB)', env: 'test', db: 'postgresql://localhost:5432/test', expectedStatus: 'NEXT' },
        { name: '5. NODE_ENV=test (remote DB host)', env: 'test', db: 'postgresql://postgres:pass@db.supabase.co:5432/postgres', expectedStatus: 401 }
    ]

    let allPassed = true

    for (const sc of scenarios) {
        if (sc.env === undefined) {
            delete process.env.NODE_ENV
        } else {
            process.env.NODE_ENV = sc.env
        }
        process.env.DATABASE_URL = sc.db

        let statusReturned = null
        let jsonReturned = null
        let reachedHandler = false

        const req = {
            headers: {
                authorization: 'Bearer test_rider_alpha'
            }
        }
        const res = {
            status: function (code) {
                statusReturned = code
                return {
                    json: function (payload) {
                        jsonReturned = payload
                    }
                }
            }
        }
        const next = function () {
            reachedHandler = true
        }

        try {
            await authMiddleware(req, res, next)
        } catch (e) {
            statusReturned = 'THREW_EXCEPTION'
            jsonReturned = { error: e.message }
        }

        const passed = sc.expectedStatus === 'NEXT'
            ? reachedHandler === true
            : (statusReturned === sc.expectedStatus && reachedHandler === false)

        const outcome = passed ? 'PASS' : 'FAIL'
        console.log(`[${outcome}] ${sc.name}`)
        console.log(`       Result: Status=${statusReturned}, ReachedHandler=${reachedHandler}, Response=${JSON.stringify(jsonReturned)}`)

        if (!passed) allPassed = false
    }

    // Restore env
    process.env.NODE_ENV = originalEnv
    process.env.DATABASE_URL = originalDb

    console.log('\n====================================================')
    console.log(` Matrix Test Result: ${allPassed ? 'ALL PASSED' : 'FAILURES OCCURRED'}`)
    console.log('====================================================')

    process.exit(allPassed ? 0 : 1)
}

runAuthMatrixTest()
