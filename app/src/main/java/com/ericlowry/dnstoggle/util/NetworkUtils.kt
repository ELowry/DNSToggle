package com.ericlowry.dnstoggle.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.ericlowry.dnstoggle.DnsToggleApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.time.Duration.Companion.milliseconds

/**
 * Utility extension to strip double quotes from SSID strings.
 */
fun String.stripSsidQuotes(): String {
	return this.removePrefix("\"").removeSuffix("\"")
}

/**
 * Utility class for network-related operations, including SSID extraction and connectivity testing.
 */
object NetworkUtils {

	/**
	 * Returns true if the given SSID is not null, not empty, and not the placeholder "<unknown ssid>".
	 */
	fun isValidSsid(ssid: String?): Boolean {
		val stripped = ssid?.stripSsidQuotes()
		return !stripped.isNullOrEmpty() && stripped != "<unknown ssid>"
	}

	/**
	 * Extracts the current Wi-Fi SSID, preferring the detected SSID from the application state.
	 *
	 * @param context The application or activity context.
	 * @return The sanitized SSID string, or null if not connected to Wi-Fi.
	 */
	fun getCurrentWifiSsid(context: Context): String? {
		val application = context.applicationContext as? DnsToggleApplication
		application?.detectedSsid?.let {
			return it
		}

		val rawSsid = getCurrentWifiInfo(context)?.ssid
		return if (isValidSsid(rawSsid)) {
			rawSsid?.stripSsidQuotes()
		} else {
			null
		}
	}

	/**
	 * Performs a TCP reachability test on a given host and port.
	 *
	 * @param host The hostname or IP address to test.
	 * @param port The port number.
	 * @param timeoutMs The timeout for the connection attempt.
	 * @return True if the connection was successful within the timeout.
	 */
	suspend fun isHostReachable(host: String, port: Int, timeoutMs: Int = 3000): Boolean =
		withTimeoutOrNull(timeoutMs.toLong().milliseconds) {
			try {
				runInterruptible(Dispatchers.IO) {
					Socket().use {
						it.connect(InetSocketAddress(host, port), timeoutMs)
					}
				}
				true
			} catch (_: Exception) {
				false
			}
		} ?: false

	/**
	 * Validates if a string is a syntactically valid FQDN for a DNS hostname.
	 * Max 253 chars, alphanumeric/hyphen segments, no leading/trailing hyphens.
	 */
	fun isValidDnsHostname(hostname: String): Boolean {
		val hostnameRegex =
			"^(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]*[a-zA-Z0-9])\\.)*([A-Za-z0-9]|[A-Za-z0-9][a-zA-Z0-9\\-]*[A-Za-z0-9])$".toRegex()
		return (hostname.length <= 253) && hostnameRegex.matches(hostname)
	}

	private fun getCurrentWifiInfo(context: Context): WifiInfo? {
		var wifiInfo: WifiInfo? = null

		val connectivityManager =
			context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
		val activeNetwork = connectivityManager.activeNetwork
		val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)

		if (networkCapabilities != null && (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
					networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
		) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				wifiInfo = networkCapabilities.transportInfo as? WifiInfo
			}
		}

		// Fallback to legacy API for Android 10+, which blocks WifiInfo access via ConnectivityManager
		if (wifiInfo == null) {
			val wifiManager =
				context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
			@Suppress("DEPRECATION")
			wifiInfo = wifiManager.connectionInfo
		}

		return wifiInfo
	}
}
