package com.example.locallore

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    var selectedPlace by remember { mutableStateOf<NearbyPlace?>(null) }
    var showLogs by remember { mutableStateOf(false) }
    var logsText by remember { mutableStateOf("") }
    var showOptions by remember { mutableStateOf(false) }

    // Permission Launchers
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted && coarseGranted) {
            statusText = "Location granted! Tap fetch to begin."
        }
    }

    val backgroundPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) DebugLogger.log(context, "Background tracking active.")
    }

    // Startup Logic
    LaunchedEffect(Unit) {
        val intent = (context as? android.app.Activity)?.intent
        val placeIdFromNotif = intent?.getStringExtra("extra_place_id")

        initAppDataFromCache(
            context,
            onCitySet = { cityName = it },
            onPlacesSet = { places -> 
                fetchedPlaces = places
                if (placeIdFromNotif != null) {
                    selectedPlace = places.find { it.placeId == placeIdFromNotif }
                }
            },
            onEnrichedSet = { enrichedMap = it },
            onStatusChange = { statusText = it }
        )
    }

    Scaffold(
        topBar = {
            if (selectedPlace == null) {
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
                    }
                )
            }
        },
        floatingActionButton = {
            if (selectedPlace == null) {
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
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(text = statusText, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.labelMedium)
                }

                if (fetchedPlaces.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No attractions loaded", color = MaterialTheme.colorScheme.outline)
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(fetchedPlaces) { place ->
                            PlaceItem(
                                place = place,
                                summary = enrichedMap[place.placeId],
                                apiKey = apiKey,
                                onClick = { selectedPlace = place }
                            )
                        }
                    }
                }
            }

            // FULL SCREEN DETAIL OVERLAY
            AnimatedVisibility(
                visible = selectedPlace != null,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                selectedPlace?.let { place ->
                    PlaceDetailScreen(
                        place = place,
                        summary = enrichedMap[place.placeId],
                        apiKey = apiKey,
                        onClose = { selectedPlace = null },
                        onNavigate = { 
                            val uri = android.net.Uri.parse("google.navigation:q=${place.lat},${place.lng}")
                            val mapIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                            mapIntent.setPackage("com.google.android.apps.maps")
                            context.startActivity(mapIntent)
                        }
                    )
                }
            }
        }

        // Dialogs
        if (showOptions) {
            AlertDialog(
                onDismissRequest = { showOptions = false },
                title = { Text("Settings") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { 
                            showOptions = false
                            handleEnrichmentClick(context, scope, fetchedPlaces, cityName, { enrichedMap = it }) { statusText = it } 
                        }, modifier = Modifier.fillMaxWidth()) { Text("Manual Wiki Enrich") }
                        
                        OutlinedButton(onClick = { 
                            showOptions = false
                            handleStopBackgroundWork(context) { statusText = it } 
                        }, modifier = Modifier.fillMaxWidth()) { Text("Stop Background Work") }

                        OutlinedButton(onClick = { 
                            showOptions = false
                            handleClearCache(context, scope) { fetchedPlaces = emptyList(); statusText = it } 
                        }, modifier = Modifier.fillMaxWidth()) { Text("Clear All Caches") }

                        TextButton(onClick = { 
                            showOptions = false
                            logsText = DebugLogger.getLogs(context)
                            showLogs = true 
                        }, modifier = Modifier.fillMaxWidth()) { Text("View Debug Logs") }
                    }
                },
                confirmButton = { TextButton(onClick = { showOptions = false }) { Text("Dismiss") } }
            )
        }

        if (showLogs) {
            AlertDialog(
                onDismissRequest = { showLogs = false },
                title = { Text("Device Logs") },
                text = {
                    Column {
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { DebugLogger.clearLogs(context); logsText = "Logs cleared." }) { Text("Clear") }
                        }
                        Box(modifier = Modifier.height(400.dp).verticalScroll(rememberScrollState())) {
                            Text(logsText, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showLogs = false }) { Text("Close") } }
            )
        }
    }
}

