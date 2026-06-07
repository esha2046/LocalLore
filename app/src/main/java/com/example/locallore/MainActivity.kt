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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import coil.compose.AsyncImage
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
                MainScreen(context = this)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(context: Context, modifier: Modifier = Modifier) {
    val apiKey = BuildConfig.PLACES_API_KEY
    val scope = rememberCoroutineScope()

    // UI States
    var cityName by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf("Ready to explore?") }
    var fetchedPlaces by remember { mutableStateOf<List<NearbyPlace>>(emptyList()) }
    var enrichedMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showLogs by remember { mutableStateOf(false) }
    var logsText by remember { mutableStateOf("") }
    var showOptions by remember { mutableStateOf(false) }
    var selectedPlaceSummary by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Permission Launchers
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted && coarseGranted) {
            statusText = "Location granted! Tap the map to fetch."
            DebugLogger.log(context, "Foreground Location Granted")
        } else {
            statusText = "Location permissions denied."
        }
    }

    val backgroundPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            statusText = "Background tracking active."
            DebugLogger.log(context, "Background Location Granted")
        }
    }

    LaunchedEffect(Unit) {
        initAppDataFromCache(
            context,
            onCitySet = { cityName = it },
            onPlacesSet = { fetchedPlaces = it },
            onEnrichedSet = { enrichedMap = it },
            onStatusChange = { statusText = it }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("LocalLore", style = MaterialTheme.typography.titleLarge)
                        cityName?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showOptions = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    handleFetchClick(
                        context, scope, apiKey, permissionLauncher, backgroundPermissionLauncher,
                        onCityDetected = { cityName = it },
                        onPlacesUpdated = { fetchedPlaces = it },
                        onEnrichedUpdated = { enrichedMap = it },
                        onStatusChange = { statusText = it }
                    )
                },
                icon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                text = { Text("Fetch Nearby") }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // Status Bar
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = statusText,
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            if (fetchedPlaces.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("No attractions loaded", color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(fetchedPlaces) { place ->
                        PlaceItem(
                            place = place,
                            summary = enrichedMap[place.placeId],
                            apiKey = apiKey,
                            onClick = { summary ->
                                selectedPlaceSummary = place.name to (summary ?: "No Wikipedia summary available for this location yet.")
                            }
                        )
                    }
                }
            }
        }

        // Summary Dialog
        selectedPlaceSummary?.let { (title, summary) ->
            AlertDialog(
                onDismissRequest = { selectedPlaceSummary = null },
                title = { Text(title) },
                text = {
                    Box(modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                        Text(summary, style = MaterialTheme.typography.bodyMedium)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { selectedPlaceSummary = null }) { Text("Close") }
                }
            )
        }

        // Options Dialog
        if (showOptions) {
            AlertDialog(
                onDismissRequest = { showOptions = false },
                title = { Text("Settings & Maintenance") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                showOptions = false
                                handleEnrichmentClick(context, scope, fetchedPlaces, cityName, { enrichedMap = it }) { statusText = it }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Manual Wiki Enrich")
                        }
                        
                        OutlinedButton(
                            onClick = {
                                showOptions = false
                                handleStopBackgroundWork(context) { statusText = it }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Stop Background Work")
                        }

                        OutlinedButton(
                            onClick = {
                                showOptions = false
                                handleClearCache(context, scope) {
                                    fetchedPlaces = emptyList()
                                    statusText = it
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Clear All Caches")
                        }

                        TextButton(
                            onClick = {
                                showOptions = false
                                logsText = DebugLogger.getLogs(context)
                                showLogs = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("View Debug Logs")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showOptions = false }) { Text("Dismiss") }
                }
            )
        }

        // Logs Dialog
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
                            Text(logsText, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
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

@Composable
fun PlaceItem(place: NearbyPlace, summary: String?, apiKey: String, onClick: (String?) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        onClick = { onClick(summary) }
    ) {
        Column {
            if (place.photoReference != null) {
                AsyncImage(
                    model = "https://maps.googleapis.com/maps/api/place/photo?maxwidth=800&photo_reference=${place.photoReference}&key=$apiKey",
                    contentDescription = "Photo of ${place.name}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentScale = ContentScale.Crop
                )
            }
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(place.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (summary != null) {
                            Icon(
                                Icons.Default.AutoFixHigh,
                                contentDescription = "Enriched",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Text(
                        place.vicinity,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            " ${place.rating} (${place.userRatingsTotal} reviews)",
                            style = MaterialTheme.typography.labelSmall
                        )
                        if (place.openNow == true) {
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "OPEN NOW",
                                style = MaterialTheme.typography.labelSmall,
                                color = androidx.compose.ui.graphics.Color(0xFF4CAF50)
                            )
                        }
                    }
                }
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

private suspend fun initAppDataFromCache(
    context: Context,
    onCitySet: (String?) -> Unit,
    onPlacesSet: (List<NearbyPlace>) -> Unit,
    onEnrichedSet: (Map<String, String>) -> Unit,
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
            
            // Also load Wikipedia cache
            val wikiCached = withContext(Dispatchers.IO) { WikipediaService.loadFromCache(context) }
            if (wikiCached != null) {
                val map = wikiCached.enriched.associate { it.placeId to it.wikipediaSummary }
                onEnrichedSet(map)
                onStatusChange("Loaded ${cached.size} places (Enriched ✅)")
            } else {
                onStatusChange("Loaded ${cached.size} places from cache!")
            }
            
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
    onEnrichedUpdated: (Map<String, String>) -> Unit,
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
            
            val wikiCached = withContext(Dispatchers.IO) { WikipediaService.loadFromCache(context) }
            if (wikiCached != null) {
                onEnrichedUpdated(wikiCached.enriched.associate { it.placeId to it.wikipediaSummary })
            }

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
    onEnrichedUpdated: (Map<String, String>) -> Unit,
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
                val map = cached.enriched.associate { it.placeId to it.wikipediaSummary }
                onEnrichedUpdated(map)
                GeofenceManager.registerAll(context, fetchedPlaces, lat, lng)
                onStatusChange("Loaded from cache! Geofences active ✅")
            } else {
                onStatusChange("Fetching Wikipedia data...")
                val result = WikipediaService.enrichAndFilter(fetchedPlaces, cityName)
                withContext(Dispatchers.IO) { WikipediaService.saveToCache(context, result) }
                
                val map = result.enriched.associate { it.placeId to it.wikipediaSummary }
                onEnrichedUpdated(map)

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
