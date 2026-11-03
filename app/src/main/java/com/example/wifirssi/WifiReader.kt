package com.example.wifirssi

import android.content.Context
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build

/** One reading of the access point the device is currently associated with. */
data class WifiSample(
    val timestampMs: Long,
    val connected: Boolean,
    val rssi: Int,
    val ssid: String?,
    val bssid: String?,
    val frequencyMhz: Int,
    val linkSpeedMbps: Int,
    val txLinkSpeedMbps: Int?,
    val rxLinkSpeedMbps: Int?,
    val wifiStandard: String?,
    val level: Int,
    val maxLevel: Int,
) {
    val channel: Int? get() = channelFromFrequency(frequencyMhz)
    val band: String?
        get() = when (frequencyMhz) {
            in 2400..2500 -> "2.4 GHz"
            in 4900..5899 -> "5 GHz"
            in 5925..7125 -> "6 GHz"
            else -> null
        }

    /** Rough 0..100 % quality mapping: -100 dBm -> 0 %, -50 dBm -> 100 %. */
    val qualityPercent: Int get() = (2 * (rssi + 100)).coerceIn(0, 100)
}

class WifiReader(context: Context) {
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    val isWifiEnabled: Boolean get() = wifiManager.isWifiEnabled

    /**
     * Reads the current connection info. [WifiManager.getConnectionInfo] is deprecated in API 31
     * in favour of NetworkCallback, but it remains the simplest way to poll a fresh RSSI value and
     * still works on current Android releases.
     */
    @Suppress("DEPRECATION")
    fun read(): WifiSample {
        val info: WifiInfo? = wifiManager.connectionInfo
        val now = System.currentTimeMillis()
        val rssi = info?.rssi ?: INVALID_RSSI
        val freq = info?.frequency ?: -1
        val connected = info != null && rssi != INVALID_RSSI && freq > 0

        val maxLevel: Int
        val level: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            maxLevel = wifiManager.maxSignalLevel
            level = if (connected) wifiManager.calculateSignalLevel(rssi) else 0
        } else {
            maxLevel = 4
            level = if (connected) WifiManager.calculateSignalLevel(rssi, maxLevel + 1) else 0
        }

        return WifiSample(
            timestampMs = now,
            connected = connected,
            rssi = rssi,
            ssid = info?.ssid?.let(::cleanSsid),
            bssid = info?.bssid?.takeUnless { it == REDACTED_BSSID },
            frequencyMhz = freq,
            linkSpeedMbps = info?.linkSpeed ?: -1,
            txLinkSpeedMbps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info?.txLinkSpeedMbps else null,
            rxLinkSpeedMbps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info?.rxLinkSpeedMbps else null,
            wifiStandard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) info?.let { standardName(it.wifiStandard) } else null,
            level = level,
            maxLevel = maxLevel,
        )
    }

    companion object {
        const val INVALID_RSSI = -127
        private const val REDACTED_BSSID = "02:00:00:00:00:00"
        private const val UNKNOWN_SSID = "<unknown ssid>"

        /** Returns null when Android hides the SSID (no location permission or location off). */
        private fun cleanSsid(raw: String): String? {
            if (raw == UNKNOWN_SSID || raw.isBlank()) return null
            return raw.removeSurrounding("\"")
        }

        private fun standardName(std: Int): String? = when (std) {
            1 -> "802.11a/b/g"   // WIFI_STANDARD_LEGACY
            4 -> "Wi-Fi 4 (n)"
            5 -> "Wi-Fi 5 (ac)"
            6 -> "Wi-Fi 6 (ax)"
            7 -> "WiGig (ad)"
            8 -> "Wi-Fi 7 (be)"
            else -> null
        }
    }
}

fun channelFromFrequency(freq: Int): Int? = when {
    freq == 2484 -> 14
    freq in 2412..2472 -> (freq - 2407) / 5
    freq in 4910..5895 -> (freq - 5000) / 5
    freq == 5935 -> 2
    freq in 5955..7115 -> (freq - 5950) / 5
    else -> null
}
