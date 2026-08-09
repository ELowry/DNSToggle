package com.ericlowry.dnstoggle.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings.Global
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.ericlowry.dnstoggle.DnsToggleApplication
import com.ericlowry.dnstoggle.R
import com.ericlowry.dnstoggle.data.Constants
import com.ericlowry.dnstoggle.data.repository.NetworkProfileRepository
import com.ericlowry.dnstoggle.data.repository.VpnRepository
import com.ericlowry.dnstoggle.ui.MainActivity
import com.ericlowry.dnstoggle.util.EncryptionManager
import com.ericlowry.dnstoggle.util.NotificationUtils
import com.ericlowry.dnstoggle.util.stripSsidQuotes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class WifiMonitoringService : Service() {

	private lateinit var connectivityManager: ConnectivityManager
	private var networkCallback: NetworkCallback? = null
	private var cachedDnsMode: String? = null
	private val activeNetworks = ConcurrentHashMap<Network, NetworkCapabilities>()
	private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
	private lateinit var watchdogManager: ConnectivityWatchdogManager

	private val dnsSettingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
		override fun onChange(selfChange: Boolean) {
			cachedDnsMode = Global.getString(contentResolver, Constants.SETTINGS_PRIVATE_DNS_MODE)
			TileServiceCompat.requestListeningState(
				this@WifiMonitoringService,
				ComponentName(this@WifiMonitoringService, DnsToggleService::class.java)
			)
		}
	}

	companion object {
		private const val TAG = "WifiMonitoringService"
	}

	private fun getPrefs(): SharedPreferences {
		return (application as DnsToggleApplication).getPrefs()
	}

	private val preferenceChangeListener =
		SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
			if (key == Constants.PREF_VPN_OVERRIDE_ENABLED ||
				key == Constants.PREF_VPN_DNS_HOSTNAME ||
				key == Constants.PREF_CONNECTIVITY_WATCHDOG_ENABLED ||
				key == Constants.PREF_CONNECTIVITY_WATCHDOG_PROBE_TARGETS
			) {
				evaluateActiveNetworks()
			}
		}

	override fun onCreate() {
		super.onCreate()
		connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
		watchdogManager = ConnectivityWatchdogManager(this, serviceScope)

		cachedDnsMode = Global.getString(contentResolver, Constants.SETTINGS_PRIVATE_DNS_MODE)
		contentResolver.registerContentObserver(
			Global.getUriFor(Constants.SETTINGS_PRIVATE_DNS_MODE),
			false,
			dnsSettingsObserver
		)

		getPrefs().registerOnSharedPreferenceChangeListener(preferenceChangeListener)

		serviceScope.launch {
			NetworkProfileRepository.networkProfiles.collect {
				evaluateActiveNetworks()
			}
		}
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		val notification = createPersistentNotification()
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			startForeground(
				Constants.NOTIFICATION_ID_FOREGROUND,
				notification,
				ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
			)
		} else {
			startForeground(Constants.NOTIFICATION_ID_FOREGROUND, notification)
		}
		registerNetworkCallback()
		return START_STICKY
	}

	override fun onDestroy() {
		serviceScope.cancel()
		watchdogManager.cancelAll()
		unregisterNetworkCallback()
		contentResolver.unregisterContentObserver(dnsSettingsObserver)
		getPrefs().unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)

		val prefs = getPrefs()
		if (prefs.getBoolean(Constants.PREF_IS_IN_VPN_OVERRIDE, false)) {
			prefs.edit(true) { putBoolean(Constants.PREF_IS_IN_VPN_OVERRIDE, false) }
		}
		if (prefs.getString(Constants.PREF_ACTIVE_SSID_OVERRIDE, null) != null) {
			restorePreferredDnsSync()
			prefs.edit(true) { putString(Constants.PREF_ACTIVE_SSID_OVERRIDE, null) }
		}

		(application as DnsToggleApplication).detectedSsid = null
		super.onDestroy()
	}

	override fun onBind(intent: Intent?): IBinder? = null

	private fun createPersistentNotification(): Notification {
		val launchIntent = Intent(this, MainActivity::class.java)
		val pendingIntent =
			PendingIntent.getActivity(this, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE)

		return NotificationCompat.Builder(this, Constants.CHANNEL_ID_SERVICE)
			.setContentTitle(getString(R.string.service_notif_title))
			.setContentText(getString(R.string.service_notif_text))
			.setSmallIcon(R.drawable.ic_qs_dns)
			.setContentIntent(pendingIntent)
			.setOngoing(true)
			.setCategory(Notification.CATEGORY_SERVICE)
			.setPriority(NotificationCompat.PRIORITY_MIN)
			.setVisibility(NotificationCompat.VISIBILITY_SECRET)
			.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
			.build()
	}

	private fun registerNetworkCallback() {
		if (networkCallback != null) return

		val networkRequest = NetworkRequest.Builder()
			.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
			.addTransportType(NetworkCapabilities.TRANSPORT_VPN)
			.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
			.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
			.build()

		val hasLocationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
		} else {
			checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
		}

		networkCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			val flags = if (hasLocationPermission) NetworkCallback.FLAG_INCLUDE_LOCATION_INFO else 0
			object : NetworkCallback(flags) {
				override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
					val oldCaps = activeNetworks[network]
					if (hasMeaningfulChange(oldCaps, caps)) {
						activeNetworks[network] = caps
						evaluateActiveNetworks()
					}
				}

				override fun onLost(network: Network) {
					if (activeNetworks.containsKey(network)) {
						activeNetworks.remove(network)
						evaluateActiveNetworks()
					}
				}
			}
		} else {
			object : NetworkCallback() {
				override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
					val oldCaps = activeNetworks[network]
					if (hasMeaningfulChange(oldCaps, caps)) {
						activeNetworks[network] = caps
						evaluateActiveNetworks()
					}
				}

				override fun onLost(network: Network) {
					if (activeNetworks.containsKey(network)) {
						activeNetworks.remove(network)
						evaluateActiveNetworks()
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

	private fun hasMeaningfulChange(
		oldCaps: NetworkCapabilities?,
		newCaps: NetworkCapabilities
	): Boolean {
		if (oldCaps == null) return true

		if (oldCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) != newCaps.hasCapability(
				NetworkCapabilities.NET_CAPABILITY_VALIDATED
			)
		) return true

		// Check transport changes
		if (oldCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != newCaps.hasTransport(
				NetworkCapabilities.TRANSPORT_VPN
			)
		) return true
		if (oldCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != newCaps.hasTransport(
				NetworkCapabilities.TRANSPORT_WIFI
			)
		) return true

		// Check SSID changes
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			val oldInfo = oldCaps.transportInfo as? WifiInfo
			val newInfo = newCaps.transportInfo as? WifiInfo
			if (oldInfo?.ssid != newInfo?.ssid) return true
		} else {
			if (oldCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) != newCaps.hasCapability(
					NetworkCapabilities.NET_CAPABILITY_VALIDATED
				)
			) return true
			if (oldCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != newCaps.hasTransport(
					NetworkCapabilities.TRANSPORT_WIFI
				)
			) return true
			if (oldCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != newCaps.hasTransport(
					NetworkCapabilities.TRANSPORT_VPN
				)
			) return true
			return false
		}

		return false
	}

	private fun evaluateActiveNetworks() {
		serviceScope.launch(Dispatchers.Default) {
			val allCaps = activeNetworks.values
			val isVpnActive = allCaps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
			val wifiEntry =
				activeNetworks.entries.find { it.value.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
			val wifiNetwork = wifiEntry?.key
			val wifiCaps = wifiEntry?.value

			val hasLocationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
			} else {
				checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
			}

			val currentSsid = if (hasLocationPermission) {
				wifiCaps?.let { caps ->
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
						(caps.transportInfo as? WifiInfo)?.ssid?.stripSsidQuotes()
					} else {
						val wm = getSystemService(WIFI_SERVICE) as WifiManager
						@Suppress("DEPRECATION") wm.connectionInfo?.ssid?.stripSsidQuotes()
					}
				}
			} else null

			val app = application as DnsToggleApplication
			app.detectedSsid =
				if (currentSsid == "<unknown ssid>" || currentSsid?.isEmpty() == true) null else currentSsid

			val prefs = getPrefs()
			val vpnOverrideEnabled = prefs.getBoolean(Constants.PREF_VPN_OVERRIDE_ENABLED, false)
			val isInVpnOverride = prefs.getBoolean(Constants.PREF_IS_IN_VPN_OVERRIDE, false)

			if (isVpnActive && vpnOverrideEnabled) {
				watchdogManager.cancelAll()
				if (!isInVpnOverride) {
					// Enter VPN override
					prefs.edit {
						putBoolean(Constants.PREF_IS_IN_VPN_OVERRIDE, true)
						putString(Constants.PREF_ACTIVE_SSID_OVERRIDE, null)
					}
				}
				applyVpnDns()
				return@launch
			} else if (!isVpnActive && isInVpnOverride) {
				// Exit VPN override
				prefs.edit { putBoolean(Constants.PREF_IS_IN_VPN_OVERRIDE, false) }
				dispatchStatusNotification(getString(R.string.notif_vpn_dns_restored))
			}

			// Normal Wi-Fi logic
			if (currentSsid == null || currentSsid == "<unknown ssid>" || currentSsid.isEmpty()) {
				watchdogManager.cancelAll()
				if (prefs.getString(Constants.PREF_ACTIVE_SSID_OVERRIDE, null) != null) {
					prefs.edit { putString(Constants.PREF_ACTIVE_SSID_OVERRIDE, null) }
				}
				restorePreferredDns()
				return@launch
			}

			val profiles = NetworkProfileRepository.networkProfiles.value ?: emptyList()
			val profile = profiles.find { it.ssid == currentSsid }

			if (profile != null) {
				if (prefs.getString(Constants.PREF_ACTIVE_SSID_OVERRIDE, null) != currentSsid) {
					prefs.edit { putString(Constants.PREF_ACTIVE_SSID_OVERRIDE, currentSsid) }
				}

				if (profile.isEnabled) {
					watchdogManager.cancelAll() // Cancels recovery if enabled

					val targetHostname = profile.targetHostname ?: getGlobalPreferredHostname()
					if (targetHostname != null) {
						applyHostnameDns(currentSsid, targetHostname)
					} else {
						restorePreferredDns()
					}

					watchdogManager.evaluateConnectivityWatchdog(
						currentSsid,
						wifiCaps,
						activeNetworks,
						cachedDnsMode
					)
				} else {
					watchdogManager.cancelAll() // Cancels watchdog if disabled

					applyOffDns(
						currentSsid,
						profile.targetMode,
						if (profile.isAutoDetected) R.string.notif_connectivity_watchdog_disabled else R.string.notif_dns_disabled_auto
					)
					watchdogManager.maybeRetryAutoDetectedSsid(
						currentSsid,
						profile.isAutoDetected,
						wifiNetwork
					) { ssid ->
						dispatchStatusNotification(
							getString(
								R.string.notif_connectivity_watchdog_restored,
								ssid
							)
						)
					}
				}
			} else {
				if (prefs.getString(Constants.PREF_ACTIVE_SSID_OVERRIDE, null) != null) {
					prefs.edit { putString(Constants.PREF_ACTIVE_SSID_OVERRIDE, null) }
				}
				restorePreferredDns(immediate = true)

				watchdogManager.cancelAll()

				watchdogManager.evaluateConnectivityWatchdog(
					currentSsid,
					wifiCaps,
					activeNetworks,
					cachedDnsMode
				)
			}
		}
	}

	private fun getGlobalPreferredHostname(): String? {
		val encryptedPrefs = (application as DnsToggleApplication).getEncryptedPrefs()
		val encryptedHostname = encryptedPrefs.getString(Constants.PREF_LAST_USED_HOSTNAME, null)
		return encryptedHostname?.let {
			when (val result = EncryptionManager.decrypt(it)) {
				is EncryptionManager.DecryptResult.Success -> result.data
				else -> null
			}
		} ?: Global.getString(contentResolver, Constants.SETTINGS_PRIVATE_DNS_SPECIFIER)
	}

	private fun applyHostnameDns(ssid: String, hostname: String) {
		watchdogManager.cancelDebounce()
		updateDnsSetting(
			Constants.DNS_MODE_HOSTNAME,
			ssid,
			R.string.notif_dns_enabled_auto,
			hostname = hostname
		)
	}

	private fun applyVpnDns() {
		val vpnDns = VpnRepository.vpnDnsHostname.value
		val vpnMode = VpnRepository.vpnDnsMode.value

		val resolver = contentResolver
		val currentMode = Global.getString(resolver, Constants.SETTINGS_PRIVATE_DNS_MODE)
		val currentSpecifier = Global.getString(resolver, Constants.SETTINGS_PRIVATE_DNS_SPECIFIER)

		if (vpnMode != Constants.DNS_MODE_HOSTNAME) {
			if (currentMode != vpnMode) {
				updateDnsSetting(vpnMode, null)
				dispatchStatusNotification(getString(R.string.notif_vpn_dns_applied))
			}
		} else if (vpnDns != null) {
			// Hostname mode
			if (currentMode != Constants.DNS_MODE_HOSTNAME || currentSpecifier != vpnDns) {
				try {
					Global.putString(resolver, Constants.SETTINGS_PRIVATE_DNS_SPECIFIER, vpnDns)
					updateDnsSetting(Constants.DNS_MODE_HOSTNAME, null)
					dispatchStatusNotification(getString(R.string.notif_vpn_dns_applied))
				} catch (e: SecurityException) {
					Log.e(TAG, "Failed to apply VPN DNS hostname", e)
				}
			}
		}
	}

	private fun unregisterNetworkCallback() {
		networkCallback?.let { callback ->
			try {
				connectivityManager.unregisterNetworkCallback(callback)
			} catch (e: Exception) {
				Log.e(TAG, "Failed to unregister network callback", e)
			}
		}
		networkCallback = null
	}

	private fun applyOffDns(
		ssid: String,
		targetMode: String?,
		reasonStringResId: Int = R.string.notif_dns_disabled_auto
	) {
		watchdogManager.cancelDebounce()
		val offMode = targetMode ?: getPrefs().getString(
			Constants.PREF_DEFAULT_OFF_MODE,
			Constants.DNS_MODE_OPPORTUNISTIC
		) ?: Constants.DNS_MODE_OPPORTUNISTIC
		updateDnsSetting(offMode, ssid, reasonStringResId)
	}

	private fun restorePreferredDns(immediate: Boolean = false) {
		watchdogManager.restorePreferredDns(immediate) {
			restorePreferredDnsSync()
		}
	}

	private fun restorePreferredDnsSync() {
		val sharedPreferences = getPrefs()

		if (sharedPreferences.getBoolean(Constants.PREF_IS_IN_VPN_OVERRIDE, false) &&
			sharedPreferences.getBoolean(Constants.PREF_VPN_OVERRIDE_ENABLED, false)
		) {
			return
		}

		val preferredMode = sharedPreferences.getString(
			Constants.PREF_PREFERRED_DNS_MODE,
			Constants.DNS_MODE_HOSTNAME
		) ?: Constants.DNS_MODE_HOSTNAME

		if (preferredMode == Constants.DNS_MODE_HOSTNAME) {
			// Try to restore from last used hostname
			val encryptedPrefs = (application as DnsToggleApplication).getEncryptedPrefs()
			val encryptedHostname =
				encryptedPrefs.getString(Constants.PREF_LAST_USED_HOSTNAME, null)
			val hostname = encryptedHostname?.let {
				when (val result = EncryptionManager.decrypt(it)) {
					is EncryptionManager.DecryptResult.Success -> result.data
					else -> null
				}
			}

			if (hostname != null) {
				try {
					Global.putString(
						contentResolver,
						Constants.SETTINGS_PRIVATE_DNS_SPECIFIER,
						hostname
					)
					updateDnsSetting(preferredMode, null)
				} catch (e: SecurityException) {
					Log.e(TAG, "Failed to restore preferred DNS specifier", e)
				}
			} else {
				// Safety fallback to avoid breaking network with a null hostname
				val offMode = sharedPreferences.getString(
					Constants.PREF_DEFAULT_OFF_MODE,
					Constants.DNS_MODE_OPPORTUNISTIC
				) ?: Constants.DNS_MODE_OPPORTUNISTIC
				updateDnsSetting(offMode, null)
				dispatchStatusNotification(
					getString(R.string.keystore_error_title) + ": " + (if (offMode == Constants.DNS_MODE_OFF) getString(
						R.string.off_strict_label
					) else getString(R.string.off_automatic_label))
				)
			}
		} else {
			updateDnsSetting(preferredMode, null)
		}
	}

	private fun updateDnsSetting(
		newMode: String,
		ssidForNotification: String?,
		reasonStringResId: Int = R.string.notif_dns_disabled_auto,
		hostname: String? = null
	) {
		try {
			val resolver = contentResolver
			var changed = false

			if (newMode == Constants.DNS_MODE_HOSTNAME && hostname != null) {
				val currentHostname =
					Global.getString(resolver, Constants.SETTINGS_PRIVATE_DNS_SPECIFIER)
				if (currentHostname != hostname) {
					Global.putString(resolver, Constants.SETTINGS_PRIVATE_DNS_SPECIFIER, hostname)
					changed = true
				}
			}

			if (cachedDnsMode != newMode) {
				val currentSystemMode =
					Global.getString(resolver, Constants.SETTINGS_PRIVATE_DNS_MODE)
				if (currentSystemMode != newMode) {
					Global.putString(resolver, Constants.SETTINGS_PRIVATE_DNS_MODE, newMode)
					cachedDnsMode = newMode
					changed = true
				} else {
					cachedDnsMode = newMode
				}
			}

			if (changed) {
				ssidForNotification?.let {
					dispatchStatusNotification(getString(reasonStringResId, it))
				}
				TileServiceCompat.requestListeningState(
					this,
					ComponentName(this, DnsToggleService::class.java)
				)
			}
		} catch (e: SecurityException) {
			Log.e(TAG, "Failed to update DNS setting", e)
		}
	}

	private fun dispatchStatusNotification(message: String) {
		NotificationUtils.showStatusNotification(this, message)
		if (getPrefs().getBoolean(Constants.PREF_SHOW_TOAST, true)) {
			Handler(Looper.getMainLooper()).post {
				Toast.makeText(this, message, Toast.LENGTH_SHORT)
					.show()
			}
		}
	}
}
