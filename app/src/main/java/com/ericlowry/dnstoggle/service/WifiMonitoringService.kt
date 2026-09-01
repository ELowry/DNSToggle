package com.ericlowry.dnstoggle.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings.Global
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.ericlowry.dnstoggle.DnsToggleApplication
import com.ericlowry.dnstoggle.R
import com.ericlowry.dnstoggle.data.Constants
import com.ericlowry.dnstoggle.data.DnsPolicyEvaluator
import com.ericlowry.dnstoggle.data.repository.NetworkProfileRepository
import com.ericlowry.dnstoggle.ui.MainActivity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground Service responsible for tracking Wi-Fi connectivity and applying DNS policies.
 * Delegated core logic to NetworkStateTracker and DnsPolicyEvaluator.
 */
class WifiMonitoringService : Service() {

	private lateinit var tracker: NetworkStateTracker
	private lateinit var watchdogManager: ConnectivityWatchdogManager
	private var cachedDnsMode: String? = null

	internal var mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
	private val serviceScope by lazy { CoroutineScope(mainDispatcher + SupervisorJob()) }

	private val dnsSettingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
		override fun onChange(selfChange: Boolean) {
			cachedDnsMode = Global.getString(contentResolver, Constants.SETTINGS_PRIVATE_DNS_MODE)
			TileServiceCompat.requestListeningState(
				this@WifiMonitoringService,
				ComponentName(this@WifiMonitoringService, DnsToggleService::class.java)
			)
		}
	}

	private val preferenceChangeListener =
		SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
			val policyKeys = listOf(
				Constants.PREF_VPN_OVERRIDE_ENABLED,
				Constants.PREF_VPN_DNS_HOSTNAME,
				Constants.PREF_CONNECTIVITY_WATCHDOG_ENABLED,
				Constants.PREF_CONNECTIVITY_WATCHDOG_PROBE_TARGETS
			)
			if (key in policyKeys) {
				DnsPolicyEvaluator.evaluate(
					this,
					serviceScope,
					tracker.currentNetwork.value,
					tracker.getActiveNetworks(),
					watchdogManager,
					cachedDnsMode
				)
			}
		}

	override fun onCreate() {
		super.onCreate()
		tracker = NetworkStateTracker(this)
		watchdogManager = ConnectivityWatchdogManager(this, serviceScope)

		cachedDnsMode = Global.getString(contentResolver, Constants.SETTINGS_PRIVATE_DNS_MODE)
		contentResolver.registerContentObserver(
			Global.getUriFor(Constants.SETTINGS_PRIVATE_DNS_MODE),
			false,
			dnsSettingsObserver
		)

		val app = application as DnsToggleApplication
		app.getPrefs().registerOnSharedPreferenceChangeListener(preferenceChangeListener)

		serviceScope.launch {
			NetworkProfileRepository.networkProfiles.collect {
				DnsPolicyEvaluator.evaluate(
					this@WifiMonitoringService,
					serviceScope,
					tracker.currentNetwork.value,
					tracker.getActiveNetworks(),
					watchdogManager,
					cachedDnsMode
				)
			}
		}

		serviceScope.launch {
			tracker.currentNetwork.collect { network ->
				DnsPolicyEvaluator.evaluate(
					this@WifiMonitoringService,
					serviceScope,
					network,
					tracker.getActiveNetworks(),
					watchdogManager,
					cachedDnsMode
				)
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
		tracker.startTracking()
		return START_STICKY
	}

	override fun onDestroy() {
		tracker.stopTracking()
		serviceScope.cancel()
		watchdogManager.cancelAll()
		contentResolver.unregisterContentObserver(dnsSettingsObserver)

		val app = application as DnsToggleApplication
		val prefs = app.getPrefs()
		prefs.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)

		if (prefs.getBoolean(Constants.PREF_IS_IN_VPN_OVERRIDE, false)) {
			prefs.edit(true) { putBoolean(Constants.PREF_IS_IN_VPN_OVERRIDE, false) }
		}
		if (prefs.getString(Constants.PREF_ACTIVE_SSID_OVERRIDE, null) != null) {
			DnsPolicyEvaluator.restorePreferredDns(
				this,
				watchdogManager,
				immediate = true
			)
			prefs.edit(true) { putString(Constants.PREF_ACTIVE_SSID_OVERRIDE, null) }
		}

		app.detectedSsid = null
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
}
