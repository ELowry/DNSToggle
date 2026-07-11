package com.ericlowry.dnstoggle

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build

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

		val wifiManager =
			context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

		@Suppress("DEPRECATION")
		val wifiInfo = wifiManager.connectionInfo
		var ssid = wifiInfo?.ssid?.stripSsidQuotes()

		if (ssid == null || ssid == "<unknown ssid>" || ssid.isEmpty()) {
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
		}

		return if (ssid == "<unknown ssid>" || ssid?.isEmpty() == true) null else ssid
	}

	fun isValidDnsHostname(hostname: String): Boolean {
		val hostnameRegex =
			"^(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]*[a-zA-Z0-9])\\.)*([A-Za-z0-9]|[A-Za-z0-9][A-Za-z0-9\\-]*[A-Za-z0-9])$".toRegex()
		return (hostname.length <= 253) && hostnameRegex.matches(hostname)
	}
}
