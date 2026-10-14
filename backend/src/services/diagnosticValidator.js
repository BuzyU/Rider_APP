/**
 * Validates diagnostic batch payloads against contract specifications.
 * Enforces types, finite numbers, plausible ranges, string lengths, and max rows.
 */

const MAX_BATCH_ROWS = 200
const VALID_NETWORK_TYPES = new Set(['WIFI', 'CELLULAR', 'ETHERNET', 'VPN', 'UNKNOWN'])
const VALID_THERMAL_STATUSES = new Set(['NONE', 'LIGHT', 'MODERATE', 'SEVERE', 'CRITICAL', 'EMERGENCY', 'SHUTDOWN'])

function isFiniteNumber(val) {
    return typeof val === 'number' && Number.isFinite(val)
}

function validateBatchPayload(payload) {
    if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
        throw createValidationError('Payload must be a JSON object')
    }

    const selfMetrics = Array.isArray(payload.selfMetrics) ? payload.selfMetrics : []
    const inboundMetrics = Array.isArray(payload.inboundMetrics) ? payload.inboundMetrics : []
    const events = Array.isArray(payload.events) ? payload.events : []

    const totalRows = selfMetrics.length + inboundMetrics.length + events.length
    if (totalRows > MAX_BATCH_ROWS) {
        throw createValidationError(`Batch row count (${totalRows}) exceeds the maximum limit of ${MAX_BATCH_ROWS} samples`)
    }

    // 1. Validate selfMetrics
    for (let i = 0; i < selfMetrics.length; i++) {
        const sm = selfMetrics[i]
        if (!sm || typeof sm !== 'object') {
            throw createValidationError(`selfMetrics[${i}] must be an object`)
        }

        if (!isFiniteNumber(sm.timestamp) || sm.timestamp <= 0) {
            throw createValidationError(`selfMetrics[${i}].timestamp must be a positive finite number`)
        }

        if (sm.rtt_ms !== undefined && sm.rtt_ms !== null) {
            if (!isFiniteNumber(sm.rtt_ms) || sm.rtt_ms < 0 || sm.rtt_ms > 30000) {
                throw createValidationError(`selfMetrics[${i}].rtt_ms must be a finite number between 0 and 30000 ms, or null`)
            }
        }

        for (const numField of ['packets_sent', 'packets_received', 'packets_lost', 'bytes_sent', 'bytes_received']) {
            if (sm[numField] !== undefined && sm[numField] !== null) {
                if (!isFiniteNumber(sm[numField]) || sm[numField] < 0) {
                    throw createValidationError(`selfMetrics[${i}].${numField} must be a non-negative finite number`)
                }
            }
        }

        if (sm.network_type !== undefined && sm.network_type !== null) {
            if (typeof sm.network_type !== 'string' || sm.network_type.length > 32) {
                throw createValidationError(`selfMetrics[${i}].network_type must be a string up to 32 chars`)
            }
        }

        if (sm.cell_operator !== undefined && sm.cell_operator !== null) {
            if (typeof sm.cell_operator !== 'string' || sm.cell_operator.length > 64) {
                throw createValidationError(`selfMetrics[${i}].cell_operator must be a string up to 64 chars`)
            }
        }

        if (sm.cell_tech !== undefined && sm.cell_tech !== null) {
            if (typeof sm.cell_tech !== 'string' || sm.cell_tech.length > 32) {
                throw createValidationError(`selfMetrics[${i}].cell_tech must be a string up to 32 chars`)
            }
        }

        if (sm.thermal_status !== undefined && sm.thermal_status !== null) {
            if (typeof sm.thermal_status !== 'string' || sm.thermal_status.length > 32) {
                throw createValidationError(`selfMetrics[${i}].thermal_status must be a string up to 32 chars`)
            }
        }
    }

    // 2. Validate inboundMetrics
    for (let i = 0; i < inboundMetrics.length; i++) {
        const im = inboundMetrics[i]
        if (!im || typeof im !== 'object') {
            throw createValidationError(`inboundMetrics[${i}] must be an object`)
        }

        if (!isFiniteNumber(im.timestamp) || im.timestamp <= 0) {
            throw createValidationError(`inboundMetrics[${i}].timestamp must be a positive finite number`)
        }

        if (!im.remote_rider || typeof im.remote_rider !== 'string' || im.remote_rider.trim().length === 0 || im.remote_rider.length > 128) {
            throw createValidationError(`inboundMetrics[${i}].remote_rider must be a non-empty string up to 128 chars`)
        }

        if (im.track_sid !== undefined && im.track_sid !== null) {
            if (typeof im.track_sid !== 'string' || im.track_sid.length > 64) {
                throw createValidationError(`inboundMetrics[${i}].track_sid must be a string up to 64 chars`)
            }
        }

        for (const numField of ['packets_received', 'packets_lost', 'bytes_received']) {
            if (im[numField] !== undefined && im[numField] !== null) {
                if (!isFiniteNumber(im[numField])) {
                    throw createValidationError(`inboundMetrics[${i}].${numField} must be a finite number`)
                }
            }
        }

        if (im.jitter_ms !== undefined && im.jitter_ms !== null) {
            if (!isFiniteNumber(im.jitter_ms) || im.jitter_ms < 0) {
                throw createValidationError(`inboundMetrics[${i}].jitter_ms must be a non-negative finite number`)
            }
        }
    }

    // 3. Validate events
    for (let i = 0; i < events.length; i++) {
        const ev = events[i]
        if (!ev || typeof ev !== 'object') {
            throw createValidationError(`events[${i}] must be an object`)
        }

        if (!isFiniteNumber(ev.timestamp) || ev.timestamp <= 0) {
            throw createValidationError(`events[${i}].timestamp must be a positive finite number`)
        }

        if (!ev.event_type || typeof ev.event_type !== 'string' || ev.event_type.length > 64) {
            throw createValidationError(`events[${i}].event_type must be a non-empty string up to 64 chars`)
        }

        if (ev.details !== undefined && ev.details !== null) {
            if (typeof ev.details !== 'string' || ev.details.length > 256) {
                throw createValidationError(`events[${i}].details must be a string up to 256 chars`)
            }
        }
    }

    return true
}

function createValidationError(message) {
    const error = new Error(`Validation Error: ${message}`)
    error.status = 400
    return error
}

module.exports = {
    MAX_BATCH_ROWS,
    validateBatchPayload
}
