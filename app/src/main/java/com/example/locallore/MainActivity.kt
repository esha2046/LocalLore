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
            }
        }
    }

    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            statusText = "Permissions granted! Click again to search."
        } else {
            statusText = "Location permission denied."
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val file = java.io.File(context.filesDir, "nearby_attractions.json")
                        val enrichedFile = java.io.File(context.filesDir, "enriched_places.json")
                        if (file.exists()) file.delete()
                        if (enrichedFile.exists()) enrichedFile.delete()
                    }
                    fetchedPlaces = emptyList()
                    statusText = "Cache cleared! Press Fetch to refresh."
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

                if (hasFineLocation && hasCoarseLocation) {
                    statusText = "Checking cache..."
                    scope.launch {
                        val location = LocationService.getCurrentLocation(context)
                        if (location == null) {
                            statusText = "Could not get location."
                            return@launch
                        }
                        val (lat, lng) = location
                        cityName = LocationService.getCityName(lat, lng)
                        // Try cache first, passing current location
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
                                LocationService.savePlacesToJson(context, places, lat, lng)
                            }
                            statusText = "Found ${places.size} attractions! Now press Enrich."
                            places.forEach { Log.d("LocalLore", "Place: ${it.name}") }
                        }
                    }
                } else {
                    permissionLauncher.launch(
                        arrayOf(
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
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
                statusText = "Fetching Wikipedia data..."
                scope.launch {
                    val enriched = WikipediaService.enrichAndFilter(fetchedPlaces, cityName)
                    statusText = "Done! ${enriched.size} real attractions out of ${fetchedPlaces.size}"
                    enriched.forEach {
                        Log.d("LocalLore", "✅ ${it.name}: ${it.wikipediaSummary.take(100)}...")
                    }
                }
            }) {
                Text("Enrich with Wikipedia")
            }
        }
    }
}