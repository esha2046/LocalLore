package com.example.locallore

import android.content.Context
import android.util.Log
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

data class EnrichedPlace(
    val name: String,
    val placeId: String,
    val lat: Double,
    val lng: Double,
    val wikipediaSummary: String,
    val wikipediaTitle: String,
    // carry over from NearbyPlace
    val rating: Double,
    val userRatingsTotal: Int,
    val vicinity: String,
    val openNow: Boolean?,
    val photoReference: String?,
    val businessStatus: String,
    val viewportNortheastLat: Double?,
    val viewportNortheastLng: Double?,
    val viewportSouthwestLat: Double?,
    val viewportSouthwestLng: Double?
)

data class EnrichmentResult(
    val enriched: List<EnrichedPlace>,
    val unenriched: List<NearbyPlace>
)

object WikipediaService {

    private val client = OkHttpClient()
    private const val USER_AGENT = "LocalLore/1.0 (com.example.locallore; locallore@example.com)"

    suspend fun enrichAndFilter(
        places: List<NearbyPlace>,
        cityName: String? = null
    ): EnrichmentResult = coroutineScope {
        val enriched = mutableListOf<EnrichedPlace>()
        val unenriched = mutableListOf<NearbyPlace>()

        val batchSize = 6
        for (i in places.indices) {
            val place = places[i]

            if (i > 0 && i % batchSize == 0) {
                Log.d("WikipediaService", "Batch limit reached. Cooling down for 10s...")
                delay(10000)
            }

            val result = tryEnrichPlace(place, cityName)
            if (result != null) {
                enriched.add(result)
            } else {
                unenriched.add(place)
            }
            
            // Standard delay between individual requests
            delay(800)
        }

        Log.d("WikipediaService", "Enriched: ${enriched.size}, Unenriched: ${unenriched.size}")
        EnrichmentResult(enriched, unenriched)
    }

    private suspend fun tryEnrichPlace(
        place: NearbyPlace,
        cityName: String? = null
    ): EnrichedPlace? {
        val queryWithCity = if (cityName != null) "${place.name} $cityName" else place.name
        val retryDelays = listOf(0L, 5000L, 10000L)

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
                    return place.toEnrichedPlace(directResult.first, directResult.second)
                }

                delay(800)
                val searchResult = fetchViaSearch(queryWithCity)
                    ?: if (cityName != null) fetchViaSearch(place.name) else null

                if (searchResult != null) {
                    Log.d("WikipediaService", "Search hit: ${place.name} → ${searchResult.second}")
                    return place.toEnrichedPlace(searchResult.first, searchResult.second)
                }

