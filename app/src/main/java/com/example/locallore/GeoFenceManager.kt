package com.example.locallore

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

object GeofenceManager {

    private const val BOUNDARY_GEOFENCE_ID = "BOUNDARY_GEOFENCE"
    private const val BOUNDARY_RADIUS_METERS = 15000f // 15km boundary
    private const val MIN_RADIUS_METERS = 250f // Increased further for better emulator detection
    private const val MAX_RADIUS_METERS = 600f
    private const val DEFAULT_RADIUS_METERS = 300f

    private fun getGeofencingClient(context: Context): GeofencingClient {
        return LocationServices.getGeofencingClient(context)
    }

    private fun getPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    private fun calculateRadius(place: NearbyPlace): Float {
        val neLat = place.viewportNortheastLat
        val neLng = place.viewportNortheastLng
        val swLat = place.viewportSouthwestLat
        val swLng = place.viewportSouthwestLng

        if (neLat == null || neLng == null || swLat == null || swLng == null) {
            return DEFAULT_RADIUS_METERS
        }

        val distanceKm = haversineDistance(neLat, neLng, swLat, swLng)
        val radiusMeters = (distanceKm * 1000 / 2).toFloat()
        return max(MIN_RADIUS_METERS, min(MAX_RADIUS_METERS, radiusMeters))
    }

    private fun calculateRadius(place: EnrichedPlace): Float {
        val neLat = place.viewportNortheastLat
        val neLng = place.viewportNortheastLng
        val swLat = place.viewportSouthwestLat
        val swLng = place.viewportSouthwestLng

        if (neLat == null || neLng == null || swLat == null || swLng == null) {
            return DEFAULT_RADIUS_METERS
        }

        val distanceKm = haversineDistance(neLat, neLng, swLat, swLng)
        val radiusMeters = (distanceKm * 1000 / 2).toFloat()
        return max(MIN_RADIUS_METERS, min(MAX_RADIUS_METERS, radiusMeters))
    }

    private fun haversineDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }

    suspend fun registerAll(
        context: Context,
        result: EnrichmentResult,
        currentLat: Double,
        currentLng: Double
    ) = withContext(Dispatchers.IO) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasBackgroundPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission || !hasBackgroundPermission) {
            Log.e("GeofenceManager", "Missing permissions: Fine=$hasPermission, Background=$hasBackgroundPermission")
            return@withContext
        }

        val geofenceList = mutableListOf<Pair<Geofence, Double>>()

        // Add enriched
        result.enriched.forEach { place ->
            val radius = calculateRadius(place)
            val dist = haversineDistance(currentLat, currentLng, place.lat, place.lng)
            val g = Geofence.Builder()
                .setRequestId(place.placeId)
                .setCircularRegion(place.lat, place.lng, radius)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_DWELL)
                .setLoiteringDelay(5000) // 5 seconds
                .build()
            geofenceList.add(g to dist)
        }

        // Add unenriched
        result.unenriched.forEach { place ->
            val radius = calculateRadius(place)
            val dist = haversineDistance(currentLat, currentLng, place.lat, place.lng)
            val g = Geofence.Builder()
                .setRequestId(place.placeId)
                .setCircularRegion(place.lat, place.lng, radius)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_DWELL)
                .setLoiteringDelay(5000) // 5 seconds
                .build()
            geofenceList.add(g to dist)
        }

        // Take 20 closest + 1 boundary
        val finalGeofences = geofenceList
            .sortedBy { it.second }
            .take(20)
            .map { it.first }
            .toMutableList()

        finalGeofences.add(
            Geofence.Builder()
                .setRequestId(BOUNDARY_GEOFENCE_ID)
                .setCircularRegion(currentLat, currentLng, BOUNDARY_RADIUS_METERS)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                .build()
        )

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_DWELL)
            .addGeofences(finalGeofences)
            .build()

        try {
            getGeofencingClient(context).addGeofences(request, getPendingIntent(context))
                .addOnSuccessListener {
                    Log.d("GeofenceManager", "Registered ${finalGeofences.size} geofences successfully.")
                }
                .addOnFailureListener { e ->
                    Log.e("GeofenceManager", "Failed to register geofences", e)
                }
        } catch (e: SecurityException) {
            Log.e("GeofenceManager", "SecurityException during registration", e)
        }
    }

    fun removeAll(context: Context) {
        getGeofencingClient(context).removeGeofences(getPendingIntent(context))
            .addOnSuccessListener { Log.d("GeofenceManager", "All geofences removed") }
            .addOnFailureListener { e -> Log.e("GeofenceManager", "Failed to remove geofences", e) }
    }
}
