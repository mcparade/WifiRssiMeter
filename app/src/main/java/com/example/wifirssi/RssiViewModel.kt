package com.example.wifirssi

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RssiStats(val count: Int = 0, val min: Int = 0, val max: Int = 0, val sum: Long = 0) {
    val average: Double get() = if (count == 0) 0.0 else sum.toDouble() / count

    fun add(rssi: Int) = if (count == 0) {
        RssiStats(1, rssi, rssi, rssi.toLong())
    } else {
        RssiStats(count + 1, minOf(min, rssi), maxOf(max, rssi), sum + rssi)
    }
}

data class UiState(
    val current: WifiSample? = null,
    val wifiEnabled: Boolean = true,
    val running: Boolean = true,
    val intervalMs: Long = 1000L,
    /** Recent connected readings, newest last, for the chart. */
    val history: List<WifiSample> = emptyList(),
    val stats: RssiStats = RssiStats(),
    val roamCount: Int = 0,
)

class RssiViewModel(app: Application) : AndroidViewModel(app) {
    private val reader = WifiReader(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Full session log for CSV export (bounded so a forgotten session can't exhaust memory). */
    private val log = ArrayDeque<WifiSample>()
    private var pollJob: Job? = null

    init {
        start()
    }

    fun start() {
        if (pollJob?.isActive == true) return
        _state.update { it.copy(running = true) }
        pollJob = viewModelScope.launch {
            while (isActive) {
                sampleOnce()
                delay(_state.value.intervalMs)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        _state.update { it.copy(running = false) }
    }

    fun setInterval(ms: Long) = _state.update { it.copy(intervalMs = ms) }

    fun reset() {
        log.clear()
        _state.update { it.copy(history = emptyList(), stats = RssiStats(), roamCount = 0) }
    }

    private fun sampleOnce() {
        val sample = reader.read()
        val enabled = reader.isWifiEnabled
        if (sample.connected) {
            log.addLast(sample)
            if (log.size > MAX_LOG) log.removeFirst()
        }
        _state.update { s ->
            if (!sample.connected) {
                s.copy(current = sample, wifiEnabled = enabled)
            } else {
                val prevBssid = s.history.lastOrNull()?.bssid
                val roamed = prevBssid != null && sample.bssid != null && prevBssid != sample.bssid
                s.copy(
                    current = sample,
                    wifiEnabled = enabled,
                    history = (s.history + sample).takeLast(CHART_POINTS),
                    stats = s.stats.add(sample.rssi),
                    roamCount = s.roamCount + if (roamed) 1 else 0,
                )
            }
        }
    }

    /** Writes the session log to a CSV in the cache dir and returns a shareable content URI. */
    suspend fun exportCsv(): Uri? {
        // The log is only touched on the main thread; snapshot it before moving to IO.
        val snapshot = log.toList()
        if (snapshot.isEmpty()) return null
        return withContext(Dispatchers.IO) { writeCsv(snapshot) }
    }

    private fun writeCsv(snapshot: List<WifiSample>): Uri {
        val ctx = getApplication<Application>()
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "rssi_$stamp.csv")
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
        file.bufferedWriter().use { w ->
            w.appendLine("timestamp,rssi_dbm,ssid,bssid,frequency_mhz,channel,link_speed_mbps,tx_mbps,rx_mbps")
            for (s in snapshot) {
                w.appendLine(
                    listOf(
                        iso.format(Date(s.timestampMs)),
                        s.rssi,
                        s.ssid?.let { "\"" + it.replace("\"", "\"\"") + "\"" } ?: "",
                        s.bssid ?: "",
                        s.frequencyMhz,
                        s.channel ?: "",
                        s.linkSpeedMbps,
                        s.txLinkSpeedMbps ?: "",
                        s.rxLinkSpeedMbps ?: "",
                    ).joinToString(",")
                )
            }
        }
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
    }

    companion object {
        const val CHART_POINTS = 120
        const val MAX_LOG = 36_000
    }
}
