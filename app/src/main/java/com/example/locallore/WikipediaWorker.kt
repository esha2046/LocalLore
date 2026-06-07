package com.example.locallore

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class WikipediaWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        DebugLogger.log(applicationContext, "WikiWorker Started")

        return try {
            // Load places from cache
            val placesFile = File(applicationContext.filesDir, "nearby_attractions.json")
            if (!placesFile.exists()) {
                DebugLogger.log(applicationContext, "WikiWorker: No Cache Found")
                return Result.failure()
            }

            val wrapper = JSONObject(placesFile.readText())
            val jsonArray = wrapper.getJSONArray("places")
            val cityName = wrapper.optString("cityName").ifBlank { null }

            // Load progress file
            val progressFile = File(applicationContext.filesDir, "wikipedia_progress.json")
            val processedIds = mutableSetOf<String>()
            if (progressFile.exists()) {
                val progressJson = JSONArray(progressFile.readText())
                for (i in 0 until progressJson.length()) {
                    processedIds.add(progressJson.getString(i))
                }
            }

            val existingResult = WikipediaService.loadFromCache(applicationContext)
            val enriched = existingResult?.enriched?.toMutableList() ?: mutableListOf()
            val unenriched = existingResult?.unenriched?.toMutableList() ?: mutableListOf()
            unenriched.removeAll { it.placeId in processedIds }

            DebugLogger.log(applicationContext, "Resuming enrichment (${processedIds.size} done)")

            var processedInThisSession = 0
            val batchSize = 6

            // Process remaining places
            for (i in 0 until jsonArray.length()) {
                if (isStopped) {
                    DebugLogger.log(applicationContext, "WikiWorker Stopped Manually.")
                    return Result.success()
                }

                val obj = jsonArray.getJSONObject(i)
                val placeId = obj.getString("placeId")
                if (placeId in processedIds) continue
                if (enriched.any { it.placeId == placeId }) {
                    processedIds.add(placeId)
                    continue
                }

                if (processedInThisSession > 0 && processedInThisSession % batchSize == 0) {
                    DebugLogger.log(applicationContext, "Wiki Cooling down (5s)...")
                    delay(5000)
                }

                val place = NearbyPlace(
                    name = obj.getString("name"),
                    placeId = placeId,
                    lat = obj.getDouble("lat"),
                    lng = obj.getDouble("lng"),
                    rating = obj.optDouble("rating", 0.0),
                    userRatingsTotal = obj.optInt("userRatingsTotal", 0),
                    vicinity = obj.optString("vicinity", ""),
                    openNow = if (obj.isNull("openNow")) null else obj.optBoolean("openNow"),
                    photoReference = obj.optString("photoReference").ifBlank { null },
                    businessStatus = obj.optString("businessStatus", "OPERATIONAL"),
                    viewportNortheastLat = if (obj.isNull("viewportNELat")) null else obj.optDouble("viewportNELat"),
                    viewportNortheastLng = if (obj.isNull("viewportNELng")) null else obj.optDouble("viewportNELng"),
                    viewportSouthwestLat = if (obj.isNull("viewportSWLat")) null else obj.optDouble("viewportSWLat"),
                    viewportSouthwestLng = if (obj.isNull("viewportSWLng")) null else obj.optDouble("viewportSWLng")
                )

                val enrichedPlace = tryEnrichSingle(place, cityName)
                processedInThisSession++
                
                if (enrichedPlace != null) {
                    enriched.add(enrichedPlace)
                } else {
                    unenriched.add(place)
                }

                processedIds.add(placeId)
                val progressArray = JSONArray()
                processedIds.forEach { progressArray.put(it) }
                progressFile.writeText(progressArray.toString())

                WikipediaService.saveToCache(applicationContext, EnrichmentResult(enriched.toList(), unenriched.toList()))
                delay(500)
            }

            if (progressFile.exists()) progressFile.delete()
            DebugLogger.log(applicationContext, "Wiki Enrichment Complete! (${enriched.size} enriched)")

            // Update Geofences with the final enriched data
            val location = LocationService.getCurrentLocation(applicationContext)
            if (location != null) {
                val (lat, lng) = location
                val allPlaces = LocationService.loadAllPlacesFromCache(applicationContext) ?: emptyList()
                GeofenceManager.registerAll(applicationContext, allPlaces, lat, lng)
            }

            Result.success()

        } catch (e: Exception) {
            DebugLogger.log(applicationContext, "Wiki Error: ${e.message}")
            Result.retry()
        }
    }

    private suspend fun tryEnrichSingle(
        place: NearbyPlace,
        cityName: String?
    ): EnrichedPlace? {
        val queryWithCity = if (cityName != null) "${place.name} $cityName" else place.name
        val retryDelays = listOf(0L, 3000L, 10000L)

        for (delayMs in retryDelays) {
            if (delayMs > 0) {
                Log.d("WikipediaWorker", "Retrying ${place.name} after ${delayMs}ms...")
                delay(delayMs)
            }
            try {
                val result = WikipediaService.fetchSummaryForPlace(queryWithCity, place.name, cityName)
                return if (result != null) place.toEnrichedPlace(result.first, result.second) else null
            } catch (e: Exception) {
                Log.e("WikipediaWorker", "Error for ${place.name}: ${e.message}")
            }
        }
        return null
    }

    companion object {
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<WikipediaWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "wikipedia_enrichment",
                ExistingWorkPolicy.KEEP, // Don't restart if already running
                request
            )
            Log.d("WikipediaWorker", "Wikipedia enrichment job scheduled")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork("wikipedia_enrichment")
        }
    }
}
