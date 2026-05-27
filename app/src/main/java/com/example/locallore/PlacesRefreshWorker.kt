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
        Log.d("PlacesRefreshWorker", "Starting places refresh for new location")

        return try {
            // Step 1 — Get current location
            val location = LocationService.getCurrentLocation(applicationContext)
            if (location == null) {
                Log.e("PlacesRefreshWorker", "Could not get location, retrying later")
                return Result.retry()
            }
            val (lat, lng) = location
            Log.d("PlacesRefreshWorker", "Got location: $lat, $lng")

            // Step 2 — Get city name
            val cityName = LocationService.getCityName(lat, lng)
            Log.d("PlacesRefreshWorker", "City: $cityName")

            // Step 3 — Fetch new places from Google
            val apiKey = BuildConfig.PLACES_API_KEY
            val places = LocationService.getNearbyAttractions(lat, lng, apiKey)
            if (places.isEmpty()) {
                Log.e("PlacesRefreshWorker", "No places returned, retrying later")
                return Result.retry()
            }
            Log.d("PlacesRefreshWorker", "Fetched ${places.size} places")

            // Step 4 — Save to cache
            LocationService.savePlacesToJson(applicationContext, places, lat, lng, cityName)

            // Step 5 — Remove old geofences
            GeofenceManager.removeAll(applicationContext)

            // Step 6 — Register all new places as unenriched geofences immediately
            val tempResult = EnrichmentResult(
                enriched = emptyList(),
                unenriched = places
            )
            GeofenceManager.registerAll(applicationContext, tempResult, lat, lng)

            // Step 7 — Schedule Wikipedia enrichment in background
            WikipediaWorker.cancel(applicationContext)
            WikipediaWorker.schedule(applicationContext)

            Log.d("PlacesRefreshWorker", "Places refresh complete!")
            Result.success()

        } catch (e: Exception) {
            Log.e("PlacesRefreshWorker", "Worker failed", e)
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