package com.example.locallore

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.Geofence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        const val CHANNEL_ID = "locallore_geofence_channel"
        const val EXTRA_PLACE_ID = "extra_place_id"
        const val EXTRA_IS_ENRICHED = "extra_is_enriched"
        private const val BOUNDARY_GEOFENCE_ID = "BOUNDARY_GEOFENCE"
        
        private var lastNotificationTime = 0L
        private const val NOTIFICATION_COOLDOWN_MS = 10000L // 10 seconds
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        scope.launch {
            try {
                handleEvent(context, intent)
            } catch (e: Exception) {
                DebugLogger.log(context, "Receiver Error: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleEvent(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent) ?: return

        if (geofencingEvent.hasError()) {
            val errorMessage = GeofenceStatusCodes.getStatusCodeString(geofencingEvent.errorCode)
            DebugLogger.log(context, "Geofencing Error: $errorMessage")
            return
        }

        val triggeringGeofences = geofencingEvent.triggeringGeofences ?: return
        val transition = geofencingEvent.geofenceTransition
        
        val type = when(transition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "ENTER"
            Geofence.GEOFENCE_TRANSITION_DWELL -> "DWELL"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "EXIT"
            else -> "UNKNOWN"
        }
        
        val ids = triggeringGeofences.joinToString(", ") { id ->
            val name = LocationService.loadAllPlacesFromCache(context)?.find { it.placeId == id.requestId }?.name ?: id.requestId
            name
        }
        DebugLogger.log(context, "$type event: $ids")

        // 1. Handle Boundary Logic first
        if (triggeringGeofences.any { it.requestId == BOUNDARY_GEOFENCE_ID }) {
            DebugLogger.log(context, "Boundary triggered. Refreshing...")
            handleBoundaryExit(context)
        }

        // 2. Filter out exits for POIs
        if (transition == Geofence.GEOFENCE_TRANSITION_EXIT) {
            triggeringGeofences.filter { it.requestId != BOUNDARY_GEOFENCE_ID }
                .forEach { DebugLogger.log(context, "Left POI: ${it.requestId}") }
            return
        }

        // 3. Apply Throttling
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastNotificationTime < NOTIFICATION_COOLDOWN_MS) {
            DebugLogger.log(context, "Throttling notification.")
            return
        }

        // 4. Notify for POIs
        val poi = triggeringGeofences.find { it.requestId != BOUNDARY_GEOFENCE_ID } ?: return
        val id = poi.requestId
        
        val wikiCache = WikipediaService.loadFromCache(context)
        val enrichedPlace = wikiCache?.enriched?.find { it.placeId == id }
        
        if (enrichedPlace != null) {
            lastNotificationTime = currentTime
            DebugLogger.log(context, "Notify: ${enrichedPlace.name} (Wiki)")
            showNotification(context, enrichedPlace)
            return
        }

        val rawPlaces = LocationService.loadAllPlacesFromCache(context)
        val unenrichedPlace = rawPlaces?.find { it.placeId == id }
        if (unenrichedPlace != null) {
            lastNotificationTime = currentTime
            DebugLogger.log(context, "Notify: ${unenrichedPlace.name} (Raw)")
            showNotification(context, unenrichedPlace)
            return
        }

        DebugLogger.log(context, "No data for ID: $id")
    }

    private suspend fun handleBoundaryExit(context: Context) {
        val location = LocationService.getCurrentLocation(context) ?: return
        val (currentLat, currentLng) = location
        
        val file = File(context.filesDir, "nearby_attractions.json")
        if (!file.exists()) {
            PlacesRefreshWorker.schedule(context)
            return
        }
        
        val wrapper = JSONObject(file.readText())
        val fetchedLat = wrapper.optDouble("fetchedLat", 0.0)
        val fetchedLng = wrapper.optDouble("fetchedLng", 0.0)
        
        val distanceKm = haversineDistance(currentLat, currentLng, fetchedLat, fetchedLng)
        DebugLogger.log(context, "Moved ${String.format("%.2f", distanceKm)}km from last fetch.")

        if (distanceKm >= 15.0) {
            DebugLogger.log(context, "Hard reset triggered (>15km)")
            WikipediaWorker.cancel(context)
            PlacesRefreshWorker.schedule(context)
        } else if (distanceKm >= 3.0) { // Matching the 3km refresh logic from Manager
            DebugLogger.log(context, "Soft refresh triggered (>3km)")
            val rawPlaces = LocationService.loadAllPlacesFromCache(context) ?: emptyList()
            GeofenceManager.registerAll(context, rawPlaces, currentLat, currentLng)
        }
    }

    private fun showNotification(context: Context, place: EnrichedPlace) {
        val title = place.name
        val text = place.wikipediaSummary.take(120) + "..."
        sendNotification(context, place.placeId, title, text, true)
    }

    private fun showNotification(context: Context, place: NearbyPlace) {
        val title = place.name
        val text = buildString {
            if (place.rating > 0) append("⭐ ${place.rating} (${place.userRatingsTotal} reviews)")
            if (place.vicinity.isNotBlank()) append(" · ${place.vicinity}")
        }
        sendNotification(context, place.placeId, title, text, false)
    }

    private fun sendNotification(context: Context, placeId: String, title: String, text: String, isEnriched: Boolean) {
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_PLACE_ID, placeId)
            putExtra(EXTRA_IS_ENRICHED, isEnriched)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context, placeId.hashCode(), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        createNotificationChannel(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(placeId.hashCode(), notification)
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

    private fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID, "LocalLore Nearby Places", NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Notifications for nearby attractions" }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }
}
