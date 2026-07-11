package com.ericlowry.dnstoggle

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
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
			if (key == Constants.PREF_AUTO_BLACKLIST || key == Constants.PREF_AUTO_WHITELIST) {
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
		val serviceIntent = Intent(this, WifiMonitoringService::class.java)

		if (isWifiMonitoringRequired()) {
			try {
				ContextCompat.startForegroundService(this, serviceIntent)
			} catch (e: Exception) {
				Log.e("DnsToggleApplication", "Failed to start foreground service", e)
			}
		} else {
			stopService(serviceIntent)
			detectedSsid = null
		}
	}

	private fun isWifiMonitoringRequired(): Boolean {
		val sharedPreferences = getPrefs()
		val isAutoManagementEnabled =
			sharedPreferences.getBoolean(Constants.PREF_AUTO_BLACKLIST, false) ||
					sharedPreferences.getBoolean(Constants.PREF_AUTO_WHITELIST, false)

		val blacklist = DnsSettingsRepository.blacklist.value
		if (blacklist == null || (blacklist.isEmpty() && !isAutoManagementEnabled)) return false

		val hasFineLocation = ContextCompat.checkSelfPermission(
			this, Manifest.permission.ACCESS_FINE_LOCATION
		) == PackageManager.PERMISSION_GRANTED

		val hasNearbyWifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			ContextCompat.checkSelfPermission(
				this, Manifest.permission.NEARBY_WIFI_DEVICES
			) == PackageManager.PERMISSION_GRANTED
		} else {
			true
		}

		return hasFineLocation && hasNearbyWifi
	}
}
