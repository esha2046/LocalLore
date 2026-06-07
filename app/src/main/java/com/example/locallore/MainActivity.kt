package com.example.locallore

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import com.example.locallore.ui.theme.LocalLoreTheme
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.app.ActivityCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseAuth.getInstance().signInAnonymously()
            .addOnSuccessListener { DebugLogger.log(this, "Firebase connected ✅") }
            .addOnFailureListener {
                DebugLogger.log(this, "Firebase Auth Failed")
            }
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1001
            )
        }
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
fun MainScreen(context: Context, modifier: Modifier = Modifier) {
    val apiKey = BuildConfig.PLACES_API_KEY
    val scope = rememberCoroutineScope()

    // UI States
    var cityName by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf("Press to find nearby attractions") }
    var fetchedPlaces by remember { mutableStateOf<List<NearbyPlace>>(emptyList()) }
    var showLogs by remember { mutableStateOf(false) }
    var logsText by remember { mutableStateOf("") }

    // Permission Launchers
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted && coarseGranted) {
            statusText = "Foreground location granted!"
            DebugLogger.log(context, "Foreground Location Granted")
        } else {
            statusText = "Basic location permissions denied."
        }
    }

    val backgroundPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            statusText = "Background location active!"
            DebugLogger.log(context, "Background Location Granted")
        } else {
            statusText = "Background location denied."
        }
    }

    // Startup Logic: Resume from cache
    LaunchedEffect(Unit) {
        initAppDataFromCache(context, { cityName = it }, { fetchedPlaces = it }, { statusText = it })
    }

    // Main UI Layout
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(statusText, modifier = Modifier.padding(bottom = 16.dp))
            
            Button(onClick = {
                handleClearCache(context, scope) {
                    fetchedPlaces = emptyList()
                    statusText = it
                }
            }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("Clear Cache")
            }

            Button(onClick = {
                handleFetchClick(
                    context, scope, apiKey, permissionLauncher, backgroundPermissionLauncher,
                    onCityDetected = { cityName = it },
                    onPlacesUpdated = { fetchedPlaces = it },
                    onStatusChange = { statusText = it }
                )
            }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("Fetch Nearby POIs")
            }

            Button(onClick = {
                val dbFile = java.io.File(context.filesDir, "nearby_attractions.json")
                if (dbFile.exists()) {
                    val count = fetchedPlaces.size
                    val wikiCache = WikipediaService.loadFromCache(context)
                    val enrichedCount = wikiCache?.enriched?.size ?: 0
                    
                    // Simple summary
                    logsText = "Database: $count places total\n" +
                            "Wikipedia: $enrichedCount enriched\n\n" +
                            "Full List:\n" + 
                            fetchedPlaces.joinToString("\n") { "- ${it.name}" }
                    showLogs = true
                } else {
                    statusText = "No attractions in database yet."
                }
            }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("Show All Attractions")
            }

            Button(onClick = {
                handleEnrichmentClick(context, scope, fetchedPlaces, cityName) {
                    statusText = it
                }
            }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("Manual Wiki Enrich")
            }

            Button(onClick = {
                handleStopBackgroundWork(context) { statusText = it }
            }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("Stop Background Work")
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(onClick = {
                logsText = DebugLogger.getLogs(context)
                showLogs = true
            }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                Text("View Debug Logs")
            }
        }

        if (showLogs) {
            AlertDialog(
                onDismissRequest = { showLogs = false },
                title = { Text("Device Logs") },
                text = {
                    Column {
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                           TextButton(onClick = { 
                               DebugLogger.clearLogs(context)
                               logsText = "Logs cleared."
                           }) { Text("Clear Logs") }
                        }
                        Box(modifier = Modifier.height(400.dp).verticalScroll(rememberScrollState())) {
                            Text(logsText, fontSize = 10.sp)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showLogs = false }) { Text("Close") }
                }
            )
        }
    }
}

