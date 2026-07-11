package com.ericlowry.dnstoggle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
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

import androidx.core.app.NotificationCompat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class WifiMonitoringService : Service() {

	private lateinit var connectivityManager: ConnectivityManager
	private var networkCallback: NetworkCallback? = null
	private var cachedDnsMode: String? = null
	private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
	private var debounceJob: Job? = null

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

	override fun onCreate() {
		super.onCreate()
		connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
		initializeNotificationChannel()
		// Initialize cache and register observer
		cachedDnsMode = Global.getString(contentResolver, Constants.SETTINGS_PRIVATE_DNS_MODE)
		contentResolver.registerContentObserver(
			Global.getUriFor(Constants.SETTINGS_PRIVATE_DNS_MODE),
			false,
			dnsSettingsObserver
		)
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
		unregisterNetworkCallback()
		contentResolver.unregisterContentObserver(dnsSettingsObserver)
		(application as DnsToggleApplication).detectedSsid = null
		super.onDestroy()
	}

	override fun onBind(intent: Intent?): IBinder? = null

	private fun initializeNotificationChannel() {
		val channelName = getString(R.string.service_notif_title)
		val importance = NotificationManager.IMPORTANCE_LOW
		val channel =
			NotificationChannel(Constants.CHANNEL_ID_SERVICE, channelName, importance).apply {
				setShowBadge(false)
			}
		val manager = getSystemService(NotificationManager::class.java)
		manager.createNotificationChannel(channel)
	}

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
			.build()
	}

	private fun registerNetworkCallback() {
		if (networkCallback != null) return

		val networkRequest = NetworkRequest.Builder()
			.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
			.addTransportType(NetworkCapabilities.TRANSPORT_VPN)
			.build()

		networkCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			object : NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
				override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
					evaluateNetworkCapabilities(caps)
				}

				override fun onLost(network: Network) {
					(application as DnsToggleApplication).detectedSsid = null
					restorePreferredDns()
				}
			}
		} else {
			object : NetworkCallback() {
				override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
					evaluateNetworkCapabilities(caps)
				}

				override fun onLost(network: Network) {
					(application as DnsToggleApplication).detectedSsid = null
					restorePreferredDns()
				}
			}
		}

		try {
			connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
		} catch (e: Exception) {
			Log.e(TAG, "Failed to register network callback", e)
		}
	}

	private fun evaluateNetworkCapabilities(networkCapabilities: NetworkCapabilities) {
		val currentSsid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			val wifiInfo = networkCapabilities.transportInfo as? WifiInfo
			wifiInfo?.ssid?.stripSsidQuotes()
		} else {
			val wm = getSystemService(WIFI_SERVICE) as WifiManager
			@Suppress("DEPRECATION") wm.connectionInfo?.ssid?.stripSsidQuotes()
		}

		val app = application as DnsToggleApplication
		if ((currentSsid == null) || (currentSsid == "<unknown ssid>") || currentSsid.isEmpty()) {
			app.detectedSsid = null
			restorePreferredDns()
			return
		}

		app.detectedSsid = currentSsid

		val blacklist = DnsSettingsRepository.blacklist.value ?: emptySet()
		if (blacklist.contains(currentSsid)) {
			applyOpportunisticDns(currentSsid)
		} else {
			restorePreferredDns()
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

	private fun applyOpportunisticDns(ssid: String) {
		debounceJob?.cancel()
		updateDnsSetting(Constants.DNS_MODE_OPPORTUNISTIC, ssid)
	}

	private fun restorePreferredDns() {
		debounceJob?.cancel()
		debounceJob = serviceScope.launch {
			delay(5.seconds) // Wait 5 seconds to ensure we aren't just "bouncing" on the edge of coverage
			val sharedPreferences = getPrefs()
			val preferredMode = sharedPreferences.getString(
				Constants.PREF_PREFERRED_DNS_MODE,
				Constants.DNS_MODE_HOSTNAME
			) ?: Constants.DNS_MODE_HOSTNAME
			updateDnsSetting(preferredMode, null)
		}
	}

	private fun updateDnsSetting(newMode: String, ssidForNotification: String?) {
		try {
			val resolver = contentResolver
			if (cachedDnsMode != newMode) {
				Global.putString(resolver, Constants.SETTINGS_PRIVATE_DNS_MODE, newMode)
				cachedDnsMode = newMode
				ssidForNotification?.let {
					dispatchStatusNotification(getString(R.string.notif_dns_disabled_auto, it))
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
	}
}
