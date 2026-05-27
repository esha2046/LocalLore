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

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "locallore_geofence_channel"
        const val EXTRA_PLACE_ID = "extra_place_id"
        const val EXTRA_IS_ENRICHED = "extra_is_enriched"
        private const val BOUNDARY_GEOFENCE_ID = "BOUNDARY_GEOFENCE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("GeofenceReceiver", "onReceive triggered!")
        val geofencingEvent = GeofencingEvent.fromIntent(intent) ?: run {
            Log.e("GeofenceReceiver", "GeofencingEvent was null")
            return
        }

        if (geofencingEvent.hasError()) {
            val errorMessage = GeofenceStatusCodes.getStatusCodeString(geofencingEvent.errorCode)
            Log.e("GeofenceReceiver", "Geofencing error: $errorMessage")
            return
        }

        val triggeringGeofences = geofencingEvent.triggeringGeofences ?: return
        val transition = geofencingEvent.geofenceTransition

        for (geofence in triggeringGeofences) {
            val geofenceId = geofence.requestId
            Log.d("GeofenceReceiver", "Triggered: $geofenceId, transition: $transition")

            // Handle boundary geofence exit — re-fetch places for new location
            if (geofenceId == BOUNDARY_GEOFENCE_ID) {
                Log.d("GeofenceReceiver", "Boundary exited! Scheduling new fetch...")
                WikipediaWorker.cancel(context)
                GeofenceManager.removeAll(context)
                // Schedule a fresh fetch via WorkManager
                PlacesRefreshWorker.schedule(context)
                return
            }

            // Find the place in cache
            val cache = WikipediaService.loadFromCache(context)
            if (cache == null) {
                Log.e("GeofenceReceiver", "No cache available")
                return
            }

            // Check enriched first
            val enrichedPlace = cache.enriched.find { it.placeId == geofenceId }
            if (enrichedPlace != null) {
                showNotification(context, enrichedPlace)
                return
            }

            // Check unenriched
            val unenrichedPlace = cache.unenriched.find { it.placeId == geofenceId }
            if (unenrichedPlace != null) {
                showNotification(context, unenrichedPlace)
                return
            }

            Log.e("GeofenceReceiver", "Place not found in cache for id: $geofenceId")
        }
    }

    private fun showNotification(context: Context, place: EnrichedPlace) {
        val shortSummary = place.wikipediaSummary.take(120) + "..."

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_PLACE_ID, place.placeId)
            putExtra(EXTRA_IS_ENRICHED, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context, place.placeId.hashCode(), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        buildAndShowNotification(
            context = context,
            placeId = place.placeId,
            title = place.name,
            text = shortSummary,
            pendingIntent = pendingIntent
        )
    }

    private fun showNotification(context: Context, place: NearbyPlace) {
        val text = buildString {
            if (place.rating > 0) append("⭐ ${place.rating} (${place.userRatingsTotal} reviews)")
            if (place.vicinity.isNotBlank()) append(" · ${place.vicinity}")
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_PLACE_ID, place.placeId)
            putExtra(EXTRA_IS_ENRICHED, false)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context, place.placeId.hashCode(), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        buildAndShowNotification(
            context = context,
            placeId = place.placeId,
            title = place.name,
            text = text,
            pendingIntent = pendingIntent
        )
    }

    private fun buildAndShowNotification(
        context: Context,
        placeId: String,
        title: String,
        text: String,
        pendingIntent: PendingIntent
    ) {
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

        val notificationManager = context.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        notificationManager.notify(placeId.hashCode(), notification)
        Log.d("GeofenceReceiver", "Notification shown for: $title")
    }

    private fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LocalLore Nearby Places",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications when you're near a point of interest"
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}