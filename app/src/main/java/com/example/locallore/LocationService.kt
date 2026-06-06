package com.example.locallore

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

data class NearbyPlace(
    val name: String,
    val placeId: String,
    val lat: Double,
    val lng: Double,
    val rating: Double,
    val userRatingsTotal: Int,
    val vicinity: String,
    val openNow: Boolean?,
    val photoReference: String?,
    val businessStatus: String,
    val viewportNortheastLat: Double?,
    val viewportNortheastLng: Double?,
    val viewportSouthwestLat: Double?,
    val viewportSouthwestLng: Double?
)

object LocationService {

    suspend fun getCityName(lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
        try {
            val url = "https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lng&format=json"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "LocalLore/1.0 (Android app; educational project)")
                .build()
            val response = OkHttpClient().newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null

            val json = JSONObject(body)
            val address = json.getJSONObject("address")

            // Try city first, then town, then state as fallback
            val city = address.optString("city").ifBlank { null }
                ?: address.optString("town").ifBlank { null }
                ?: address.optString("state").ifBlank { null }

            Log.d("LocationService", "Detected city: $city")
            city

        } catch (e: Exception) {
            Log.e("LocationService", "Reverse geocode failed", e)
            null
        }
    }
    suspend fun getCurrentLocation(context: Context): Pair<Double, Double>? {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Log.e("LocationService", "Location permission not granted")
            return null
        }

        val client = LocationServices.getFusedLocationProviderClient(context)

        return suspendCancellableCoroutine { cont ->
            // Try getting a fresh current location instead of just the last known one
            val cts = CancellationTokenSource()
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        Log.d("LocationService", "Got location: ${location.latitude}, ${location.longitude}")
                        if (cont.isActive) cont.resume(Pair(location.latitude, location.longitude))
                    } else {
                        Log.e("LocationService", "Current location was null. Check emulator settings.")
                        if (cont.isActive) cont.resume(null)
                    }
                }
                .addOnFailureListener {
                    Log.e("LocationService", "Failed to get location", it)
                    if (cont.isActive) cont.resume(null)
                }

            cont.invokeOnCancellation {
                cts.cancel()
            }
        }
    }

    suspend fun getNearbyAttractions(
        lat: Double,
        lng: Double,
        apiKey: String
    ): List<NearbyPlace> = withContext(Dispatchers.IO) {
        val allPlaces = mutableListOf<NearbyPlace>()
        var nextPageToken: String? = null

        do {
            val url = StringBuilder("https://maps.googleapis.com/maps/api/place/nearbysearch/json" +
                    "?location=$lat,$lng" +
                    "&radius=20000" +
                    "&type=tourist_attraction" +
                    "&key=$apiKey")

            if (nextPageToken != null) {
                url.append("&pagetoken=$nextPageToken")
            }

            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(url.toString()).build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: break
                val json = JSONObject(body)
                val results = json.getJSONArray("results")
                val blacklistKeywords = listOf(
                    "service", "services", "hotel", "hotels", "tour", "tours", "travels",
                    "travel", "agency", "cab", "taxi", "cargo", "lodge",
                    "lodging", "resort", "inn", "hostel", "motel", "guesthouse", "guest house",
                    "restaurant", "cafe", "dhaba", "caterers",
                    "hospital", "clinic", "pharmacy", "school", "college", "academy", "coaching", "classes", "shop", "store",
                    "showroom", "dealership", "garage", "workshop", "salon", "spa",
                    "gym", "fitness", "bank", "atm", "insurance", "finance", "realty",
                    "real estate", "builders", "construction", "packers", "movers", "holidays",
                    "voyages", "driver", "drivers", "Estate", "Office", "Motors", "Motor"
                )

                for (i in 0 until results.length()) {
                    val place = results.getJSONObject(i)
                    val location = place.getJSONObject("geometry").getJSONObject("location")
                    val name = place.getString("name")
                    val viewport = place.getJSONObject("geometry").optJSONObject("viewport")
                    val northeastLat = viewport?.getJSONObject("northeast")?.optDouble("lat")
                    val northeastLng = viewport?.getJSONObject("northeast")?.optDouble("lng")
                    val southwestLat = viewport?.getJSONObject("southwest")?.optDouble("lat")
                    val southwestLng = viewport?.getJSONObject("southwest")?.optDouble("lng")

                    // Skip permanently closed places
                    val businessStatus = place.optString("business_status", "OPERATIONAL")
                    if (businessStatus == "CLOSED_PERMANENTLY") {
                        Log.d("LocationService", "Skipping permanently closed: $name")
                        continue
                    }

                    // Keyword filter
                    val nameLower = name.lowercase()
                    val isBlacklisted = blacklistKeywords.any { keyword ->
                        val regex = Regex("\\b${Regex.escape(keyword)}\\b", RegexOption.IGNORE_CASE)
                        val matches = regex.containsMatchIn(nameLower)
                        if (matches) Log.d("LocationService", "Filtered '$name' because of keyword: '$keyword'")
                        matches
                    }
                    if (isBlacklisted) continue

                    val openNow = if (place.has("opening_hours"))
                        place.getJSONObject("opening_hours").optBoolean("open_now", false)
                    else null

                    val photoReference = if (place.has("photos"))
                        place.getJSONArray("photos").getJSONObject(0).optString("photo_reference")
                    else null

                    allPlaces.add(
                        NearbyPlace(
                            name = name,
                            placeId = place.getString("place_id"),
                            lat = location.getDouble("lat"),
                            lng = location.getDouble("lng"),
                            rating = place.optDouble("rating", 0.0),
                            userRatingsTotal = place.optInt("user_ratings_total", 0),
                            vicinity = place.optString("vicinity", ""),
                            openNow = openNow,
                            photoReference = photoReference,
                            businessStatus = businessStatus,
                            viewportNortheastLat = northeastLat,
                            viewportNortheastLng = northeastLng,
                            viewportSouthwestLat = southwestLat,
                            viewportSouthwestLng = southwestLng
                        )
                    )
                }

                nextPageToken = if (json.has("next_page_token"))
                    json.getString("next_page_token") else null

                if (nextPageToken != null) {
                    kotlinx.coroutines.delay(2000)
                }

            } catch (e: Exception) {
                Log.e("LocationService", "Places API call failed", e)
                break
            }

        } while (nextPageToken != null)

        Log.d("LocationService", "Found ${allPlaces.size} attractions total")
        allPlaces
    }

    fun savePlacesToJson(context: Context, newPlaces: List<NearbyPlace>, lat: Double, lng: Double, cityName: String? = null) {
        try {
            // Load existing places to merge
            val existingPlaces = loadPlacesFromJson(context, lat, lng) ?: emptyList()
            val existingPlaceIds = existingPlaces.map { it.placeId }.toSet()
            
            // Keep existing ones, add new ones that aren't already there
            val mergedPlaces = existingPlaces.toMutableList()
            newPlaces.forEach { if (it.placeId !in existingPlaceIds) mergedPlaces.add(it) }

            val jsonArray = JSONArray()
            for (place in mergedPlaces) {
                val jsonObject = JSONObject()
                jsonObject.put("name", place.name)
                jsonObject.put("placeId", place.placeId)
                jsonObject.put("lat", place.lat)
                jsonObject.put("lng", place.lng)
                jsonObject.put("rating", place.rating)
                jsonObject.put("userRatingsTotal", place.userRatingsTotal)
                jsonObject.put("vicinity", place.vicinity)
                jsonObject.put("openNow", place.openNow)
                jsonObject.put("photoReference", place.photoReference ?: "")
                jsonObject.put("businessStatus", place.businessStatus)
                jsonObject.put("viewportNELat", place.viewportNortheastLat ?: "")
                jsonObject.put("viewportNELng", place.viewportNortheastLng ?: "")
                jsonObject.put("viewportSWLat", place.viewportSouthwestLat ?: "")
                jsonObject.put("viewportSWLng", place.viewportSouthwestLng ?: "")
                jsonArray.put(jsonObject)
            }

            val wrapper = JSONObject()
            wrapper.put("timestamp", System.currentTimeMillis())
            wrapper.put("fetchedLat", lat)
            wrapper.put("fetchedLng", lng)
            wrapper.put("places", jsonArray)
            wrapper.put("cityName", cityName ?: "")

            val file = File(context.filesDir, "nearby_attractions.json")
            file.writeText(wrapper.toString(4))
            Log.d("LocationService", "Saved ${mergedPlaces.size} total places to cache (merged ${newPlaces.size} new)")
        } catch (e: Exception) {
            Log.e("LocationService", "Failed to save places to JSON", e)
        }
    }

    fun clearAllCaches(context: Context) {
        val filesToDelete = listOf(
            "nearby_attractions.json",
            "enriched_places.json",
            "wikipedia_progress.json"
        )
        filesToDelete.forEach { fileName ->
            val file = File(context.filesDir, fileName)
            if (file.exists()) {
                if (file.delete()) {
                    Log.d("LocationService", "Deleted cache file: $fileName")
                }
            }
        }
        // Also remove active geofences from the system so they don't persist
        GeofenceManager.removeAll(context)
    }

    fun loadPlacesFromJson(context: Context, currentLat: Double, currentLng: Double): List<NearbyPlace>? {
        return try {
            val file = File(context.filesDir, "nearby_attractions.json")
            if (!file.exists()) return null

            val wrapper = JSONObject(file.readText())
            val timestamp = wrapper.getLong("timestamp")
            val fetchedLat = wrapper.getDouble("fetchedLat")
            val fetchedLng = wrapper.getDouble("fetchedLng")

            val ageMinutes = (System.currentTimeMillis() - timestamp) / 60000
            val distanceKm = haversineDistance(currentLat, currentLng, fetchedLat, fetchedLng)

            if (ageMinutes > 45) {
                Log.d("LocationService", "Cache expired: ${ageMinutes} mins old. Clearing all.")
                clearAllCaches(context)
                return null
            }

            if (distanceKm > 15) {
                Log.d("LocationService", "Cache expired: moved ${distanceKm}km. Clearing all.")
                clearAllCaches(context)
                return null
            }

            val jsonArray = wrapper.getJSONArray("places")
            val places = mutableListOf<NearbyPlace>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                places.add(
                    NearbyPlace(
                        name = obj.getString("name"),
                        placeId = obj.getString("placeId"),
                        lat = obj.getDouble("lat"),
                        lng = obj.getDouble("lng"),
                        rating = obj.optDouble("rating", 0.0),
                        userRatingsTotal = obj.optInt("userRatingsTotal", 0),
                        vicinity = obj.optString("vicinity", ""),
                        openNow = if (obj.isNull("openNow")) null else obj.optBoolean("openNow"),
                        photoReference = obj.optString("photoReference").ifBlank { null },
                        businessStatus = obj.optString("businessStatus", "OPERATIONAL") ,
                        viewportNortheastLat = if (obj.isNull("viewportNELat")) null else obj.optDouble("viewportNELat"),
                        viewportNortheastLng = if (obj.isNull("viewportNELng")) null else obj.optDouble("viewportNELng"),
                        viewportSouthwestLat = if (obj.isNull("viewportSWLat")) null else obj.optDouble("viewportSWLat"),
                        viewportSouthwestLng = if (obj.isNull("viewportSWLng")) null else obj.optDouble("viewportSWLng")
                    )
                )
            }

            Log.d("LocationService", "Cache valid: ${ageMinutes} mins old, ${distanceKm}km away. Loaded ${places.size} places")
            places

        } catch (e: Exception) {
            Log.e("LocationService", "Failed to load places from cache", e)
            null
        }
    }

    // Calculates distance between two coordinates in km
    private fun haversineDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371.0 // Earth's radius in km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }
}