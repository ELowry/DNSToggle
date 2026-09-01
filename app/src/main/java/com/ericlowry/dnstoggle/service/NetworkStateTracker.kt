package com.ericlowry.dnstoggle.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.ericlowry.dnstoggle.data.CurrentNetwork
import com.ericlowry.dnstoggle.util.NetworkUtils
import com.ericlowry.dnstoggle.util.stripSsidQuotes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Encapsulates ConnectivityManager.NetworkCallback to track and emit clean network state changes.
 */
class NetworkStateTracker(private val context: Context) {

	private val connectivityManager =
		context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
	private val activeNetworks = ConcurrentHashMap<Network, NetworkCapabilities>()
	private var networkCallback: NetworkCallback? = null

	private val _currentNetwork = MutableStateFlow(CurrentNetwork())
	val currentNetwork: StateFlow<CurrentNetwork> = _currentNetwork.asStateFlow()

	/**
	 * Returns a snapshot of currently active networks and their capabilities.
	 */
	fun getActiveNetworks(): Map<Network, NetworkCapabilities> = activeNetworks.toMap()

	companion object {
		private const val TAG = "NetworkStateTracker"
	}

	/**
	 * Registers the network callback to start tracking connectivity changes.
	 */
	fun startTracking() {
		if (networkCallback != null) {
			return
		}

		val networkRequest = NetworkRequest.Builder()
			.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
			.addTransportType(NetworkCapabilities.TRANSPORT_VPN)
			.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
			.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
			.build()

		val hasLocationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
		} else {
			context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
		}

		networkCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			val flags = if (hasLocationPermission) NetworkCallback.FLAG_INCLUDE_LOCATION_INFO else 0
			object : NetworkCallback(flags) {
				override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
					val oldCaps = activeNetworks[network]
					if (hasMeaningfulChange(oldCaps, caps)) {
						activeNetworks[network] = caps
						updateState()
					}
				}

				override fun onLost(network: Network) {
					if (activeNetworks.containsKey(network)) {
						activeNetworks.remove(network)
						updateState()
					}
				}
			}
		} else {
			object : NetworkCallback() {
				override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
					val oldCaps = activeNetworks[network]
					if (hasMeaningfulChange(oldCaps, caps)) {
						activeNetworks[network] = caps
						updateState()
					}
				}

				override fun onLost(network: Network) {
					if (activeNetworks.containsKey(network)) {
						activeNetworks.remove(network)
						updateState()
					}
				}
			}
		}

		try {
			connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
		} catch (e: Exception) {
			Log.e(TAG, "Failed to register network callback", e)
		}
	}

	/**
	 * Unregisters the network callback.
	 */
	fun stopTracking() {
		networkCallback?.let {
			try {
				connectivityManager.unregisterNetworkCallback(it)
			} catch (e: Exception) {
				Log.e(TAG, "Failed to unregister network callback", e)
			}
		}
		networkCallback = null
	}

	/**
	 * Internal mapping for noisy connectivity updates.
	 */
	private fun hasMeaningfulChange(
		oldCaps: NetworkCapabilities?,
		newCaps: NetworkCapabilities
	): Boolean {
		if (oldCaps == null) {
			return true
		}

		if (oldCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) != newCaps.hasCapability(
				NetworkCapabilities.NET_CAPABILITY_VALIDATED
			)
		) {
			return true
		}

		if (oldCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != newCaps.hasTransport(
				NetworkCapabilities.TRANSPORT_VPN
			)
		) {
			return true
		}
		if (oldCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != newCaps.hasTransport(
				NetworkCapabilities.TRANSPORT_WIFI
			)
		) {
			return true
		}

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			val oldInfo = oldCaps.transportInfo as? WifiInfo
			val newInfo = newCaps.transportInfo as? WifiInfo
			if (oldInfo?.ssid != newInfo?.ssid) {
				return true
			}
			if (oldInfo?.bssid != newInfo?.bssid) {
				return true
			}
		}

		return false
	}

	private fun updateState() {
		val allCaps = activeNetworks.values
		val isVpnActive = allCaps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
		val wifiEntry =
			activeNetworks.entries.find { it.value.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
		val wifiCaps = wifiEntry?.value

		val hasLocationPermission =
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
			} else {
				context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
			}

		val wifiInfo = if (hasLocationPermission) {
			wifiCaps?.let { caps ->
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
					caps.transportInfo as? WifiInfo
				} else {
					val wm =
						context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
					@Suppress("DEPRECATION") wm.connectionInfo
				}
			}
		} else {
			null
		}

		val currentSsid = wifiInfo?.ssid?.stripSsidQuotes()
		val currentBssid = wifiInfo?.bssid
		val isValidated =
			wifiCaps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
		val hasInternet =
			wifiCaps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

		val ssid = if (NetworkUtils.isValidSsid(wifiInfo?.ssid)) {
			currentSsid
		} else {
			null
		}

		_currentNetwork.value = CurrentNetwork(
			ssid = ssid,
			bssid = currentBssid,
			isVpnActive = isVpnActive,
			isValidated = isValidated,
			hasInternet = hasInternet,
			wifiCapabilities = wifiCaps
		)
	}
}