private suspend fun initAppDataFromCache(
    context: Context,
    onCitySet: (String?) -> Unit,
    onPlacesSet: (List<NearbyPlace>) -> Unit,
    onStatusChange: (String) -> Unit
) {
    val location = LocationService.getCurrentLocation(context)
    if (location != null) {
        val (lat, lng) = location
        val detectedCity = LocationService.getCityName(lat, lng, context)
        onCitySet(detectedCity)

        val cached = withContext(Dispatchers.IO) { LocationService.loadPlacesFromJson(context, lat, lng) }
        if (cached != null) {
            onPlacesSet(cached)
            onStatusChange("Loaded ${cached.size} places from cache!")
            GeofenceManager.registerAll(context, cached, lat, lng)
            DebugLogger.log(context, "Geofences resumed from cache")
        } else {
            val rawBackup = LocationService.loadAllPlacesFromCache(context)
            if (rawBackup != null) {
                onPlacesSet(rawBackup)
                GeofenceManager.registerAll(context, rawBackup, lat, lng)
                onStatusChange("Jump detected! Re-syncing POIs...")
                DebugLogger.log(context, "Jump detected! Syncing POIs from local backup.")
            }
        }
    }
}

private fun handleClearCache(
    context: Context,
    scope: CoroutineScope,
    onCleared: (String) -> Unit
) {
    scope.launch {
        withContext(Dispatchers.IO) {
            LocationService.clearAllCaches(context)
            WorkManager.getInstance(context).cancelAllWork()
        }
        DebugLogger.log(context, "Caches and Work cleared.")
        onCleared("All caches cleared! Press Fetch to refresh.")
    }
}

private fun handleFetchClick(
    context: Context,
    scope: CoroutineScope,
    apiKey: String,
    launcher: ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>,
    bgLauncher: ManagedActivityResultLauncher<String, Boolean>,
    onCityDetected: (String?) -> Unit,
    onPlacesUpdated: (List<NearbyPlace>) -> Unit,
    onStatusChange: (String) -> Unit
) {
    if (!hasBasicLocation(context)) {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        launcher.launch(permissions.toTypedArray())
        return
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocation(context)) {
        onStatusChange("Requesting Background Location...")
        bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        return
    }

    onStatusChange("Checking cache...")
    scope.launch {
        val location = LocationService.getCurrentLocation(context) ?: run {
            onStatusChange("Could not get location.")
            return@launch
        }
        
        val (lat, lng) = location
        val city = LocationService.getCityName(lat, lng, context)
        onCityDetected(city)

        val cached = withContext(Dispatchers.IO) { LocationService.loadPlacesFromJson(context, lat, lng) }
        if (cached != null) {
            onPlacesUpdated(cached)
            onStatusChange("Loaded ${cached.size} places from cache!")
            GeofenceManager.registerAll(context, cached, lat, lng)
        } else {
            onStatusChange("Fetching nearby attractions...")
            val places = LocationService.getNearbyAttractions(context, lat, lng, apiKey)
            onPlacesUpdated(places)
            withContext(Dispatchers.IO) {
                LocationService.savePlacesToJson(context, places, lat, lng, city)
            }
            WikipediaWorker.schedule(context)
            onStatusChange("Found ${places.size} attractions! Enriching...")
            GeofenceManager.registerAll(context, places, lat, lng)
        }
    }
}

private fun handleEnrichmentClick(
    context: Context,
    scope: CoroutineScope,
    fetchedPlaces: List<NearbyPlace>,
    cityName: String?,
    onStatusChange: (String) -> Unit
) {
    if (fetchedPlaces.isEmpty()) {
        onStatusChange("Fetch locations first!")
        return
    }

    scope.launch {
        onStatusChange("Checking Wikipedia cache...")
        val cached = withContext(Dispatchers.IO) { WikipediaService.loadFromCache(context) }
        
        val location = LocationService.getCurrentLocation(context)
        if (location != null) {
            val (lat, lng) = location
            if (cached != null) {
                GeofenceManager.registerAll(context, fetchedPlaces, lat, lng)
                onStatusChange("Loaded from cache! Geofences active ✅")
            } else {
                onStatusChange("Fetching Wikipedia data...")
                val result = WikipediaService.enrichAndFilter(fetchedPlaces, cityName)
                withContext(Dispatchers.IO) { WikipediaService.saveToCache(context, result) }
                GeofenceManager.registerAll(context, fetchedPlaces, lat, lng)
                onStatusChange("Done! Enriched POIs active.")
            }
        }
    }
}

private fun handleStopBackgroundWork(context: Context, onStatusChange: (String) -> Unit) {
    WikipediaWorker.cancel(context)
    WorkManager.getInstance(context).cancelAllWork()
    DebugLogger.log(context, "Work stopped manually.")
    onStatusChange("Background work cancelled")
}

private fun hasBasicLocation(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}

private fun hasBackgroundLocation(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    } else true
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
