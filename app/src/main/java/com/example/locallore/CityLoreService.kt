package com.example.locallore

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class CityLore(
    val cityName: String,
    val timestamp: Long,
    val historyGemini: String?,
    val cultureGemini: String?
)

object CityLoreService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)  // Gemini 2.5 Flash thinks!
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private const val TAG = "CityLoreService"

    // Load from cache file: city_lore_${cityName}.json
    fun loadFromCache(context: Context, cityName: String): CityLore? {
        return try {
            val sanitizedCity = cityName.lowercase().replace(Regex("[^a-z0-9]"), "_")
            val file = File(context.filesDir, "city_lore_$sanitizedCity.json")
            if (!file.exists()) return null
            
            val json = JSONObject(file.readText())
            CityLore(
                cityName = json.getString("cityName"),
                timestamp = json.getLong("timestamp"),
                historyGemini = json.optString("historyGemini").ifBlank { null },
                cultureGemini = json.optString("cultureGemini").ifBlank { null }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error loading cache", e)
            null
        }
    }

    // Save to cache
    fun saveToCache(context: Context, lore: CityLore) {
        try {
            val sanitizedCity = lore.cityName.lowercase().replace(Regex("[^a-z0-9]"), "_")
            val file = File(context.filesDir, "city_lore_$sanitizedCity.json")
            val json = JSONObject().apply {
                put("cityName", lore.cityName)
                put("timestamp", lore.timestamp)
                put("historyGemini", lore.historyGemini ?: "")
                put("cultureGemini", lore.cultureGemini ?: "")
            }
            file.writeText(json.toString(4))
            DebugLogger.log(context, "Saved city lore for ${lore.cityName} to cache.")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving cache", e)
        }
    }

    // Clear caches
    fun clearCaches(context: Context) {
        try {
            val files = context.filesDir.listFiles { _, name -> name.startsWith("city_lore_") }
            files?.forEach { 
                if (it.delete()) {
                    DebugLogger.log(context, "Deleted cache file: ${it.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing caches", e)
        }
    }

    // Main fetch & cache
    suspend fun fetchAndCacheCityLore(context: Context, cityName: String, geminiApiKey: String): CityLore = withContext(Dispatchers.IO) {
        DebugLogger.log(context, "Fetching Gemini lore for $cityName...")
        
        if (geminiApiKey.isBlank()) {
            throw Exception("Gemini API Key is blank. Please configure GEMINI_API_KEY in local.properties.")
        }

        val historyGemini = fetchHistoryFromGemini(context, cityName, geminiApiKey)
        val cultureGemini = fetchCultureFromGemini(context, cityName, geminiApiKey)

        val lore = CityLore(
            cityName = cityName,
            timestamp = System.currentTimeMillis(),
            historyGemini = historyGemini,
            cultureGemini = cultureGemini
        )
        saveToCache(context, lore)
        lore
    }

    private suspend fun fetchHistoryFromGemini(context: Context, cityName: String, apiKey: String): String {
        val prompt = """
            Act as an engaging but informative local historian. 
            Tell me the history of the city of $cityName in a clean, narrative, and structured style. 
            Focus on key historical milestones, founding era, growth, and major events. 
            Keep it interesting, but prioritize accurate factual details and clarity.
            Break it down with clear subheadings starting with ## or ###. 
            Keep it around 300-400 words. Do not use overexcited slang or excessive exclamation marks.
        """.trimIndent()
        return queryGemini(context, prompt, apiKey)
    }

    private suspend fun fetchCultureFromGemini(context: Context, cityName: String, apiKey: String): String {
        val prompt = """
            Act as a knowledgeable and engaging local cultural guide. 
            Describe the culture of $cityName in an informative, structured, and clear way. 
            Focus heavily on:
            1. Unique local traditions, celebrations, festivals, or cultural quirks of the residents.
            2. Local cuisines (NOT hotels, restaurants, cafes, or dining spots. Focus strictly on local dishes, foods, drinks, and culinary history/flavors unique to $cityName or its immediate region).
            Provide interesting context, but prioritize accurate, localized information and clarity.
            Break it down with clear subheadings starting with ## or ###. 
            Keep it around 300-400 words. Do not use overexcited slang, excessive exclamation marks, or emoji-overload.
        """.trimIndent()
        return queryGemini(context, prompt, apiKey)
    }

    private suspend fun queryGemini(context: Context, prompt: String, apiKey: String): String {
        val configs = listOf(
            Pair("v1beta", "gemini-2.5-flash"),
            Pair("v1", "gemini-1.5-flash"),
            Pair("v1beta", "gemini-1.5-flash")
        )
        var lastException: Exception? = null

        for ((version, model) in configs) {
            try {
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val jsonReq = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", prompt)
                                })
                            })
                        })
                    })
                }
                val body = jsonReq.toString().toRequestBody(mediaType)
                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/$version/models/$model:generateContent?key=$apiKey")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "No error body"
                    var cleanErrorMessage = errorBody
                    try {
                        val errJson = JSONObject(errorBody)
                        val errorObj = errJson.optJSONObject("error")
                        if (errorObj != null) {
                            cleanErrorMessage = errorObj.optString("message", errorBody)
                        }
                    } catch (e: Exception) {}
                    
                    throw Exception(cleanErrorMessage)
                }
                val resStr = response.body?.string() ?: throw Exception("Empty response body from Gemini")
                val json = JSONObject(resStr)
                val candidates = json.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val candidate = candidates.getJSONObject(0)
                    val content = candidate.optJSONObject("content")
                    if (content != null) {
                        val parts = content.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            return parts.getJSONObject(0).getString("text")
                        }
                    }
                }
                throw Exception("Gemini API response candidate parsing failed.")
            } catch (e: Exception) {
                lastException = e
                DebugLogger.log(context, "Gemini $version ($model) query failed: ${e.message}")
            }
        }
        throw lastException ?: Exception("Unknown error querying Gemini API")
    }
}
