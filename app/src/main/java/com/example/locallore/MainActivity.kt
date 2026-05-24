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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
fun MainScreen(context: android.content.Context, modifier: Modifier = Modifier)  {
    val apiKey = BuildConfig.PLACES_API_KEY
    var statusText by remember { mutableStateOf("Press to find nearby attractions") }
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

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Button(onClick = {
            val hasFineLocation = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasCoarseLocation = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (hasFineLocation && hasCoarseLocation) {
                statusText = "Fetching location..."
                scope.launch {
                    val location = LocationService.getCurrentLocation(context)
                    if (location != null) {
                        val (lat, lng) = location
                        val places = LocationService.getNearbyAttractions(lat, lng, apiKey)
                        statusText = if (places.isNotEmpty())
                            "Found ${places.size} attractions near you!"
                        else
                            "No attractions found."
                        places.forEach { Log.d("LocalLore", "Place: ${it.name}") }
                    } else {
                        statusText = "Could not get location."
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
    }
}