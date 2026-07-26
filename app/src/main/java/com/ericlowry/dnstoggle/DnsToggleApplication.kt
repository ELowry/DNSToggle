package com.ericlowry.dnstoggle

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.UiModeManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.ericlowry.dnstoggle.data.Constants
import com.ericlowry.dnstoggle.data.repository.DnsSettingsRepository
import com.ericlowry.dnstoggle.data.repository.HostnameRepository
import com.ericlowry.dnstoggle.data.repository.NetworkProfileRepository
import com.ericlowry.dnstoggle.data.repository.SecurityRepository
import com.ericlowry.dnstoggle.data.repository.VpnRepository
import com.ericlowry.dnstoggle.service.DnsToggleService
import com.ericlowry.dnstoggle.service.TileServiceCompat
import com.ericlowry.dnstoggle.service.UsbDebuggingTileService
import com.ericlowry.dnstoggle.service.WifiMonitoringService
import com.ericlowry.dnstoggle.util.EncryptionManager
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DnsToggleApplication : Application() {

	var detectedSsid: String? = null
	private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
	private var isAdbObserverRegistered = false

	private val preferenceChangeListener =
		SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
			if (key == Constants.PREF_AUTO_SAVE_STATE ||
				key == Constants.PREF_AUTO_SAVE_HOST ||
				key == Constants.PREF_VPN_OVERRIDE_ENABLED ||
				key == Constants.PREF_CONNECTIVITY_WATCHDOG_ENABLED
			) {
				updateWifiMonitoringRegistration()
			}
			if (key == Constants.PREF_USB_DEBUGGING_TILE_UNLOCKED) {
				updateUsbDebuggingTileAvailability()
			}
		}

	private val devModeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
		override fun onChange(selfChange: Boolean) {
			updateUsbDebuggingTileAvailability()
		}
	}

	private val adbObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
		override fun onChange(selfChange: Boolean) {
			TileServiceCompat.requestListeningState(
				this@DnsToggleApplication,
				ComponentName(this@DnsToggleApplication, UsbDebuggingTileService::class.java)
			)
		}
	}

	private val dnsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
		override fun onChange(selfChange: Boolean) {
			val resolver = contentResolver
			val newMode = Settings.Global.getString(resolver, Constants.SETTINGS_PRIVATE_DNS_MODE)
			val prefs = getPrefs()
			val isInVpn = prefs.getBoolean(Constants.PREF_IS_IN_VPN_OVERRIDE, false)
			val activeSsid = prefs.getString(Constants.PREF_ACTIVE_SSID_OVERRIDE, null)

			if (!isInVpn && activeSsid == null && newMode != null) {
				prefs.edit {
					putString(Constants.PREF_PREFERRED_DNS_MODE, newMode)
				}
				if (newMode == Constants.DNS_MODE_HOSTNAME) {
					val newSpecifier =
						Settings.Global.getString(
							resolver,
							Constants.SETTINGS_PRIVATE_DNS_SPECIFIER
						)
					if (!newSpecifier.isNullOrEmpty()) {
						getEncryptedPrefs().edit {
							putString(
								Constants.PREF_LAST_USED_HOSTNAME,
								EncryptionManager.encrypt(newSpecifier)
							)
						}
					}
				}
			}

			TileServiceCompat.requestListeningState(
				this@DnsToggleApplication,
				ComponentName(this@DnsToggleApplication, DnsToggleService::class.java)
			)
		}
	}

	override fun onCreate() {
		super.onCreate()
		val dynamicOptions = DynamicColorsOptions.Builder()
			.setPrecondition { _, _ -> shouldApplyDynamicColors() }
			.build()
		DynamicColors.applyToActivitiesIfAvailable(this, dynamicOptions)
		SecurityRepository.initialize(this)
		VpnRepository.initialize(this)
		NetworkProfileRepository.initialize(this)
		HostnameRepository.initialize(this)
		DnsSettingsRepository.initialize(this)
		initializeNotificationChannels()
		initializePreferredDnsMode()

		getPrefs().registerOnSharedPreferenceChangeListener(preferenceChangeListener)

		contentResolver.registerContentObserver(
			Settings.Global.getUriFor(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED),
			false,
			devModeObserver
		)

		updateUsbDebuggingTileAvailability()

		contentResolver.registerContentObserver(
			Settings.Global.getUriFor(Constants.SETTINGS_PRIVATE_DNS_MODE),
			false,
			dnsObserver
		)

		contentResolver.registerContentObserver(
			Settings.Global.getUriFor(Constants.SETTINGS_PRIVATE_DNS_SPECIFIER),
			false,
			dnsObserver
		)

		// Initial tile update
		TileServiceCompat.requestListeningState(
			this,
			ComponentName(this, DnsToggleService::class.java)
		)

		applicationScope.launch {
			NetworkProfileRepository.networkProfiles.collect { profiles ->
				if (profiles != null) {
					updateWifiMonitoringRegistration()
				}
			}
		}
	}

	fun getPrefs(): SharedPreferences {
		return getSharedPreferences("app_prefs_v2", MODE_PRIVATE)
	}

	fun getEncryptedPrefs(): SharedPreferences {
		return getSharedPreferences("encrypted_prefs", MODE_PRIVATE)
	}

	private fun shouldApplyDynamicColors(): Boolean {
		// Skip for Android TV because of limited support
		val uiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
		return uiModeManager.currentModeType != Configuration.UI_MODE_TYPE_TELEVISION
	}

	private fun initializeNotificationChannels() {
		val manager = getSystemService(NotificationManager::class.java)

		val statusChannel = NotificationChannel(
			Constants.CHANNEL_ID_ALERT,
			getString(R.string.notif_channel_alerts_name),
			NotificationManager.IMPORTANCE_DEFAULT,
		).apply {
			description = getString(R.string.notif_channel_alerts_desc)
		}

		val serviceChannel = NotificationChannel(
			Constants.CHANNEL_ID_SERVICE,
			getString(R.string.notif_channel_service_name),
			NotificationManager.IMPORTANCE_MIN,
		).apply {
			description = getString(R.string.notif_channel_service_desc)
			setShowBadge(false)
			enableLights(false)
			enableVibration(false)
			lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
		}

		manager.createNotificationChannels(listOf(statusChannel, serviceChannel))
	}

	private fun initializePreferredDnsMode() {
		val sharedPreferences = getPrefs()
		if (!sharedPreferences.contains(Constants.PREF_PREFERRED_DNS_MODE)) {
			val currentMode =
				Settings.Global.getString(contentResolver, Constants.SETTINGS_PRIVATE_DNS_MODE)
					?: Constants.DNS_MODE_OPPORTUNISTIC
			sharedPreferences.edit { putString(Constants.PREF_PREFERRED_DNS_MODE, currentMode) }
		}
	}

	fun updateWifiMonitoringRegistration() {
		applicationScope.launch(Dispatchers.Default) {
			val serviceIntent = Intent(this@DnsToggleApplication, WifiMonitoringService::class.java)

			if (isWifiMonitoringRequired()) {
				try {
					ContextCompat.startForegroundService(this@DnsToggleApplication, serviceIntent)
				} catch (e: Exception) {
					Log.e("DnsToggleApplication", "Failed to start foreground service", e)
				}
			} else {
				stopService(serviceIntent)
				detectedSsid = null
			}

			// Tile resync
			TileServiceCompat.requestListeningState(
				this@DnsToggleApplication,
				ComponentName(this@DnsToggleApplication, DnsToggleService::class.java)
			)
		}
	}

	private fun isWifiMonitoringRequired(): Boolean {
		val sharedPreferences = getPrefs()
		val vpnEnabled = sharedPreferences.getBoolean(Constants.PREF_VPN_OVERRIDE_ENABLED, false)
		val autoEnabled = sharedPreferences.getBoolean(Constants.PREF_AUTO_SAVE_STATE, false) ||
				sharedPreferences.getBoolean(Constants.PREF_AUTO_SAVE_HOST, false) ||
				sharedPreferences.getBoolean(Constants.PREF_CONNECTIVITY_WATCHDOG_ENABLED, false)

		val profiles = NetworkProfileRepository.networkProfiles.value
		val hasNetworkProfiles = !profiles.isNullOrEmpty()

		return vpnEnabled || autoEnabled || hasNetworkProfiles
	}

	fun updateUsbDebuggingTileAvailability() {
		val isDevMode = try {
			Settings.Global.getInt(
				contentResolver,
				Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
				0
			) != 0
		} catch (_: SecurityException) {
			false
		}

		val isAppUnlocked = getPrefs().getBoolean(Constants.PREF_USB_DEBUGGING_TILE_UNLOCKED, false)

		val componentName = ComponentName(this, UsbDebuggingTileService::class.java)

		val newState = if (isDevMode && isAppUnlocked) {
			PackageManager.COMPONENT_ENABLED_STATE_ENABLED
		} else {
			PackageManager.COMPONENT_ENABLED_STATE_DISABLED
		}

		if (packageManager.getComponentEnabledSetting(componentName) != newState) {
			packageManager.setComponentEnabledSetting(
				componentName,
				newState,
				PackageManager.DONT_KILL_APP
			)

			if (newState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
				registerAdbObserver()
				TileServiceCompat.requestListeningState(this, componentName)
			} else {
				unregisterAdbObserver()
			}
		} else if (newState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
			registerAdbObserver()
		}
	}

	private fun registerAdbObserver() {
		if (!isAdbObserverRegistered) {
			try {
				contentResolver.registerContentObserver(
					Settings.Global.getUriFor(Settings.Global.ADB_ENABLED),
					false,
					adbObserver
				)
				isAdbObserverRegistered = true
			} catch (e: Exception) {
				Log.w("DnsToggleApplication", "Failed to register ADB observer: ${e.message}")
			}
		}
	}

	private fun unregisterAdbObserver() {
		if (isAdbObserverRegistered) {
			contentResolver.unregisterContentObserver(adbObserver)
			isAdbObserverRegistered = false
		}
	}
}
