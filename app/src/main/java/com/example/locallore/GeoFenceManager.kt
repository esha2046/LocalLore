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
    private const val BOUNDARY_RADIUS_METERS = 3000f // 3km boundary
    private const val SCAN_RADIUS_METERS = 7000.0 // 7km scanning radius
    private const val MAX_GEOFENCES = 40 // Capping at 40 POIs
    private const val MIN_RADIUS_METERS = 350f 
    private const val MAX_RADIUS_METERS = 600f
    private const val DEFAULT_RADIUS_METERS = 400f

    private fun getGeofencingClient(context: Context): GeofencingClient {
        return LocationServices.getGeofencingClient(context)
    }

    private fun getPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    private fun calculateRadius(place: NearbyPlace): Float {
        val neLat = place.viewportNortheastLat
        val neLng = place.viewportNortheastLng
        val swLat = place.viewportSouthwestLat
        val swLng = place.viewportSouthwestLng
        if (neLat == null || neLng == null || swLat == null || swLng == null) return DEFAULT_RADIUS_METERS
        val distanceKm = haversineDistance(neLat, neLng, swLat, swLng)
        return max(MIN_RADIUS_METERS, min(MAX_RADIUS_METERS, (distanceKm * 1000 / 2).toFloat()))
    }

    private fun haversineDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    suspend fun registerAll(
        context: Context,
        places: List<NearbyPlace>,
        currentLat: Double,
        currentLng: Double
    ) = withContext(Dispatchers.IO) {
        val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasBgPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission || !hasBgPermission) {
            DebugLogger.log(context, "Permissions missing for geofencing")
            return@withContext
        }

        val geofenceList = mutableListOf<Pair<Geofence, Double>>()

        places.forEach { place ->
            val dist = haversineDistance(currentLat, currentLng, place.lat, place.lng)
            if (dist * 1000 <= SCAN_RADIUS_METERS) {
                val radius = calculateRadius(place)
                val g = Geofence.Builder()
                    .setRequestId(place.placeId)
                    .setCircularRegion(place.lat, place.lng, radius)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_DWELL)
                    .setLoiteringDelay(1000) // Fast dwell trigger for simulation
                    .build()
                geofenceList.add(g to dist)
            }
        }

        val sorted = geofenceList.sortedBy { it.second }.take(MAX_GEOFENCES)
        val toRegister = sorted.map { it.first }.toMutableList()

        toRegister.add(
            Geofence.Builder()
                .setRequestId(BOUNDARY_GEOFENCE_ID)
                .setCircularRegion(currentLat, currentLng, BOUNDARY_RADIUS_METERS)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                .build()
        )

        // Force clean removal before adding
        getGeofencingClient(context).removeGeofences(getPendingIntent(context)).addOnCompleteListener {
            if (toRegister.isEmpty()) return@addOnCompleteListener
            
            val request = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_DWELL)
                .addGeofences(toRegister)
                .build()
            
            try {
                getGeofencingClient(context).addGeofences(request, getPendingIntent(context))
                    .addOnSuccessListener {
                        DebugLogger.log(context, "Registered ${toRegister.size} POIs (3km boundary active)")
                        // Log names of the closest 5 registered POIs for user visibility
                        val names = sorted.take(5).joinToString(", ") { it.first.requestId }
                        DebugLogger.log(context, "Closest POIs: $names")
                    }
                    .addOnFailureListener {
                        DebugLogger.log(context, "Geofence Registration FAILED: ${it.message}")
                    }
            } catch (e: SecurityException) {
                DebugLogger.log(context, "Security error in GeofenceManager")
            }
        }
    }

    fun removeAll(context: Context) {
        getGeofencingClient(context).removeGeofences(getPendingIntent(context))
            .addOnSuccessListener { DebugLogger.log(context, "All geofences cleared") }
    }
}
