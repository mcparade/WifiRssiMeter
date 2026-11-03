package com.example.wifirssi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wifirssi.ui.AppTheme
import com.example.wifirssi.ui.RssiChart
import com.example.wifirssi.ui.colorForRssi
import com.example.wifirssi.ui.labelForRssi
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { RssiScreen() } }
    }
}

private fun hasLocationPermission(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun isLocationOn(ctx: Context) =
    LocationManagerCompat.isLocationEnabled(ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RssiScreen(vm: RssiViewModel = viewModel()) {
    val ctx = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var permGranted by remember { mutableStateOf(hasLocationPermission(ctx)) }
    var locationOn by remember { mutableStateOf(isLocationOn(ctx)) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permGranted = hasLocationPermission(ctx) }

    LaunchedEffect(Unit) {
        if (!permGranted) {
            permLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }
    // Re-check when returning from system settings.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permGranted = hasLocationPermission(ctx)
        locationOn = isLocationOn(ctx)
    }

    Scaffold(topBar = { TopAppBar(title = { Text("WiFi RSSI Meter") }) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!permGranted || !locationOn) {
                PermissionBanner(
                    permGranted = permGranted,
                    onGrant = {
                        permLauncher.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                        )
                    },
                    onOpenLocation = { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                )
            }

            val cur = state.current
            when {
                !state.wifiEnabled -> StatusCard("Wi-Fi is off", "Turn on Wi-Fi and connect to an access point.") {
                    ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                }
                cur != null && !cur.connected -> StatusCard("Not connected", "Connect to a Wi-Fi access point to start measuring.") {
                    ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                }
                cur != null -> RssiHero(cur)
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("History (last ${RssiViewModel.CHART_POINTS} samples)", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    RssiChart(state.history, RssiViewModel.CHART_POINTS)
                }
            }

            StatsCard(state)

            if (cur != null && cur.connected) DetailsCard(cur)

            ControlsCard(
                state = state,
                onToggle = { if (state.running) vm.stop() else vm.start() },
                onReset = vm::reset,
                onInterval = vm::setInterval,
                onExport = {
                    scope.launch {
                        val uri = vm.exportCsv()
                        if (uri == null) {
                            Toast.makeText(ctx, "No samples to export yet", Toast.LENGTH_SHORT).show()
                        } else {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            ctx.startActivity(Intent.createChooser(send, "Export RSSI log"))
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun PermissionBanner(permGranted: Boolean, onGrant: () -> Unit, onOpenLocation: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (!permGranted) "Location permission needed for SSID/BSSID"
                else "Location services are off",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "Android hides the network name and AP MAC address unless location access is granted " +
                    "and location is on. RSSI is still measured without it.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = if (!permGranted) onGrant else onOpenLocation) {
                Text(if (!permGranted) "Grant permission" else "Open location settings")
            }
        }
    }
}

@Composable
private fun StatusCard(title: String, body: String, onAction: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onAction) { Text("Wi-Fi settings") }
        }
    }
}

@Composable
private fun RssiHero(s: WifiSample) {
    val color = colorForRssi(s.rssi)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(s.ssid ?: "Hidden SSID", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${s.rssi}", fontSize = 72.sp, fontWeight = FontWeight.Bold, color = color)
                Text(" dBm", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 14.dp))
            }
            Text("${labelForRssi(s.rssi)} · ${s.qualityPercent}%", color = color, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(12.dp))
            SignalBars(level = s.level, maxLevel = s.maxLevel, color = color)
        }
    }
}

@Composable
private fun SignalBars(level: Int, maxLevel: Int, color: androidx.compose.ui.graphics.Color) {
    val inactive = MaterialTheme.colorScheme.surfaceVariant
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 1..maxLevel) {
            Box(
                Modifier
                    .width(14.dp)
                    .height((10 + i * 8).dp)
                    .background(if (i <= level) color else inactive, RoundedCornerShape(3.dp))
            )
        }
    }
}

@Composable
private fun StatsCard(state: UiState) {
    val st = state.stats
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat("Min", if (st.count > 0) "${st.min}" else "–")
            Stat("Avg", if (st.count > 0) String.format(Locale.US, "%.1f", st.average) else "–")
            Stat("Max", if (st.count > 0) "${st.max}" else "–")
            Stat("Samples", "${st.count}")
            Stat("Roams", "${state.roamCount}")
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun DetailsCard(s: WifiSample) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Access point", style = MaterialTheme.typography.titleSmall)
            Detail("BSSID", s.bssid ?: "Hidden (needs location)")
            Detail("Frequency", "${s.frequencyMhz} MHz" + (s.band?.let { " ($it)" } ?: ""))
            Detail("Channel", s.channel?.toString() ?: "–")
            Detail("Link speed", if (s.linkSpeedMbps > 0) "${s.linkSpeedMbps} Mbps" else "–")
            if (s.txLinkSpeedMbps != null && s.txLinkSpeedMbps > 0) Detail("Tx / Rx", "${s.txLinkSpeedMbps} / ${s.rxLinkSpeedMbps ?: "–"} Mbps")
            s.wifiStandard?.let { Detail("Standard", it) }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlsCard(
    state: UiState,
    onToggle: () -> Unit,
    onReset: () -> Unit,
    onInterval: (Long) -> Unit,
    onExport: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sample interval", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(500L to "0.5 s", 1000L to "1 s", 2000L to "2 s", 5000L to "5 s").forEach { (ms, label) ->
                    FilterChip(
                        selected = state.intervalMs == ms,
                        onClick = { onInterval(ms) },
                        label = { Text(label) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onToggle) { Text(if (state.running) "Pause" else "Resume") }
                OutlinedButton(onClick = onReset) { Text("Reset") }
                OutlinedButton(onClick = onExport) { Text("Export CSV") }
            }
        }
    }
}
