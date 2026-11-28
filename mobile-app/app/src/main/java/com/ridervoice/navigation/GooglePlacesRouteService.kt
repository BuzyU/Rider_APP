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
import org.json.JSONObject
import java.net.URLEncoder
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
    val durationMinutes: Int
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
                val request = Request.Builder().url(url).get().build()
                httpClient.newCall(request).execute().use { resp ->
                    val body = resp.body?.string()
                    if (resp.isSuccessful && !body.isNullOrBlank()) {
                        val parsed = parseAutocompleteResponse(body)
                        if (parsed.isNotEmpty()) return@withContext parsed
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Places autocomplete failed, falling back to Geocoder: ${e.message}")
            }
        }

        // Graceful device Geocoder fallback when Places Web API is unreachable or disabled on key
        return@withContext geocodeSuggestionsFallback(trimmed)
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
        if (!key.isNullOrBlank() && suggestion.placeId.isNotBlank() && !suggestion.placeId.startsWith("geocoder:")) {
            try {
                val encodedPlaceId = URLEncoder.encode(suggestion.placeId, "UTF-8")
                val url =
                    "https://maps.googleapis.com/maps/api/place/details/json?place_id=$encodedPlaceId&fields=name,formatted_address,geometry&key=$key"
                val request = Request.Builder().url(url).get().build()
                httpClient.newCall(request).execute().use { resp ->
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
                val request = Request.Builder().url(url).get().build()
                httpClient.newCall(request).execute().use { resp ->
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
                val request = Request.Builder().url(url).get().build()
                httpClient.newCall(request).execute().use { resp ->
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
        destLng: Double
    ): ComputedDirections? = withContext(Dispatchers.IO) {
        val key = apiKey
        if (key.isNullOrBlank()) return@withContext null
        try {
            val url =
                "https://maps.googleapis.com/maps/api/directions/json?origin=$originLat,$originLng&destination=$destLat,$destLng&mode=driving&key=$key"
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (resp.isSuccessful && !body.isNullOrBlank()) {
                    return@withContext parseDirectionsResponse(body)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Directions API failed: ${e.message}")
        }
        return@withContext null
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
                if (legs != null) {
                    for (i in 0 until legs.length()) {
                        val leg = legs.optJSONObject(i) ?: continue
                        totalDist += leg.optJSONObject("distance")?.optInt("value", 0) ?: 0
                        totalSec += leg.optJSONObject("duration")?.optInt("value", 0) ?: 0
                    }
                }
                ComputedDirections(
                    encodedPolyline = poly,
                    distanceMeters = totalDist,
                    durationMinutes = ((totalSec + 59) / 60).coerceAtLeast(1)
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
