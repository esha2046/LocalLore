package com.example.locallore

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.locallore.ui.theme.LocalLoreTheme
import com.google.firebase.auth.FirebaseAuth
import androidx.work.WorkManager

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseAuth.getInstance().signInAnonymously()
            .addOnSuccessListener { Log.d("LocalLore", "Firebase connected! ✅") }
            .addOnFailureListener { 
                Log.e("LocalLore", "Firebase Auth Failed", it)
                if (it is com.google.firebase.FirebaseNetworkException) {
                    Log.e("LocalLore", "Network issue detected. Check emulator internet.")
                }
            }
        enableEdgeToEdge()
        setContent {
            LocalLoreTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(context = this, modifier = Modifier.padding(innerPadding))
                }
            }
        }

    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "Hello $name!")
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    LocalLoreTheme {
        Greeting("Android")
    }
}

@Composable
fun MainScreen(context: android.content.Context, modifier: Modifier = Modifier) {
    val apiKey = BuildConfig.PLACES_API_KEY
    var cityName by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf("Press to find nearby attractions") }
    var fetchedPlaces by remember { mutableStateOf<List<NearbyPlace>>(emptyList()) }
    LaunchedEffect(Unit) {
        val location = LocationService.getCurrentLocation(context)
        if (location != null) {
            val (lat, lng) = location
            cityName = LocationService.getCityName(lat, lng)
            Log.d("LocalLore", "Detected City on Start: $cityName")

            val cached = withContext(Dispatchers.IO) {
                LocationService.loadPlacesFromJson(context, lat, lng)
            }
            if (cached != null) {
                fetchedPlaces = cached
                statusText = "Loaded ${cached.size} places from cache!"
                
                // Automatically resume Geofences on app start if cache exists
                val wikiCache = withContext(Dispatchers.IO) {
                    WikipediaService.loadFromCache(context)
                }
                if (wikiCache != null) {
                    GeofenceManager.registerAll(context, wikiCache, lat, lng)
                    Log.d("LocalLore", "Geofences resumed on start from cache")
                }
            }
        }
    }

    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineGranted && coarseGranted) {
            statusText = "Foreground location granted!"
        } else {
            statusText = "Basic location permissions denied."
        }
    }

    val backgroundPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            statusText = "Background location active! Geofences ready."
        } else {
            statusText = "Background location denied. Geofencing will only work while app is open."
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        LocationService.clearAllCaches(context)
                        WorkManager.getInstance(context).cancelAllWork()
                    }
                    fetchedPlaces = emptyList()
                    statusText = "All caches cleared! Press Fetch to refresh."
                    Log.d("LocalLore", "Cache manually cleared 🗑️")
                }
            }) {
                Text("Clear Cache")
            }

            // Button 1 — Fetch from Google
            Button(onClick = {
                val hasFineLocation = androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val hasCoarseLocation = androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.ACCESS_COARSE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                
                val hasBackground = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                } else true

                if (hasFineLocation && hasCoarseLocation) {
                    if (!hasBackground && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        statusText = "Requesting Background Location..."
                        backgroundPermissionLauncher.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        return@Button
                    }
                    
                    statusText = "Checking cache..."
                    scope.launch {
                        val location = LocationService.getCurrentLocation(context)
                        if (location == null) {
                            statusText = "Could not get location."
                            return@launch
                        }
                        val (lat, lng) = location
                        cityName = LocationService.getCityName(lat, lng)

                        val cached = withContext(Dispatchers.IO) {
                            LocationService.loadPlacesFromJson(context, lat, lng)
                        }

                        if (cached != null) {
                            fetchedPlaces = cached
                            statusText = "Loaded ${cached.size} places from cache!"
                        } else {
                            statusText = "Fetching nearby attractions..."
                            val places = LocationService.getNearbyAttractions(lat, lng, apiKey)
                            fetchedPlaces = places
                            withContext(Dispatchers.IO) {
                                LocationService.savePlacesToJson(context, places, lat, lng, cityName)
                            }
                            WikipediaWorker.schedule(context)
                            statusText = "Found ${places.size} attractions! Enriching in background..."
                        }

                        // Register geofences with whatever Wikipedia data we have so far
                        val currentCache = withContext(Dispatchers.IO) {
                            WikipediaService.loadFromCache(context)
                        }
                        if (currentCache != null) {
                            GeofenceManager.registerAll(context, currentCache, lat, lng)
                            statusText = "Geofences active ✅"
                        } else {
                            val tempResult = EnrichmentResult(
                                enriched = emptyList(),
                                unenriched = fetchedPlaces
                            )
                            GeofenceManager.registerAll(context, tempResult, lat, lng)
                            statusText = "Geofences active, enriching in background..."
                        }
                    }
                } else {
                    val permissions = mutableListOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                    permissionLauncher.launch(permissions.toTypedArray())
                }
            }) {
                Text(statusText)
            }

            // Button 2 — Enrich with Wikipedia
            Button(onClick = {
                if (fetchedPlaces.isEmpty()) {
                    statusText = "Fetch locations first!"
                    return@Button
                }
                statusText = "Checking Wikipedia cache..."
                scope.launch {
                    val cached = withContext(Dispatchers.IO) {
                        WikipediaService.loadFromCache(context)
                    }
                    if (cached != null) {
                        // FIX: Even if loaded from cache, ensure Geofences are active
                        val location = LocationService.getCurrentLocation(context)
                        if (location != null) {
                            GeofenceManager.registerAll(context, cached, location.first, location.second)
                            statusText = "Loaded from cache! Geofences active ✅"
                        } else {
                            statusText = "Loaded from cache, but location unavailable for geofences."
                        }
                        Log.d("LocalLore", "Enriched: ${cached.enriched.size}, Unenriched: ${cached.unenriched.size}")
                    } else {
                        statusText = "Fetching Wikipedia data..."
                        val result = WikipediaService.enrichAndFilter(fetchedPlaces, cityName)
                        withContext(Dispatchers.IO) {
                            WikipediaService.saveToCache(context, result)
                        }

                        // Set up POIs immediately after enrichment
                        val location = LocationService.getCurrentLocation(context)
                        if (location != null) {
                            GeofenceManager.registerAll(context, result, location.first, location.second)
                            statusText = "Done! ${result.enriched.size} enriched. Geofences updated ✅"
                        } else {
                            statusText = "Enriched, but location unavailable for geofences."
                        }

                        result.enriched.forEach {
                            Log.d("LocalLore", "✅ ${it.name}: ${it.wikipediaSummary.take(100)}...")
                        }
                        result.unenriched.forEach {
                            Log.d("LocalLore", "📍 ${it.name} (no Wikipedia)")
                        }
                    }
                }
            }) {
                Text("Enrich with Wikipedia")
            }

            // For stopping the background processes
            Button(onClick = {
                WikipediaWorker.cancel(context)
                WorkManager.getInstance(context).cancelAllWork()
                statusText = "Background work cancelled"
                Log.d("LocalLore", "All WorkManager jobs cancelled")
            }) {
                Text("Stop Background Work")
            }
        }
    }
}