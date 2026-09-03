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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize OSMDroid Configuration
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

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
    var isMapView by remember { mutableStateOf(false) }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Attractions", "History", "Culture")

    var cityLore by remember { mutableStateOf<CityLore?>(null) }
    var isLoadingCityLore by remember { mutableStateOf(false) }
    var cityLoreError by remember { mutableStateOf<String?>(null) }
    var loreRefreshTrigger by remember { mutableIntStateOf(0) }

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

    LaunchedEffect(cityName, loreRefreshTrigger) {
        val city = cityName
        if (city != null) {
            DebugLogger.log(context, "Lore LaunchedEffect triggered for: $city")
            isLoadingCityLore = true
            cityLoreError = null
            try {
                val cached = withContext(Dispatchers.IO) { CityLoreService.loadFromCache(context, city) }
                if (cached != null) {
                    cityLore = cached
                    isLoadingCityLore = false
                    statusText = "Lore for $city loaded from cache!"
                } else {
                    statusText = "Calling Gemini for $city lore..."
                    val geminiApiKey = BuildConfig.GEMINI_API_KEY
                    val result = withContext(Dispatchers.IO) {
                        CityLoreService.fetchAndCacheCityLore(context, city, geminiApiKey)
                    }
                    cityLore = result
                    isLoadingCityLore = false
                    statusText = "Lore for $city loaded from Gemini!"
                }
            } catch (e: Exception) {
                DebugLogger.log(context, "Lore Load Failed: ${e.message}")
                cityLoreError = "Failed to load city lore: ${e.localizedMessage}"
                isLoadingCityLore = false
                statusText = "Gemini load failed: ${e.localizedMessage}"
            }
        } else {
            cityLore = null
        }
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
                        IconButton(onClick = { isMapView = !isMapView }) {
                            Icon(
                                if (isMapView) Icons.Default.List else Icons.Default.Map,
                                contentDescription = "Toggle View"
                            )
                        }
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
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f)
                            )
                            val isRunning = isLoadingCityLore || 
                                            statusText.contains("fetching", ignoreCase = true) || 
                                            statusText.contains("checking", ignoreCase = true) || 
                                            statusText.contains("enriching", ignoreCase = true)
                            if (isRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        val isRunning = isLoadingCityLore || 
                                        statusText.contains("fetching", ignoreCase = true) || 
                                        statusText.contains("checking", ignoreCase = true) || 
                                        statusText.contains("enriching", ignoreCase = true)
                        if (isRunning) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        }
                    }
                }

                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) },
                            icon = {
                                when (index) {
                                    0 -> Icon(Icons.Default.Place, contentDescription = null)
                                    1 -> Icon(Icons.Default.History, contentDescription = null)
                                    2 -> Icon(Icons.Default.Palette, contentDescription = null)
                                }
                            }
                        )
                    }
                }

                when (selectedTab) {
                    0 -> {
                        if (fetchedPlaces.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No attractions loaded", color = MaterialTheme.colorScheme.outline)
                            }
                        } else if (isMapView) {
                            OSMMapView(
                                places = fetchedPlaces,
                                enrichedMap = enrichedMap,
                                onMarkerClick = { place -> selectedPlace = place }
                            )
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
                    1 -> {
                        HistoryTabContent(
                            cityName = cityName,
                            cityLore = cityLore,
                            isLoading = isLoadingCityLore,
                            error = cityLoreError,
                            onRetry = {
                                val city = cityName
                                if (city != null) {
                                    scope.launch {
                                        isLoadingCityLore = true
                                        cityLoreError = null
                                        try {
                                            val geminiApiKey = BuildConfig.GEMINI_API_KEY
                                            val result = withContext(Dispatchers.IO) {
                                                CityLoreService.fetchAndCacheCityLore(context, city, geminiApiKey)
                                            }
                                            cityLore = result
                                        } catch (e: Exception) {
                                            cityLoreError = e.localizedMessage
                                        } finally {
                                            isLoadingCityLore = false
                                        }
                                    }
                                }
                            }
                        )
                    }
                    2 -> {
                        CultureTabContent(
                            cityName = cityName,
                            cityLore = cityLore,
                            isLoading = isLoadingCityLore,
                            error = cityLoreError,
                            onRetry = {
                                val city = cityName
                                if (city != null) {
                                    scope.launch {
                                        isLoadingCityLore = true
                                        cityLoreError = null
                                        try {
                                            val geminiApiKey = BuildConfig.GEMINI_API_KEY
                                            val result = withContext(Dispatchers.IO) {
                                                CityLoreService.fetchAndCacheCityLore(context, city, geminiApiKey)
                                            }
                                            cityLore = result
                                        } catch (e: Exception) {
                                            cityLoreError = e.localizedMessage
                                        } finally {
                                            isLoadingCityLore = false
                                        }
                                    }
                                }
                            }
                        )
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
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    CityLoreService.clearCaches(context)
                                }
                                cityLore = null
                                loreRefreshTrigger++
                                statusText = "City lore cache cleared."
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Clear City Lore Cache") }

                        OutlinedButton(onClick = { 
                            showOptions = false
                            handleClearCache(context, scope) { 
                                fetchedPlaces = emptyList()
                                cityLore = null
                                cityName = null
                                statusText = it 
                            } 
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
fun OSMMapView(
    places: List<NearbyPlace>,
    enrichedMap: Map<String, String>,
    onMarkerClick: (NearbyPlace) -> Unit
) {
    AndroidView(
        factory = { context ->
            MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                
                val mapController = controller
                mapController.setZoom(14.0)
                
                if (places.isNotEmpty()) {
                    val startPoint = GeoPoint(places[0].lat, places[0].lng)
                    mapController.setCenter(startPoint)
                }

                places.forEach { place ->
                    val marker = Marker(this)
                    marker.position = GeoPoint(place.lat, place.lng)
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    marker.title = place.name
                    marker.snippet = place.vicinity
                    marker.setOnMarkerClickListener { _, _ ->
                        onMarkerClick(place)
                        true
                    }
                    overlays.add(marker)
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { mapView ->
            // Update logic if needed when 'places' changes
        }
    )
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
            CityLoreService.clearCaches(context)
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

// City History & Culture Composables and Helpers

sealed class LoreBlock {
    data class Header(val text: String, val level: Int) : LoreBlock()
    data class Paragraph(val text: String) : LoreBlock()
    data class BulletItem(val text: String) : LoreBlock()
}

fun parseLoreText(text: String): List<LoreBlock> {
    val lines = text.split("\n")
    val blocks = mutableListOf<LoreBlock>()
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        
        if (trimmed.startsWith("###")) {
            blocks.add(LoreBlock.Header(trimmed.removePrefix("###").trim(), 3))
        } else if (trimmed.startsWith("##")) {
            blocks.add(LoreBlock.Header(trimmed.removePrefix("##").trim(), 2))
        } else if (trimmed.startsWith("#")) {
            blocks.add(LoreBlock.Header(trimmed.removePrefix("#").trim(), 1))
        } else if (trimmed.startsWith("-") || trimmed.startsWith("*")) {
            val content = trimmed.substring(1).trim()
            blocks.add(LoreBlock.BulletItem(content))
        } else {
            blocks.add(LoreBlock.Paragraph(trimmed))
        }
    }
    return blocks
}

fun parseBoldText(text: String) = buildAnnotatedString {
    val parts = text.split("**")
    parts.forEachIndexed { index, part ->
        if (index % 2 == 1) {
            withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                append(part)
            }
        } else {
            append(part)
        }
    }
}

@Composable
fun RenderLoreBlocks(blocks: List<LoreBlock>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        blocks.forEach { block ->
            when (block) {
                is LoreBlock.Header -> {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = block.text,
                        style = when (block.level) {
                            1 -> MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                            2 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            else -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                        }
                    )
                }
                is LoreBlock.Paragraph -> {
                    Text(
                        text = parseBoldText(block.text),
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                is LoreBlock.BulletItem -> {
                    Row(
                        modifier = Modifier.padding(start = 8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text("•  ", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
                        Text(
                            text = parseBoldText(block.text),
                            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyLoreState(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun LoadingLoreState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
fun ErrorLoreState(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.error
            )
            Button(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}

@Composable
fun HistoryTabContent(
    cityName: String?,
    cityLore: CityLore?,
    isLoading: Boolean,
    error: String?,
    onRetry: () -> Unit
) {
    if (cityName == null) {
        EmptyLoreState("Please detect your location or fetch nearby attractions to reveal local history!")
        return
    }
    if (isLoading) {
        LoadingLoreState("Channeling local tour guides for $cityName's history...")
        return
    }
    if (error != null) {
        ErrorLoreState(error, onRetry)
        return
    }
    val historyText = cityLore?.historyGemini
    if (historyText == null) {
        EmptyLoreState("No history loaded. Click 'Fetch Nearby' to get history for $cityName.")
        return
    }

    val blocks = remember(historyText) { parseLoreText(historyText) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // City Header Card
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "$cityName Chronicles",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "A fun journey through time ✨",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
        }

        RenderLoreBlocks(blocks)
        Spacer(modifier = Modifier.height(100.dp)) // padding for floating action button
    }
}

@Composable
fun CultureTabContent(
    cityName: String?,
    cityLore: CityLore?,
    isLoading: Boolean,
    error: String?,
    onRetry: () -> Unit
) {
    if (cityName == null) {
        EmptyLoreState("Please detect your location or fetch nearby attractions to reveal local culture!")
        return
    }
    if (isLoading) {
        LoadingLoreState("Discovering local traditions & secret cuisines of $cityName...")
        return
    }
    if (error != null) {
        ErrorLoreState(error, onRetry)
        return
    }
    val cultureText = cityLore?.cultureGemini
    if (cultureText == null) {
        EmptyLoreState("No culture lore loaded. Click 'Fetch Nearby' to get culture details for $cityName.")
        return
    }

    val blocks = remember(cultureText) { parseLoreText(cultureText) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // City Header Card
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "$cityName's Soul",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = "Traditions, quirkiness, and local flavors 🍲",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                )
            }
        }

        RenderLoreBlocks(blocks)
        Spacer(modifier = Modifier.height(100.dp)) // padding for floating action button
    }
}
