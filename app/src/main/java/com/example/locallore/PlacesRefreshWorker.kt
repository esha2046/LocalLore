package com.example.locallore

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

class PlacesRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        DebugLogger.log(applicationContext, "PlacesRefreshWorker started")

        return try {
            // Step 1 — Get current location
            val location = LocationService.getCurrentLocation(applicationContext)
            if (location == null) {
                DebugLogger.log(applicationContext, "Worker: Location Failed")
                return Result.retry()
            }
            val (lat, lng) = location

            // Step 2 — Get city name
            val cityName = LocationService.getCityName(lat, lng, applicationContext)

            // Step 3 — Fetch new places from Google
            val apiKey = BuildConfig.PLACES_API_KEY
            val places = LocationService.getNearbyAttractions(applicationContext, lat, lng, apiKey)
            if (places.isEmpty()) {
                DebugLogger.log(applicationContext, "Worker: No Places Found")
                return Result.retry()
            }

            // Step 4 — Save to cache
            LocationService.savePlacesToJson(applicationContext, places, lat, lng, cityName)

            // Step 5 — Remove old geofences
            GeofenceManager.removeAll(applicationContext)

            // Step 6 — Register all new places as geofences immediately
            GeofenceManager.registerAll(applicationContext, places, lat, lng)

            // Step 7 — Schedule Wikipedia enrichment in background
            WikipediaWorker.cancel(applicationContext)
            WikipediaWorker.schedule(applicationContext)

            DebugLogger.log(applicationContext, "PlacesRefreshWorker Complete ✅")
            Result.success()

        } catch (e: Exception) {
            DebugLogger.log(applicationContext, "Worker Error: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<PlacesRefreshWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "places_refresh",
                ExistingWorkPolicy.REPLACE,
                request
            )
            Log.d("PlacesRefreshWorker", "Places refresh scheduled")
        }
    }
}