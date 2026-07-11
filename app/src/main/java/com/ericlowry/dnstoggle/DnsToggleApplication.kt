package com.ericlowry.dnstoggle

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DnsToggleApplication : Application() {

	var detectedSsid: String? = null
	private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

	private val preferenceChangeListener =
		SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
			if (key == Constants.PREF_AUTO_BLACKLIST ||
				key == Constants.PREF_AUTO_WHITELIST ||
				key == Constants.PREF_VPN_OVERRIDE_ENABLED
			) {
				updateWifiMonitoringRegistration()
			}
		}

	override fun onCreate() {
		super.onCreate()
		DynamicColors.applyToActivitiesIfAvailable(this)
		DnsSettingsRepository.initialize(this)
		initializeNotificationChannels()
		initializePreferredDnsMode()

		getPrefs().registerOnSharedPreferenceChangeListener(preferenceChangeListener)

		applicationScope.launch {
			DnsSettingsRepository.blacklist.collect { blacklist ->
				if (blacklist != null) {
					updateWifiMonitoringRegistration()
				}
			}
		}
	}

	fun getPrefs(): SharedPreferences {
		return getSharedPreferences("app_prefs_v2", MODE_PRIVATE)
	}

	private fun initializeNotificationChannels() {
		val manager = getSystemService(NotificationManager::class.java)

		val statusChannel = NotificationChannel(
			Constants.CHANNEL_ID_ALERT,
			getString(R.string.notif_channel_name),
			NotificationManager.IMPORTANCE_DEFAULT,
		)

		val serviceChannel = NotificationChannel(
			Constants.CHANNEL_ID_SERVICE,
			getString(R.string.service_notif_title),
			NotificationManager.IMPORTANCE_LOW,
		).apply {
			setShowBadge(false)
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
		}
	}

	private fun isWifiMonitoringRequired(): Boolean {
		val sharedPreferences = getPrefs()
		val vpnEnabled = sharedPreferences.getBoolean(Constants.PREF_VPN_OVERRIDE_ENABLED, false)
		val autoEnabled = sharedPreferences.getBoolean(Constants.PREF_AUTO_BLACKLIST, false) ||
				sharedPreferences.getBoolean(Constants.PREF_AUTO_WHITELIST, false)

		val blacklist = DnsSettingsRepository.blacklist.value
		val hasActiveBlacklist = !blacklist.isNullOrEmpty()

		return vpnEnabled || autoEnabled || hasActiveBlacklist
	}
}
