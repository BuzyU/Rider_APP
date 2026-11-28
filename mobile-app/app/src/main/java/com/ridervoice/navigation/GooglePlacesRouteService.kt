package com.ridervoice.navigation

import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.android.gms.maps.model.LatLng
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class PlaceSuggestion(
    val placeId: String,
    val primaryText: String,
    val secondaryText: String,
    val fullText: String,
    val lat: Double? = null,
    val lng: Double? = null
)

data class ResolvedPlace(
    val name: String,
    val lat: Double,
    val lng: Double
)

data class ComputedDirections(
    val encodedPolyline: String,
    val distanceMeters: Int,
    val durationMinutes: Int,
    val nextInstruction: String? = null
)

@Singleton
class GooglePlacesRouteService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    private val apiKey: String? by lazy { readManifestMapsApiKey(context) }
    private val androidCertSha1: String? by lazy { readAppSigningSha1Hex(context) }

    private fun googleRestRequest(url: String): Request {
        val builder = Request.Builder()
            .url(url)
            .header("X-Android-Package", context.packageName)
            .get()
        androidCertSha1?.let { sha1 ->
            builder.header("X-Android-Cert", sha1)
        }
        return builder.build()
    }

    private fun nominatimRequest(url: String): Request {
        return Request.Builder()
            .url(url)
            .header("User-Agent", "RiderVoice-Android/1.0 (${context.packageName})")
            .header("Accept", "application/json")
            .get()
            .build()
    }

    suspend fun autocompletePlaces(
        query: String,
        biasLat: Double? = null,
        biasLng: Double? = null
    ): List<PlaceSuggestion> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@withContext emptyList()

        val key = apiKey
        if (!key.isNullOrBlank()) {
            try {
                val encodedInput = URLEncoder.encode(trimmed, "UTF-8")
                val biasParam = if (biasLat != null && biasLng != null) {
                    "&location=$biasLat,$biasLng&radius=50000"
                } else {
                    ""
                }
                val url =
                    "https://maps.googleapis.com/maps/api/place/autocomplete/json?input=$encodedInput$biasParam&key=$key"
                httpClient.newCall(googleRestRequest(url)).execute().use { resp ->
                    val body = resp.body?.string()
                    if (resp.isSuccessful && !body.isNullOrBlank()) {
                        val parsed = parseAutocompleteResponse(body)
                        if (parsed.isNotEmpty()) return@withContext parsed
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Places autocomplete failed, falling back: ${e.message}")
            }
        }

        // Fallback 1: device Android Geocoder
        val geocoderResults = geocodeSuggestionsFallback(trimmed)
        if (geocoderResults.isNotEmpty()) return@withContext geocoderResults

        // Fallback 2: OpenStreetMap Nominatim search when Google Web REST returns REQUEST_DENIED on Android-restricted keys
        return@withContext fetchNominatimSuggestions(trimmed, limit = 5, biasLat = biasLat, biasLng = biasLng)
    }

    suspend fun resolveSuggestion(suggestion: PlaceSuggestion): ResolvedPlace? = withContext(Dispatchers.IO) {
        if (suggestion.lat != null && suggestion.lng != null) {
            return@withContext ResolvedPlace(
                name = suggestion.fullText.ifBlank { suggestion.primaryText },
                lat = suggestion.lat,
                lng = suggestion.lng
            )
        }

        val key = apiKey
        if (!key.isNullOrBlank() &&
            suggestion.placeId.isNotBlank() &&
            !suggestion.placeId.startsWith("geocoder:") &&
            !suggestion.placeId.startsWith("nominatim:")
        ) {
            try {
                val encodedPlaceId = URLEncoder.encode(suggestion.placeId, "UTF-8")
                val url =
                    "https://maps.googleapis.com/maps/api/place/details/json?place_id=$encodedPlaceId&fields=name,formatted_address,geometry&key=$key"
                httpClient.newCall(googleRestRequest(url)).execute().use { resp ->
                    val body = resp.body?.string()
                    if (resp.isSuccessful && !body.isNullOrBlank()) {
                        val parsed = parsePlaceDetailsResponse(body, fallbackName = suggestion.fullText)
                        if (parsed != null) return@withContext parsed
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Place details failed, falling back to geocodeName: ${e.message}")
            }
        }

        return@withContext geocodeName(suggestion.fullText.ifBlank { suggestion.primaryText })
    }

    suspend fun geocodeName(placeName: String): ResolvedPlace? = withContext(Dispatchers.IO) {
        val trimmed = placeName.trim()
        if (trimmed.isBlank()) return@withContext null

        val key = apiKey
        if (!key.isNullOrBlank()) {
            try {
                val encoded = URLEncoder.encode(trimmed, "UTF-8")
                val url = "https://maps.googleapis.com/maps/api/geocode/json?address=$encoded&key=$key"
                httpClient.newCall(googleRestRequest(url)).execute().use { resp ->
                    val body = resp.body?.string()
                    if (resp.isSuccessful && !body.isNullOrBlank()) {
                        val parsed = parseGeocodeResponse(body, fallbackName = trimmed)
                        if (parsed != null) return@withContext parsed
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Geocoding REST failed, trying Android Geocoder: ${e.message}")
            }
        }

        val fallbackList = geocodeSuggestionsFallback(trimmed, maxResults = 1)
            .ifEmpty { fetchNominatimSuggestions(trimmed, limit = 1) }
        val first = fallbackList.firstOrNull() ?: return@withContext null
        val lat = first.lat ?: return@withContext null
        val lng = first.lng ?: return@withContext null
        return@withContext ResolvedPlace(name = trimmed, lat = lat, lng = lng)
    }

    suspend fun reverseGeocode(lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
        try {
            if (Geocoder.isPresent()) {
                val addr = getFromLocationCompat(lat, lng)
                if (addr != null) return@withContext addr
            }
        } catch (e: Exception) {
            Log.w(TAG, "Android reverse geocoder failed: ${e.message}")
        }

        val key = apiKey
        if (!key.isNullOrBlank()) {
            try {
                val url = "https://maps.googleapis.com/maps/api/geocode/json?latlng=$lat,$lng&key=$key"
                httpClient.newCall(googleRestRequest(url)).execute().use { resp ->
                    val body = resp.body?.string()
                    if (resp.isSuccessful && !body.isNullOrBlank()) {
                        val parsed = parseReverseGeocodeResponse(body)
                        if (!parsed.isNullOrBlank()) return@withContext parsed
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "REST reverse geocode failed: ${e.message}")
            }
        }
        return@withContext String.format(Locale.US, "%.4f, %.4f", lat, lng)
    }

    suspend fun fetchRoute(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
        waypointLat: Double? = null,
        waypointLng: Double? = null
    ): ComputedDirections? = withContext(Dispatchers.IO) {
        val useWaypoint = waypointLat != null && waypointLng != null &&
            !isNearlySamePoint(originLat, originLng, waypointLat, waypointLng) &&
            !isNearlySamePoint(destLat, destLng, waypointLat, waypointLng)
        val key = apiKey
        if (!key.isNullOrBlank()) {
            try {
                val waypointParam = if (useWaypoint) "&waypoints=$waypointLat,$waypointLng" else ""
                val url =
                    "https://maps.googleapis.com/maps/api/directions/json?origin=$originLat,$originLng&destination=$destLat,$destLng$waypointParam&mode=driving&key=$key"
                httpClient.newCall(googleRestRequest(url)).execute().use { resp ->
                    val body = resp.body?.string()
                    if (resp.isSuccessful && !body.isNullOrBlank()) {
                        val parsed = parseDirectionsResponse(body)
                        if (parsed != null) return@withContext parsed
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Google Directions API failed, falling back to OSRM: ${e.message}")
            }
        }
        // Fallback: public OSRM driving router so real road polylines render even when Google Web Directions returns REQUEST_DENIED
        return@withContext fetchOsrmDirections(
            originLat = originLat,
            originLng = originLng,
            destLat = destLat,
            destLng = destLng,
            waypointLat = if (useWaypoint) waypointLat else null,
            waypointLng = if (useWaypoint) waypointLng else null
        )
    }

    private fun isNearlySamePoint(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Boolean {
        return kotlin.math.abs(lat1 - lat2) < 0.0005 && kotlin.math.abs(lng1 - lng2) < 0.0005
    }

    private fun fetchNominatimSuggestions(
        query: String,
        limit: Int = 5,
        biasLat: Double? = null,
        biasLng: Double? = null
    ): List<PlaceSuggestion> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val viewbox = if (biasLat != null && biasLng != null) {
                "&viewbox=${biasLng - 0.5},${biasLat + 0.5},${biasLng + 0.5},${biasLat - 0.5}"
            } else {
                ""
            }
            val url =
                "https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=1&limit=$limit&q=$encoded$viewbox"
            httpClient.newCall(nominatimRequest(url)).execute().use { resp ->
                val body = resp.body?.string()
                if (resp.isSuccessful && !body.isNullOrBlank()) {
                    parseNominatimResponse(body)
                } else {
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Nominatim search failed: ${e.message}")
            emptyList()
        }
    }

    private fun fetchOsrmDirections(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
        waypointLat: Double? = null,
        waypointLng: Double? = null
    ): ComputedDirections? {
        return try {
            val coords = if (waypointLat != null && waypointLng != null) {
                "$originLng,$originLat;$waypointLng,$waypointLat;$destLng,$destLat"
            } else {
                "$originLng,$originLat;$destLng,$destLat"
            }
            val url =
                "https://router.project-osrm.org/route/v1/driving/$coords?overview=full&geometries=polyline&steps=true"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "RiderVoice-Android/1.0 (${context.packageName})")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                if (resp.isSuccessful && !body.isNullOrBlank()) {
                    parseOsrmDirectionsResponse(body)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "OSRM directions fallback failed: ${e.message}")
            null
        }
    }

    private suspend fun geocodeSuggestionsFallback(
        query: String,
        maxResults: Int = 5
    ): List<PlaceSuggestion> {
        if (!Geocoder.isPresent()) return emptyList()
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine<List<android.location.Address>> { cont ->
                    geocoder.getFromLocationName(query, maxResults) { list ->
                        if (cont.isActive) cont.resume(list)
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocationName(query, maxResults) ?: emptyList()
            }
            addresses.mapIndexedNotNull { index, address ->
                if (!address.hasLatitude() || !address.hasLongitude()) return@mapIndexedNotNull null
                val full = (0..address.maxAddressLineIndex)
                    .mapNotNull { address.getAddressLine(it)?.takeIf { s -> s.isNotBlank() } }
                    .joinToString(", ")
                    .ifBlank { listOfNotNull(address.featureName, address.locality, address.adminArea).joinToString(", ") }
                    .ifBlank { query }
                val primary = address.featureName?.takeIf { it.isNotBlank() }
                    ?: address.locality?.takeIf { it.isNotBlank() }
                    ?: full.substringBefore(',')
                val secondary = full.removePrefix(primary).trimStart(',', ' ')
                PlaceSuggestion(
                    placeId = "geocoder:$index:${address.latitude},${address.longitude}",
                    primaryText = primary,
                    secondaryText = secondary,
                    fullText = full,
                    lat = address.latitude,
                    lng = address.longitude
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Geocoder fallback failed: ${e.message}")
            emptyList()
        }
    }

    private suspend fun getFromLocationCompat(lat: Double, lng: Double): String? {
        val geocoder = Geocoder(context, Locale.getDefault())
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine<List<android.location.Address>> { cont ->
                geocoder.getFromLocation(lat, lng, 1) { list ->
                    if (cont.isActive) cont.resume(list)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            geocoder.getFromLocation(lat, lng, 1) ?: emptyList()
        }
        val first = addresses.firstOrNull() ?: return null
        val compact = listOfNotNull(
            first.subLocality?.takeIf { it.isNotBlank() } ?: first.featureName?.takeIf { it.isNotBlank() },
            first.locality?.takeIf { it.isNotBlank() },
            first.adminArea?.takeIf { it.isNotBlank() }
        ).distinct().joinToString(", ")
        return compact.ifBlank { first.getAddressLine(0) }
    }

    companion object {
        private const val TAG = "GooglePlacesRoute"

        private fun readManifestMapsApiKey(context: Context): String? {
            return try {
                val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getApplicationInfo(
                        context.packageName,
                        PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                }
                appInfo.metaData?.getString("com.google.android.geo.API_KEY")?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }

        private fun readAppSigningSha1Hex(context: Context): String? {
            return try {
                val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val pkgInfo = context.packageManager.getPackageInfo(
                        context.packageName,
                        PackageManager.GET_SIGNING_CERTIFICATES
                    )
                    pkgInfo.signingInfo?.apkContentsSigners
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        PackageManager.GET_SIGNATURES
                    ).signatures
                }
                val certBytes = signatures?.firstOrNull()?.toByteArray() ?: return null
                val digest = MessageDigest.getInstance("SHA-1").digest(certBytes)
                digest.joinToString("") { byte -> "%02X".format(byte) }
            } catch (_: Exception) {
                null
            }
        }

        @VisibleForTesting
        fun parseNominatimResponse(json: String): List<PlaceSuggestion> {
            return try {
                val array = JSONArray(json)
                buildList {
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val lat = item.optString("lat").toDoubleOrNull() ?: continue
                        val lng = item.optString("lon").toDoubleOrNull() ?: continue
                        val displayName = item.optString("display_name").trim()
                        if (displayName.isBlank()) continue
                        val shortName = item.optString("name").trim()
                        val primary = shortName.ifBlank { displayName.substringBefore(',').trim() }
                        val secondary = displayName.removePrefix(primary).trimStart(',', ' ')
                        val osmId = item.optLong("place_id", i.toLong())
                        add(
                            PlaceSuggestion(
                                placeId = "nominatim:$osmId:$lat,$lng",
                                primaryText = primary,
                                secondaryText = secondary,
                                fullText = displayName,
                                lat = lat,
                                lng = lng
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        @VisibleForTesting
        fun parseOsrmDirectionsResponse(json: String): ComputedDirections? {
            return try {
                val root = JSONObject(json)
                if (!root.optString("code").equals("Ok", ignoreCase = true)) return null
                val route = root.optJSONArray("routes")?.optJSONObject(0) ?: return null
                val poly = route.optString("geometry").trim()
                if (poly.isBlank()) return null
                val distanceMeters = route.optDouble("distance", 0.0).toInt().coerceAtLeast(1)
                val durationSec = route.optDouble("duration", 0.0)
                val durationMinutes = ((durationSec + 59.0) / 60.0).toInt().coerceAtLeast(1)
                val nextStep = extractOsrmNextInstruction(route.optJSONArray("legs"))
                ComputedDirections(
                    encodedPolyline = poly,
                    distanceMeters = distanceMeters,
                    durationMinutes = durationMinutes,
                    nextInstruction = nextStep
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun extractOsrmNextInstruction(legs: JSONArray?): String? {
            val firstLeg = legs?.optJSONObject(0) ?: return null
            val steps = firstLeg.optJSONArray("steps") ?: return null
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                val maneuver = step.optJSONObject("maneuver") ?: continue
                val type = maneuver.optString("type").trim()
                if (type.equals("arrive", ignoreCase = true)) continue
                val modifier = maneuver.optString("modifier").trim()
                val roadName = step.optString("name").trim()
                val action = when (type) {
                    "depart" -> "Head"
                    "turn" -> if (modifier.isNotBlank()) "Turn $modifier" else "Turn"
                    "new name" -> "Continue"
                    "roundabout", "rotary" -> "Take roundabout"
                    "fork", "off ramp", "on ramp" -> if (modifier.isNotBlank()) "Keep $modifier" else "Take ramp"
                    else -> type.replace('_', ' ').replaceFirstChar { it.uppercase() }
                }
                val formatted = buildString {
                    append(action)
                    if (type == "depart" && modifier.isNotBlank()) {
                        append(' ')
                        append(modifier)
                    }
                    if (roadName.isNotBlank()) {
                        append(" on ")
                        append(roadName)
                    }
                }.trim()
                if (formatted.isNotBlank()) return formatted
            }
            return null
        }

        @VisibleForTesting
        fun parseAutocompleteResponse(json: String): List<PlaceSuggestion> {
            return try {
                val root = JSONObject(json)
                if (root.optString("status") != "OK") return emptyList()
                val predictions = root.optJSONArray("predictions") ?: return emptyList()
                buildList {
                    for (i in 0 until predictions.length()) {
                        val item = predictions.optJSONObject(i) ?: continue
                        val placeId = item.optString("place_id")
                        val description = item.optString("description")
                        if (placeId.isBlank() || description.isBlank()) continue
                        val formatting = item.optJSONObject("structured_formatting")
                        val mainText = formatting?.optString("main_text")?.takeIf { it.isNotBlank() }
                            ?: description.substringBefore(',').trim()
                        val secondaryText = formatting?.optString("secondary_text")?.takeIf { it.isNotBlank() }
                            ?: description.substringAfter(',', "").trim()
                        add(
                            PlaceSuggestion(
                                placeId = placeId,
                                primaryText = mainText,
                                secondaryText = secondaryText,
                                fullText = description
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        @VisibleForTesting
        fun parsePlaceDetailsResponse(json: String, fallbackName: String): ResolvedPlace? {
            return try {
                val root = JSONObject(json)
                if (root.optString("status") != "OK") return null
                val result = root.optJSONObject("result") ?: return null
                val loc = result.optJSONObject("geometry")?.optJSONObject("location") ?: return null
                val lat = loc.optDouble("lat", Double.NaN)
                val lng = loc.optDouble("lng", Double.NaN)
                if (lat.isNaN() || lng.isNaN()) return null
                val name = result.optString("name").takeIf { it.isNotBlank() }
                    ?: result.optString("formatted_address").takeIf { it.isNotBlank() }
                    ?: fallbackName
                ResolvedPlace(name = fallbackName.ifBlank { name }, lat = lat, lng = lng)
            } catch (_: Exception) {
                null
            }
        }

        @VisibleForTesting
        fun parseGeocodeResponse(json: String, fallbackName: String): ResolvedPlace? {
            return try {
                val root = JSONObject(json)
                if (root.optString("status") != "OK") return null
                val first = root.optJSONArray("results")?.optJSONObject(0) ?: return null
                val loc = first.optJSONObject("geometry")?.optJSONObject("location") ?: return null
                val lat = loc.optDouble("lat", Double.NaN)
                val lng = loc.optDouble("lng", Double.NaN)
                if (lat.isNaN() || lng.isNaN()) return null
                val formatted = first.optString("formatted_address").takeIf { it.isNotBlank() } ?: fallbackName
                ResolvedPlace(name = fallbackName.ifBlank { formatted }, lat = lat, lng = lng)
            } catch (_: Exception) {
                null
            }
        }

        @VisibleForTesting
        fun parseReverseGeocodeResponse(json: String): String? {
            return try {
                val root = JSONObject(json)
                if (root.optString("status") != "OK") return null
                val first = root.optJSONArray("results")?.optJSONObject(0) ?: return null
                first.optString("formatted_address").takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }

        @VisibleForTesting
        fun parseDirectionsResponse(json: String): ComputedDirections? {
            return try {
                val root = JSONObject(json)
                if (root.optString("status") != "OK") return null
                val route = root.optJSONArray("routes")?.optJSONObject(0) ?: return null
                val poly = route.optJSONObject("overview_polyline")?.optString("points").orEmpty()
                if (poly.isBlank()) return null
                val legs = route.optJSONArray("legs")
                var totalDist = 0
                var totalSec = 0
                var firstInstruction: String? = null
                if (legs != null) {
                    for (i in 0 until legs.length()) {
                        val leg = legs.optJSONObject(i) ?: continue
                        totalDist += leg.optJSONObject("distance")?.optInt("value", 0) ?: 0
                        totalSec += leg.optJSONObject("duration")?.optInt("value", 0) ?: 0
                        if (firstInstruction == null) {
                            val rawHtml = leg.optJSONArray("steps")?.optJSONObject(0)?.optString("html_instructions").orEmpty()
                            val cleanText = rawHtml.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
                            if (cleanText.isNotBlank()) firstInstruction = cleanText
                        }
                    }
                }
                ComputedDirections(
                    encodedPolyline = poly,
                    distanceMeters = totalDist,
                    durationMinutes = ((totalSec + 59) / 60).coerceAtLeast(1),
                    nextInstruction = firstInstruction
                )
            } catch (_: Exception) {
                null
            }
        }

        /** Decodes a Google Maps encoded polyline string into LatLng points. */
        fun decodePolyline(encoded: String): List<LatLng> {
            if (encoded.isBlank()) return emptyList()
            val poly = ArrayList<LatLng>()
            var index = 0
            val len = encoded.length
            var lat = 0
            var lng = 0

            while (index < len) {
                var b: Int
                var shift = 0
                var result = 0
                do {
                    if (index >= len) return poly
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20)
                val dLat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
                lat += dLat

                shift = 0
                result = 0
                do {
                    if (index >= len) return poly
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20)
                val dLng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
                lng += dLng

                poly.add(LatLng(lat / 1E5, lng / 1E5))
            }
            return poly
        }
    }
}
