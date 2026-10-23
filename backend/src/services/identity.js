/**
 * identity.js - Unified rider callsign and identity helpers
 */

/**
 * Computes a human-readable, non-generic callsign for a rider.
 * Order: displayName (trimmed, 2-24 chars, not generic "Rider") -> @handle -> "Rider <last 4 of uid>"
 * Never returns "Rider" alone or "Guest Rider" when an id or handle is available.
 *
 * @param {object} user - User object with { id|uid, displayName, handle }
 * @returns {string} Resolved callsign
 */
function riderCallsign(user) {
    if (!user) return 'Rider'

    const dn = typeof user.displayName === 'string' ? user.displayName.trim() : ''
    if (dn.length >= 2 && dn.length <= 24 && dn !== 'Rider' && dn !== 'Guest Rider') {
        return dn
    }

    const handle = typeof user.handle === 'string' ? user.handle.trim() : ''
    if (handle.length >= 2) {
        return handle.startsWith('@') ? handle : `@${handle}`
    }

    const uid = typeof user.id === 'string' ? user.id : (typeof user.uid === 'string' ? user.uid : '')
    if (uid.length >= 4) {
        return `Rider ${uid.slice(-4)}`
    }

    return dn || (uid ? `Rider ${uid}` : 'Rider')
}

/**
 * Disambiguates duplicate callsigns in a single ride/room context.
 * Appends counter or handle if two members have the same callsign.
 *
 * @param {Array<object>} members - Array of objects with user or callsign
 * @returns {Array<object>} Same objects with unique callsign fields
 */
function disambiguateCallsigns(members) {
    if (!Array.isArray(members)) return []
    const seen = new Map()

    return members.map(m => {
        const base = m.callsign || riderCallsign(m.user || m.invitee || m)
        const count = (seen.get(base) || 0) + 1
        seen.set(base, count)

        const uniqueCallsign = count === 1 ? base : `${base} (${count})`
        return {
            ...m,
            callsign: uniqueCallsign
        }
    })
}

module.exports = {
    riderCallsign,
    disambiguateCallsigns
}
