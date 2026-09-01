package com.ericlowry.dnstoggle.data

import android.net.NetworkCapabilities

/**
 * Represents the current connectivity state of the device.
 */
data class CurrentNetwork(
	val ssid: String? = null,
	val bssid: String? = null,
	val isVpnActive: Boolean = false,
	val isValidated: Boolean = false,
	val hasInternet: Boolean = false,
	val wifiCapabilities: NetworkCapabilities? = null
)
