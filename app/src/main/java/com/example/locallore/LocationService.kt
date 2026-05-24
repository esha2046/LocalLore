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
import org.json.JSONObject
import kotlin.coroutines.resume

data class NearbyPlace(
    val name: String,
    val placeId: String,
    val lat: Double,
    val lng: Double
)

object LocationService {

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
        val url = "https://maps.googleapis.com/maps/api/place/nearbysearch/json" +
                "?location=$lat,$lng" +
                "&radius=30000" +
                "&type=tourist_attraction" +
                "&key=$apiKey"

        try {
            val client = OkHttpClient()
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext emptyList<NearbyPlace>()
            Log.d("LocationService", "Places API response: $body")
            val json = JSONObject(body)
            val results = json.getJSONArray("results")
            val places = mutableListOf<NearbyPlace>()

            for (i in 0 until results.length()) {
                val place = results.getJSONObject(i)
                val location = place.getJSONObject("geometry").getJSONObject("location")
                places.add(
                    NearbyPlace(
                        name = place.getString("name"),
                        placeId = place.getString("place_id"),
                        lat = location.getDouble("lat"),
                        lng = location.getDouble("lng")
                    )
                )
            }
            Log.d("LocationService", "Using API key: $apiKey")
            Log.d("LocationService", "Found ${places.size} attractions")
            places

        } catch (e: Exception) {
            Log.e("LocationService", "Places API call failed", e)
            emptyList<NearbyPlace>()
        }
    }
}