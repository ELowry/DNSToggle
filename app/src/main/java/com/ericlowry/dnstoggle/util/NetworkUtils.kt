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

fun String.stripSsidQuotes(): String {
	return this.removePrefix("\"").removeSuffix("\"")
}

object NetworkUtils {

	fun getCurrentWifiSsid(context: Context): String? {
		val application = context.applicationContext as? DnsToggleApplication
		application?.detectedSsid?.let { return it }

		return getCurrentWifiInfo(context)?.ssid?.stripSsidQuotes()?.let {
			if (it == "<unknown ssid>" || it.isEmpty()) null else it
		}
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

	suspend fun isHostReachable(host: String, port: Int, timeoutMs: Int = 3000): Boolean =
		withTimeoutOrNull(timeoutMs.toLong().milliseconds) {
			try {
				runInterruptible(Dispatchers.IO) {
					Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
				}
				true
			} catch (_: Exception) {
				false
			}
		} ?: false

	/**
	 * FQDN validation: Max 253 chars, alphanumeric/hyphen segments, no leading/trailing hyphens.
	 */
	fun isValidDnsHostname(hostname: String): Boolean {
		val hostnameRegex =
			"^(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]*[a-zA-Z0-9])\\.)*([A-Za-z0-9]|[A-Za-z0-9][A-Za-z0-9\\-]*[A-Za-z0-9])$".toRegex()
		return (hostname.length <= 253) && hostnameRegex.matches(hostname)
	}
}
