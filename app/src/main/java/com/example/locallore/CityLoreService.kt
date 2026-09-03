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
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private const val TAG = "CityLoreService"

    fun loadFromCache(context: Context, cityName: String): CityLore? {
        return try {
            val sanitizedCity = cityName.lowercase().replace(Regex("[^a-z0-9]"), "_")
            val file = File(context.filesDir, "city_lore_$sanitizedCity.json")
            if (!file.exists()) return null
            
            val json = JSONObject(file.readText())
            val lore = CityLore(
                cityName = json.getString("cityName"),
                timestamp = json.getLong("timestamp"),
                historyGemini = json.optString("historyGemini").ifBlank { null },
                cultureGemini = json.optString("cultureGemini").ifBlank { null }
            )
            DebugLogger.log(context, "Loaded city lore for $cityName from disk.")
            lore
        } catch (e: Exception) {
            Log.e(TAG, "Error loading cache", e)
            null
        }
    }

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

    suspend fun fetchAndCacheCityLore(context: Context, cityName: String, geminiApiKey: String): CityLore = withContext(Dispatchers.IO) {
        DebugLogger.log(context, "Starting Gemini fetch for $cityName...")
        
        if (geminiApiKey.isBlank()) {
            throw Exception("Gemini API Key is blank.")
        }

        val history = fetchHistoryFromGemini(context, cityName, geminiApiKey)
        val culture = fetchCultureFromGemini(context, cityName, geminiApiKey)

        val lore = CityLore(
            cityName = cityName,
            timestamp = System.currentTimeMillis(),
            historyGemini = history,
            cultureGemini = culture
        )
        saveToCache(context, lore)
        lore
    }

    private suspend fun fetchHistoryFromGemini(context: Context, cityName: String, apiKey: String): String {
        val prompt = """
            Act as an engaging but informative local historian. 
            Tell me the history of the city of $cityName in a clean, narrative, and structured style. 
            Focus on key historical milestones, founding era, growth, and major events. 
            Break it down with clear subheadings starting with ## or ###. 
            Keep it around 300-400 words.
        """.trimIndent()
        return queryGemini(context, prompt, apiKey)
    }

    private suspend fun fetchCultureFromGemini(context: Context, cityName: String, apiKey: String): String {
        val prompt = """
            Act as a knowledgeable and engaging local cultural guide. 
            Describe the culture of $cityName in an informative, structured, and clear way. 
            Focus heavily on unique local traditions, celebrations, and local cuisines unique to $cityName.
            Break it down with clear subheadings starting with ## or ###. 
            Keep it around 300-400 words.
        """.trimIndent()
        return queryGemini(context, prompt, apiKey)
    }

    private suspend fun queryGemini(context: Context, prompt: String, apiKey: String): String {
        val configs = listOf(
            Pair("v1beta", "gemini-2.5-flash"), // Corrected name for the latest lite model
            Pair("v1beta", "gemini-2.5-flash-lite"),
            Pair("v1", "gemini-2.0-flash")
        )
        var lastException: Exception? = null

        for ((version, model) in configs) {
            try {
                DebugLogger.log(context, "Trying Gemini $model ($version)...")
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
                val responseStr = response.body?.string()

                if (!response.isSuccessful) {
                    val errorMsg = responseStr ?: "No error body"
                    throw Exception("HTTP ${response.code}: $errorMsg")
                }

                val json = JSONObject(responseStr ?: throw Exception("Empty response"))
                val text = json.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")

                if (text.isNotBlank()) return text
                
            } catch (e: Exception) {
                lastException = e
                DebugLogger.log(context, "Gemini $model failed: ${e.message}")
            }
        }
        throw lastException ?: Exception("Unknown Gemini error")
    }
}
