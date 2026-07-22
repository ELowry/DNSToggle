package com.ericlowry.dnstoggle.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.ericlowry.dnstoggle.DnsToggleApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket

fun String.stripSsidQuotes(): String {
	return this.removePrefix("\"").removeSuffix("\"")
}

object NetworkUtils {

	/**
	 * Fetches the SSID of the currently connected Wi-Fi network.
	 * Returns null if not connected to Wi-Fi or if SSID is unknown/redacted.
	 */
	fun getCurrentWifiSsid(context: Context): String? {
		val application = context.applicationContext as? DnsToggleApplication
		application?.detectedSsid?.let { return it }

		var ssid: String? = null

		val connectivityManager =
			context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
		val activeNetwork = connectivityManager.activeNetwork
		val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)

		if (networkCapabilities != null && (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
					networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
		) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				val transportInfo = networkCapabilities.transportInfo as? WifiInfo
				ssid = transportInfo?.ssid?.stripSsidQuotes()
			}
		}

		if (ssid == null || ssid == "<unknown ssid>" || ssid.isEmpty()) {
			val wifiManager =
				context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

			@Suppress("DEPRECATION")
			val wifiInfo = wifiManager.connectionInfo
			ssid = wifiInfo?.ssid?.stripSsidQuotes()
		}

		return if (ssid == "<unknown ssid>" || ssid.isNullOrEmpty()) null else ssid
	}

	suspend fun isHostReachable(host: String, port: Int, timeoutMs: Int = 3000): Boolean =
		withContext(Dispatchers.IO) {
			// InetSocketAddress resolves the hostname eagerly, before connect()'s own
			// timeout applies, so a stuck resolver (e.g. a broken private DNS server)
			// can hang well past timeoutMs unless the whole thing is bounded here too.
			withTimeoutOrNull(timeoutMs.toLong()) {
				try {
					Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
					true
				} catch (e: Exception) {
					false
				}
			} ?: false
		}

	fun isValidDnsHostname(hostname: String): Boolean {
		val hostnameRegex =
			"^(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]*[a-zA-Z0-9])\\.)*([A-Za-z0-9]|[A-Za-z0-9][A-Za-z0-9\\-]*[A-Za-z0-9])$".toRegex()
		return (hostname.length <= 253) && hostnameRegex.matches(hostname)
	}
}
