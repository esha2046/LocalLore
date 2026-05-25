package com.example.locallore

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

data class EnrichedPlace(
    val name: String,
    val placeId: String,
    val lat: Double,
    val lng: Double,
    val wikipediaSummary: String,
    val wikipediaTitle: String
)

object WikipediaService {

    private val client = OkHttpClient()

    // Main function — takes raw places, returns only valid ones with Wikipedia summaries
    suspend fun enrichAndFilter(places: List<NearbyPlace>, cityName: String? = null): List<EnrichedPlace> = coroutineScope {
        val results = mutableListOf<EnrichedPlace>()
        for (place in places) {
            val enriched = tryEnrichPlace(place, cityName)
            if (enriched != null) results.add(enriched)
            delay(500)
        }
        results
    }

    private suspend fun tryEnrichPlace(place: NearbyPlace, cityName: String? = null): EnrichedPlace? {
        val queryWithCity = if (cityName != null) "${place.name} $cityName" else place.name

        // Try up to 3 times with increasing delays on failure
        val retryDelays = listOf(0L, 4000L, 6000L)

        for (delayMs in retryDelays) {
            if (delayMs > 0) {
                Log.d("WikipediaService", "Retrying ${place.name} after ${delayMs}ms...")
                delay(delayMs)
            }

            try {
                val directResult = fetchDirectSummary(queryWithCity)
                    ?: if (cityName != null) fetchDirectSummary(place.name) else null

                if (directResult != null) {
                    Log.d("WikipediaService", "Direct hit: ${place.name}")
                    return EnrichedPlace(
                        name = place.name,
                        placeId = place.placeId,
                        lat = place.lat,
                        lng = place.lng,
                        wikipediaSummary = directResult.first,
                        wikipediaTitle = directResult.second
                    )
                }

                delay(500)
                val searchResult = fetchViaSearch(queryWithCity)
                    ?: if (cityName != null) fetchViaSearch(place.name) else null

                if (searchResult != null) {
                    Log.d("WikipediaService", "Search hit: ${place.name} → ${searchResult.second}")
                    return EnrichedPlace(
                        name = place.name,
                        placeId = place.placeId,
                        lat = place.lat,
                        lng = place.lng,
                        wikipediaSummary = searchResult.first,
                        wikipediaTitle = searchResult.second
                    )
                }

                // If we got here without an exception, Wikipedia just has no page — no point retrying
                Log.d("WikipediaService", "No Wikipedia page, discarding: ${place.name}")
                return null

            } catch (e: Exception) {
                // Exception means rate limit or network issue — retry with longer delay
                Log.e("WikipediaService", "Error on attempt for ${place.name}, will retry: ${e.message}")
                // Continue to next retry
            }
        }

        Log.e("WikipediaService", "All retries exhausted for ${place.name}")
        return null
    }

    // Returns Pair(summary, title) or null if not found
    private suspend fun fetchDirectSummary(placeName: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val encoded = placeName.replace(" ", "_")
        val url = "https://en.wikipedia.org/api/rest_v1/page/summary/$encoded"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "LocalLore/1.0 (Android app; educational project)")
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return@withContext null

        // Throw on rate limit so retry logic kicks in
        if (!body.trimStart().startsWith("{")) {
            throw Exception("Rate limited or non-JSON response for $placeName")
        }

        if (!response.isSuccessful) return@withContext null

        val json = JSONObject(body)
        if (json.optString("type") == "disambiguation") return@withContext null

        val summary = json.optString("extract", "")
        val title = json.optString("title", "")

        if (summary.isBlank()) null else Pair(summary, title)
    }

    private suspend fun fetchViaSearch(placeName: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(placeName, "UTF-8")
        val searchUrl = "https://en.wikipedia.org/w/api.php" +
                "?action=query&list=search&srsearch=$encoded&format=json&srlimit=1"
        
        Log.d("WikipediaService", "Searching Wikipedia for: $placeName")

        val request = Request.Builder()
            .url(searchUrl)
            .header("User-Agent", "LocalLore/1.0 (Android app; educational project)")
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return@withContext null

        // Throw on rate limit so retry logic kicks in
        if (!body.trimStart().startsWith("{")) {
            throw Exception("Rate limited or non-JSON response for $placeName")
        }

        val json = JSONObject(body)
        val searchResults = json
            .getJSONObject("query")
            .getJSONArray("search")

        if (searchResults.length() == 0) return@withContext null

        val topResult = searchResults.getJSONObject(0)
        val resultTitle = topResult.getString("title")

        if (!isTitleRelevant(placeName, resultTitle)) {
            Log.d("WikipediaService", "Search result '$resultTitle' not relevant to '$placeName', discarding")
            return@withContext null
        }

        fetchDirectSummary(resultTitle)
    }

    // Checks if the Wikipedia result title is meaningfully related to the place name
    private fun isTitleRelevant(placeName: String, resultTitle: String): Boolean {
        // Words to ignore in comparison
        val stopWords = setOf(
            "the", "a", "an", "of", "in", "at", "and", "or",
            "temple", "church", "mosque", "park", "museum", "fort",
            "palace", "garden", "lake", "hill", "road", "street"
        )

        val placeWords = placeName.lowercase()
            .split(" ", "-", "'", ".")
            .filter { it.length > 2 && it !in stopWords }
            .toSet()

        val titleWords = resultTitle.lowercase()
            .split(" ", "-", "'", ".")
            .filter { it.length > 2 && it !in stopWords }
            .toSet()

        val commonWords = placeWords.intersect(titleWords)

        // At least one meaningful word must match
        return commonWords.isNotEmpty()
    }
}