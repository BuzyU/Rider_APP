package com.ridervoice.utils

import com.ridervoice.data.local.entities.RawWaypointEntity
import kotlin.math.*

object GeoUtils {

    fun distance(p1: RawWaypointEntity, p2: RawWaypointEntity): Double {
        val R = 6371e3
        val lat1 = p1.lat * PI / 180
        val lat2 = p2.lat * PI / 180
        val dLat = (p2.lat - p1.lat) * PI / 180
        val dLon = (p2.lng - p1.lng) * PI / 180

        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1) * cos(lat2) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    private fun perpendicularDistance(p: RawWaypointEntity, p1: RawWaypointEntity, p2: RawWaypointEntity): Double {
        if (p1.lat == p2.lat && p1.lng == p2.lng) {
            return distance(p, p1)
        }

        val a = distance(p1, p2)
        val b = distance(p1, p)
        val c = distance(p2, p)

        val s = (a + b + c) / 2.0
        val area = sqrt(max(0.0, s * (s - a) * (s - b) * (s - c)))

        return 2.0 * area / a
    }

    fun douglasPeucker(points: List<RawWaypointEntity>, epsilon: Double): List<RawWaypointEntity> {
        if (points.size < 3) return points

        var maxDistance = 0.0
        var index = 0

        val end = points.size - 1

        for (i in 1 until end) {
            val d = perpendicularDistance(points[i], points[0], points[end])
            if (d > maxDistance) {
                index = i
                maxDistance = d
            }
        }

        return if (maxDistance > epsilon) {
            val recResults1 = douglasPeucker(points.subList(0, index + 1), epsilon)
            val recResults2 = douglasPeucker(points.subList(index, points.size), epsilon)

            val result = mutableListOf<RawWaypointEntity>()
            result.addAll(recResults1.dropLast(1))
            result.addAll(recResults2)
            result
        } else {
            listOf(points[0], points[end])
        }
    }
}
