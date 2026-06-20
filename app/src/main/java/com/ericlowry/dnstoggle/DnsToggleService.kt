package com.ericlowry.dnstoggle

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings.Global
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast

import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DnsToggleService : TileService() {

	companion object {
		private const val TAG = "DnsToggleService"
		private const val NOTIFICATION_ID_STATUS = 1001
		private const val CHANNEL_ID_ALERT = "network_status"
	}

	private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

	private fun getPrefs(): SharedPreferences {
		return (application as DnsToggleApplication).getPrefs()
	}

	override fun onDestroy() {
		serviceScope.cancel()
		super.onDestroy()
	}

	override fun onClick() {
		super.onClick()

		serviceScope.launch {
			if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
				// Attempt root grant
				if ((RootUtils.grantSecureSettingsPermission(packageName)) &&
					(checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED)
				) {
					// Success
				} else {
					// Failed or missing root
					showPermissionRequiredDialog()
					return@launch
				}
			}

			val resolver = contentResolver

			try {
				val currentMode = Global.getString(resolver, "private_dns_mode")
				val isEnabling = currentMode != "hostname"

				if (isEnabling) {
					val specifier = Global.getString(resolver, "private_dns_specifier")
					if (specifier.isNullOrEmpty() || !NetworkUtils.isValidDnsHostname(specifier)) {
						Toast.makeText(this@DnsToggleService, R.string.error_empty_dns_host, Toast.LENGTH_LONG).show()
						launchMainActivity(focusDnsInput = true)
						return@launch
					}
				}

				val newMode = if (isEnabling) "hostname" else "opportunistic"

				val sharedPreferences = getPrefs()

				val currentSsid = NetworkUtils.getCurrentWifiSsid(this@DnsToggleService)

				var handledByAuto = false

				if (currentSsid != null) {
					if (!isEnabling && sharedPreferences.getBoolean("auto_blacklist", false)) {
						addToBlacklist(currentSsid)
						handledByAuto = true
					} else if (isEnabling && sharedPreferences.getBoolean("auto_whitelist", false)) {
						removeFromBlacklist(currentSsid)
						handledByAuto = true
					}
				}

				if (!handledByAuto) {
					sharedPreferences.edit { putString("preferred_dns_mode", newMode) }
				}

				Global.putString(resolver, "private_dns_mode", newMode)
				updateTileState(if (isEnabling) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE)

			} catch (e: SecurityException) {
				Log.e(TAG, "Failed to update private DNS mode from tile", e)
			}
		}
	}

	private fun addToBlacklist(ssid: String) {
		val prefs = getPrefs()
		val encryptedBlacklist = prefs.getStringSet("ssid_blacklist", emptySet()) ?: emptySet()
		val blacklist = encryptedBlacklist.asSequence()
			.mapNotNull { EncryptionManager.decrypt(it) }
			.toMutableSet()
		
		if (blacklist.add(ssid)) {
			val newEncryptedSet = blacklist.map { EncryptionManager.encrypt(it) }.toSet()
			prefs.edit { putStringSet("ssid_blacklist", newEncryptedSet) }
			displayStatusNotification(getString(R.string.notif_ssid_added, ssid))
			(application as DnsToggleApplication).updateWifiMonitoringRegistration()
		}
	}

	private fun removeFromBlacklist(ssid: String) {
		val prefs = getPrefs()
		val encryptedBlacklist = prefs.getStringSet("ssid_blacklist", emptySet()) ?: emptySet()
		val blacklist = encryptedBlacklist.asSequence()
			.mapNotNull { EncryptionManager.decrypt(it) }
			.toMutableSet()

		if (blacklist.remove(ssid)) {
			val newEncryptedSet = blacklist.map { EncryptionManager.encrypt(it) }.toSet()
			prefs.edit { putStringSet("ssid_blacklist", newEncryptedSet) }
			displayStatusNotification(getString(R.string.notif_ssid_removed, ssid))
			(application as DnsToggleApplication).updateWifiMonitoringRegistration()
		}
	}

	private fun displayStatusNotification(message: String) {
		val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID_ALERT)
			.setSmallIcon(R.drawable.ic_qs_dns)
			.setContentTitle(getString(R.string.app_name))
			.setContentText(message)
			.setPriority(NotificationCompat.PRIORITY_DEFAULT)
			.setAutoCancel(true)

		with(NotificationManagerCompat.from(this)) {
			val hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
			} else {
				true
			}

			if (hasNotificationPermission) {
				notify(NOTIFICATION_ID_STATUS, notificationBuilder.build())
			}
		}
	}

	override fun onStartListening() {
		super.onStartListening()

		try {
			val currentMode = Global.getString(contentResolver, "private_dns_mode")
			val tileState = if (currentMode == "hostname") Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
			updateTileState(tileState)
		} catch (_: SecurityException) {
			// Keep the tile clickable so the user can be prompted for permissions
			updateTileState(Tile.STATE_INACTIVE)
		}
	}

	private fun updateTileState(state: Int) {
		qsTile?.let { tile ->
			tile.state = state

			val sharedPreferences = getPrefs()
			val dynamicAppName = sharedPreferences.getString("dynamic_app_name", getString(R.string.app_name))

			tile.label = dynamicAppName

			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				tile.subtitle = if (state == Tile.STATE_ACTIVE) {
					getString(R.string.on_label)
				} else {
					getString(R.string.off_label)
				}
			}

			val iconResource = if (state == Tile.STATE_ACTIVE) {
				R.drawable.ic_qs_dns
			} else {
				R.drawable.ic_qs_dns_inactive
			}

			tile.icon = Icon.createWithResource(this, iconResource)

			tile.updateTile()
		}
	}

	@Suppress("DEPRECATION")
	private fun launchMainActivity(showPermissionDialog: Boolean = false, focusDnsInput: Boolean = false) {
		val intent = Intent(this, MainActivity::class.java).apply {
			flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
			if (showPermissionDialog) putExtra("show_permission_dialog", true)
			if (focusDnsInput) putExtra(MainActivity.EXTRA_FOCUS_DNS_INPUT, true)
		}

		val pendingIntent = PendingIntent.getActivity(
			this, 0, intent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
		)

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			startActivityAndCollapse(pendingIntent)
		} else {
			@Suppress("StartActivityAndCollapseDeprecated", "DEPRECATION")
			startActivityAndCollapse(intent)
		}
	}

	private fun showPermissionRequiredDialog() {
		launchMainActivity(showPermissionDialog = true)
	}
}
