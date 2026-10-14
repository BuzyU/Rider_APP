if (process.env.NODE_ENV === 'production') {
    console.error('[FATAL] Embedded Postgres must never run in production.')
    process.exit(1)
}

const EmbeddedPostgresPkg = require('embedded-postgres');
const EmbeddedPostgres = EmbeddedPostgresPkg.default || EmbeddedPostgresPkg;
const path = require('path');

async function start() {
    const dataDir = path.join(__dirname, '../.pg_data');
    const pg = new EmbeddedPostgres({
        databaseDir: dataDir,
        user: 'postgres',
        password: 'password',
        port: 5432,
        persistent: true,
    });

    const fs = require('fs');
    if (!fs.existsSync(path.join(dataDir, 'PG_VERSION'))) {
        console.log('[PG] Initialising cluster...');
        await pg.initialise();
    } else {
        console.log('[PG] Existing cluster detected, skipping initdb.');
    }
    console.log('[PG] Starting Postgres server on port 5432...');
    await pg.start();
    console.log('[PG] Postgres server started on 5432.');

    try {
        await pg.createDatabase('ridervoice_test');
        console.log('[PG] Created database ridervoice_test');
    } catch (e) {
        console.log('[PG] Database ridervoice_test may already exist:', e.message);
    }

    // Keep running until SIGINT / SIGTERM or parent exit
    process.on('SIGINT', async () => {
        console.log('[PG] Stopping server...');
        await pg.stop();
        process.exit(0);
    });
    process.on('SIGTERM', async () => {
        await pg.stop();
        process.exit(0);
    });

    // Notify ready
    console.log('PG_READY');
}

start().catch(err => {
    console.error('[PG] Error:', err);
    process.exit(1);
});
