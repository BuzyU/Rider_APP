if (process.env.NODE_ENV === 'production') {
    console.error('[FATAL] Seed scripts must never run in production.')
    process.exit(1)
}

const prisma = require('../src/db');

async function seed(convoyName, rider1, rider2) {
    const room = await prisma.room.findFirst({ where: { name: convoyName } });
    if (!room) {
        throw new Error(`Room ${convoyName} not found`);
    }

    await prisma.user.upsert({
        where: { id: rider2 },
        update: {},
        create: {
            id: rider2,
            displayName: 'Rider Bravo',
            handle: 'rider_bravo',
            email: 'bravo@test.com'
        }
    });

    await prisma.rideInvite.upsert({
        where: { id: `test_invite_${rider2}` },
        update: { status: 'ACCEPTED' },
        create: {
            id: `test_invite_${rider2}`,
            roomId: room.id,
            inviterId: rider1,
            inviteeId: rider2,
            status: 'ACCEPTED'
        }
    });

    console.log(`[Seed] Successfully authorized ${rider2} for convoy ${convoyName}`);
}

const convoy = process.argv[2] || 'Alpha-Convoy-Test';
const r1 = process.argv[3] || 'test_rider_alpha';
const r2 = process.argv[4] || 'test_rider_bravo';

seed(convoy, r1, r2)
    .then(() => prisma.$disconnect())
    .catch(err => {
        console.error(err);
        prisma.$disconnect();
        process.exit(1);
    });