                // No Wikipedia page — not retrying
                Log.d("WikipediaService", "No Wikipedia page, keeping as unenriched: ${place.name}")
                return null

            } catch (e: Exception) {
                Log.e("WikipediaService", "Error for ${place.name}, will retry: ${e.message}")
            }
        }

        Log.e("WikipediaService", "All retries exhausted for ${place.name}")
        return null
    }

    private suspend fun fetchDirectSummary(placeName: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val encoded = placeName.replace(" ", "_")
        val url = "https://en.wikipedia.org/api/rest_v1/page/summary/$encoded"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return@withContext null
        if (!body.trimStart().startsWith("{")) {
            Log.e("WikipediaService", "Non-JSON for '$placeName': ${body.take(200)}")
            throw Exception("Non-JSON response for $placeName")
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

        val request = Request.Builder()
            .url(searchUrl)
            .header("User-Agent", USER_AGENT)
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return@withContext null

        if (!body.trimStart().startsWith("{")) {
            Log.e("WikipediaService", "Non-JSON for '$placeName': ${body.take(200)}")
            throw Exception("Non-JSON response for $placeName")
        }

        val json = JSONObject(body)
        val searchResults = json
            .getJSONObject("query")
            .getJSONArray("search")

        if (searchResults.length() == 0) return@withContext null

        val topResult = searchResults.getJSONObject(0)
        val resultTitle = topResult.getString("title")

        if (!isTitleRelevant(placeName, resultTitle)) {
            Log.d("WikipediaService", "Not relevant: '$resultTitle' for '$placeName'")
            return@withContext null
        }

        fetchDirectSummary(resultTitle)
    }

    private fun isTitleRelevant(placeName: String, resultTitle: String): Boolean {
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

        return placeWords.intersect(titleWords).isNotEmpty()
    }

    // Cache functions

    fun saveToCache(context: Context, result: EnrichmentResult) {
        try {
            // Save enriched
            val enrichedArray = JSONArray()
            for (place in result.enriched) {
                enrichedArray.put(JSONObject().apply {
                    put("name", place.name)
                    put("placeId", place.placeId)
                    put("lat", place.lat)
                    put("lng", place.lng)
                    put("wikipediaSummary", place.wikipediaSummary)
                    put("wikipediaTitle", place.wikipediaTitle)
                    put("rating", place.rating)
                    put("userRatingsTotal", place.userRatingsTotal)
                    put("vicinity", place.vicinity)
                    put("openNow", place.openNow)
                    put("photoReference", place.photoReference ?: "")
                    put("businessStatus", place.businessStatus)
                    put("viewportNELat", place.viewportNortheastLat ?: "")
                    put("viewportNELng", place.viewportNortheastLng ?: "")
                    put("viewportSWLat", place.viewportSouthwestLat ?: "")
                    put("viewportSWLng", place.viewportSouthwestLng ?: "")
                })
            }

            // Save unenriched
            val unenrichedArray = JSONArray()
            for (place in result.unenriched) {
                unenrichedArray.put(JSONObject().apply {
                    put("name", place.name)
                    put("placeId", place.placeId)
                    put("lat", place.lat)
                    put("lng", place.lng)
                    put("rating", place.rating)
                    put("userRatingsTotal", place.userRatingsTotal)
                    put("vicinity", place.vicinity)
                    put("openNow", place.openNow)
                    put("photoReference", place.photoReference ?: "")
                    put("businessStatus", place.businessStatus)
                    put("viewportNELat", place.viewportNortheastLat ?: "")
                    put("viewportNELng", place.viewportNortheastLng ?: "")
                    put("viewportSWLat", place.viewportSouthwestLat ?: "")
                    put("viewportSWLng", place.viewportSouthwestLng ?: "")
                })
            }

            val wrapper = JSONObject()
            wrapper.put("timestamp", System.currentTimeMillis())
            wrapper.put("enriched", enrichedArray)
            wrapper.put("unenriched", unenrichedArray)

            File(context.filesDir, "enriched_places.json").writeText(wrapper.toString(4))
            Log.d("WikipediaService", "Saved to cache: ${result.enriched.size} enriched, ${result.unenriched.size} unenriched")

        } catch (e: Exception) {
            Log.e("WikipediaService", "Failed to save cache", e)
        }
    }

    fun loadFromCache(context: Context): EnrichmentResult? {
        return try {
            val file = File(context.filesDir, "enriched_places.json")
            if (!file.exists()) return null

            val wrapper = JSONObject(file.readText())
            val timestamp = wrapper.getLong("timestamp")
            val ageMinutes = (System.currentTimeMillis() - timestamp) / 60000

            if (ageMinutes > 60) {
                Log.d("WikipediaService", "Cache expired: ${ageMinutes} mins old")
                return null
            }

            val enriched = mutableListOf<EnrichedPlace>()
            val enrichedArray = wrapper.getJSONArray("enriched")
            for (i in 0 until enrichedArray.length()) {
                val obj = enrichedArray.getJSONObject(i)
                enriched.add(
                    EnrichedPlace(
                        name = obj.getString("name"),
                        placeId = obj.getString("placeId"),
                        lat = obj.getDouble("lat"),
                        lng = obj.getDouble("lng"),
                        wikipediaSummary = obj.getString("wikipediaSummary"),
                        wikipediaTitle = obj.getString("wikipediaTitle"),
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
                )
            }

            val unenriched = mutableListOf<NearbyPlace>()
            val unenrichedArray = wrapper.getJSONArray("unenriched")
            for (i in 0 until unenrichedArray.length()) {
                val obj = unenrichedArray.getJSONObject(i)
                unenriched.add(
                    NearbyPlace(
                        name = obj.getString("name"),
                        placeId = obj.getString("placeId"),
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
                )
            }

            Log.d("WikipediaService", "Loaded from cache: ${enriched.size} enriched, ${unenriched.size} unenriched")
            EnrichmentResult(enriched, unenriched)

        } catch (e: Exception) {
            Log.e("WikipediaService", "Failed to load cache", e)
            null
        }
    }

    suspend fun fetchSummaryForPlace(
        queryWithCity: String,
        originalName: String,
        cityName: String?
    ): Pair<String, String>? {
        val directResult = fetchDirectSummary(queryWithCity)
            ?: if (cityName != null) fetchDirectSummary(originalName) else null
        if (directResult != null) return directResult

        delay(800)
        return fetchViaSearch(queryWithCity)
            ?: if (cityName != null) fetchViaSearch(originalName) else null
    }
}

// Extension function to cleanly convert NearbyPlace to EnrichedPlace
fun NearbyPlace.toEnrichedPlace(summary: String, title: String) = EnrichedPlace(
    name = name,
    placeId = placeId,
    lat = lat,
    lng = lng,
    wikipediaSummary = summary,
    wikipediaTitle = title,
    rating = rating,
    userRatingsTotal = userRatingsTotal,
    vicinity = vicinity,
    openNow = openNow,
    photoReference = photoReference,
    businessStatus = businessStatus,
    viewportNortheastLat = viewportNortheastLat,
    viewportNortheastLng = viewportNortheastLng ,
    viewportSouthwestLat = viewportSouthwestLat,
    viewportSouthwestLng = viewportSouthwestLng
)