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
        Log.d("WikipediaWorker", "Starting Wikipedia enrichment job")

        return try {
            // Load places from cache
            val placesFile = File(applicationContext.filesDir, "nearby_attractions.json")
            if (!placesFile.exists()) {
                Log.e("WikipediaWorker", "No places cache found, stopping")
                return Result.failure()
            }

            val wrapper = JSONObject(placesFile.readText())
            val jsonArray = wrapper.getJSONArray("places")
            val cityName = wrapper.optString("cityName").ifBlank { null }

            // Load progress file to know where we left off
            val progressFile = File(applicationContext.filesDir, "wikipedia_progress.json")
            val processedIds = mutableSetOf<String>()
            if (progressFile.exists()) {
                val progressJson = JSONArray(progressFile.readText())
                for (i in 0 until progressJson.length()) {
                    processedIds.add(progressJson.getString(i))
                }
            }

            // Load existing results so we don't lose already enriched places
            val existingResult = WikipediaService.loadFromCache(applicationContext)
            val enriched = existingResult?.enriched?.toMutableList() ?: mutableListOf()
            val unenriched = existingResult?.unenriched?.toMutableList() ?: mutableListOf()

            // Remove already processed from unenriched so we don't double add
            unenriched.removeAll { it.placeId in processedIds }

            Log.d("WikipediaWorker", "Resuming from ${processedIds.size} already processed places")

            var processedInThisSession = 0
            val batchSize = 6

            // Process remaining places
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val placeId = obj.getString("placeId")

                // Skip already processed
                if (placeId in processedIds) continue

                // Batching logic: after every 6 attempts in this session, cool down
                if (processedInThisSession > 0 && processedInThisSession % batchSize == 0) {
                    Log.d("WikipediaWorker", "Batch limit ($batchSize) reached. Cooling down for 10s...")
                    delay(5000)
                }

                // MARK: Use existing radius from place if available
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

                // Try Wikipedia enrichment
                val enrichedPlace = tryEnrichSingle(place, cityName)
                processedInThisSession++
                
                if (enrichedPlace != null) {
                    enriched.add(enrichedPlace)
                    Log.d("WikipediaWorker", "✅ Enriched: ${place.name}")
                } else {
                    unenriched.add(place)
                    Log.d("WikipediaWorker", "📍 Unenriched: ${place.name}")
                }

                // Mark as processed and save progress
                processedIds.add(placeId)
                val progressArray = JSONArray()
                processedIds.forEach { progressArray.put(it) }
                progressFile.writeText(progressArray.toString())

                // Save results so far to cache after each place
                WikipediaService.saveToCache(
                    applicationContext,
                    EnrichmentResult(enriched.toList(), unenriched.toList())
                )

                // Individual request delay
                delay(8000)
            }

            // All done — delete progress file
            if (progressFile.exists()) progressFile.delete()
            Log.d("WikipediaWorker", "Wikipedia enrichment complete! ${enriched.size} enriched, ${unenriched.size} unenriched")

            // Update Geofences with the final enriched data
            val finalResult = EnrichmentResult(enriched.toList(), unenriched.toList())
            val location = LocationService.getCurrentLocation(applicationContext)
            if (location != null) {
                GeofenceManager.registerAll(applicationContext, finalResult, location.first, location.second)
                Log.d("WikipediaWorker", "Geofences updated with final enriched data")
            }

            Result.success()

        } catch (e: Exception) {
            Log.e("WikipediaWorker", "Worker failed", e)
            Result.retry() // WorkManager will retry automatically
        }
    }

    private suspend fun tryEnrichSingle(
        place: NearbyPlace,
        cityName: String?
    ): EnrichedPlace? {
        val queryWithCity = if (cityName != null) "${place.name} $cityName" else place.name
        val retryDelays = listOf(0L, 5000L, 10000L)

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