@Composable
fun PlaceDetailScreen(
    place: NearbyPlace,
    summary: String?,
    apiKey: String,
    onClose: () -> Unit,
    onNavigate: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // Hero Image Header
            Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                if (place.photoReference != null) {
                    AsyncImage(
                        model = "https://maps.googleapis.com/maps/api/place/photo?maxwidth=1200&photo_reference=${place.photoReference}&key=$apiKey",
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer))
                }
                
                // Gradient Overlay
                Box(modifier = Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)), startY = 400f)
                ))

                // Title on Image
                Text(
                    text = place.name,
                    style = MaterialTheme.typography.headlineMedium.copy(color = Color.White, fontWeight = FontWeight.Bold),
                    modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)
                )

                // Close Button
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.padding(top = 32.dp, start = 8.dp).align(Alignment.TopStart)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }

            Column(modifier = Modifier.padding(16.dp)) {
                // Info Row
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFFFB300), modifier = Modifier.size(20.dp))
                    Text(" ${place.rating} (${place.userRatingsTotal} reviews)", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    if (place.openNow == true) {
                        Surface(color = Color(0xFFE8F5E9), shape = RoundedCornerShape(16.dp)) {
                            Text("OPEN NOW", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = Color(0xFF2E7D32), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                
                // Wikipedia Content
                Text("THE LORE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                
                if (summary != null) {
                    Text(text = summary, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp))
                } else {
                    Text("No deep lore available for this location yet. Background enrichment might be in progress.", color = MaterialTheme.colorScheme.outline)
                }

                Spacer(Modifier.height(24.dp))
                
                // Location Details
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(place.vicinity, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Spacer(Modifier.height(80.dp)) // Padding for bottom bar
            }
        }

        // Bottom Action Bar
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            tonalElevation = 8.dp,
            shadowElevation = 8.dp
        ) {
            Row(modifier = Modifier.padding(16.dp).navigationBarsPadding()) {
                Button(
                    onClick = onNavigate,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Directions, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("GET DIRECTIONS")
                }
            }
        }
    }
}

@Composable
fun PlaceItem(place: NearbyPlace, summary: String?, apiKey: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column {
            if (place.photoReference != null) {
                AsyncImage(
                    model = "https://maps.googleapis.com/maps/api/place/photo?maxwidth=800&photo_reference=${place.photoReference}&key=$apiKey",
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    contentScale = ContentScale.Crop
                )
            }
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(place.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (summary != null) Icon(Icons.Default.AutoFixHigh, contentDescription = "Enriched", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                }
                Text(place.vicinity, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Text(" ${place.rating}", style = MaterialTheme.typography.labelSmall)
                    if (place.openNow == true) {
                        Spacer(Modifier.width(12.dp))
                        Text("OPEN NOW", style = MaterialTheme.typography.labelSmall, color = Color(0xFF4CAF50))
                    }
                }
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
            val wikiCached = withContext(Dispatchers.IO) { WikipediaService.loadFromCache(context) }
            if (wikiCached != null) {
                onEnrichedSet(wikiCached.enriched.associate { it.placeId to it.wikipediaSummary })
                onStatusChange("Loaded ${cached.size} places (Enriched ✅)")
            } else {
                onStatusChange("Loaded ${cached.size} places from cache!")
            }
            GeofenceManager.registerAll(context, cached, lat, lng)
        }
    }
}

private fun handleClearCache(context: Context, scope: CoroutineScope, onCleared: (String) -> Unit) {
    scope.launch {
        withContext(Dispatchers.IO) {
            LocationService.clearAllCaches(context)
            WorkManager.getInstance(context).cancelAllWork()
        }
        onCleared("Caches cleared.")
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        launcher.launch(permissions.toTypedArray())
        return
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocation(context)) {
        bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        return
    }

    onStatusChange("Checking cache...")
    scope.launch {
        val location = LocationService.getCurrentLocation(context) ?: run { onStatusChange("No location."); return@launch }
        val (lat, lng) = location
        onCityDetected(LocationService.getCityName(lat, lng, context))

        val cached = withContext(Dispatchers.IO) { LocationService.loadPlacesFromJson(context, lat, lng) }
        if (cached != null) {
            onPlacesUpdated(cached)
            val wikiCached = withContext(Dispatchers.IO) { WikipediaService.loadFromCache(context) }
            if (wikiCached != null) onEnrichedUpdated(wikiCached.enriched.associate { it.placeId to it.wikipediaSummary })
            onStatusChange("Loaded from cache!")
            GeofenceManager.registerAll(context, cached, lat, lng)
        } else {
            onStatusChange("Fetching attractions...")
            val places = LocationService.getNearbyAttractions(context, lat, lng, apiKey)
            onPlacesUpdated(places)
            withContext(Dispatchers.IO) { LocationService.savePlacesToJson(context, places, lat, lng, LocationService.getCityName(lat, lng, context)) }
            WikipediaWorker.schedule(context)
            onStatusChange("Found ${places.size} POIs. Enriching...")
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
    if (fetchedPlaces.isEmpty()) { onStatusChange("Fetch locations first!"); return }
    scope.launch {
        onStatusChange("Enriching Wikipedia data...")
        val result = WikipediaService.enrichAndFilter(fetchedPlaces, cityName)
        withContext(Dispatchers.IO) { WikipediaService.saveToCache(context, result) }
        onEnrichedUpdated(result.enriched.associate { it.placeId to it.wikipediaSummary })
        val location = LocationService.getCurrentLocation(context)
        if (location != null) GeofenceManager.registerAll(context, fetchedPlaces, location.first, location.second)
        onStatusChange("Lore Enriched! POIs updated.")
    }
}

private fun handleStopBackgroundWork(context: Context, onStatusChange: (String) -> Unit) {
    WikipediaWorker.cancel(context)
    WorkManager.getInstance(context).cancelAllWork()
    onStatusChange("Work stopped.")
}

private fun hasBasicLocation(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
private fun hasBackgroundLocation(context: Context) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED else true
