package com.ericlowry.dnstoggle

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit

class DnsToggleService : TileService() {

    companion object {
        private const val NOTIFICATION_ID_STATUS = 1001
        private const val CHANNEL_ID_ALERT = "network_status"
    }

	override fun onClick() {
		super.onClick()

		if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
			showPermissionRequiredDialog()
			return
		}

		val resolver = contentResolver

		try {
			val currentMode = Settings.Global.getString(resolver, "private_dns_mode")
			val isEnabling = currentMode != "hostname"
            val newMode = if (isEnabling) "hostname" else "opportunistic"
            
            val sharedPreferences = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            
            val currentSsid = NetworkUtils.getCurrentWifiSsid(this)
            
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

			Settings.Global.putString(resolver, "private_dns_mode", newMode)
			updateTileState(if (isEnabling) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE)
			
		} catch (ignored: SecurityException) { }
	}

    private fun addToBlacklist(ssid: String) {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val blacklist = prefs.getStringSet("ssid_blacklist", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (blacklist.add(ssid)) {
            prefs.edit { putStringSet("ssid_blacklist", blacklist) }
            displayStatusNotification(getString(R.string.notif_ssid_added, ssid))
            (application as DnsToggleApplication).updateWifiMonitoringRegistration()
        }
    }

    private fun removeFromBlacklist(ssid: String) {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val blacklist = prefs.getStringSet("ssid_blacklist", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (blacklist.remove(ssid)) {
            prefs.edit { putStringSet("ssid_blacklist", blacklist) }
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

		if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
			updateTileState(Tile.STATE_UNAVAILABLE)
			return
		}

		try {
			val currentMode = Settings.Global.getString(contentResolver, "private_dns_mode")
			val tileState = if (currentMode == "hostname") Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
			updateTileState(tileState)
		} catch (ignored: SecurityException) {
			updateTileState(Tile.STATE_UNAVAILABLE)
		}
	}

	private fun updateTileState(state: Int) {
		qsTile?.let { tile ->
			tile.state = state

			val sharedPreferences = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
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

	private fun showPermissionRequiredDialog() {
		val intent = Intent(this, MainActivity::class.java).apply {
			flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
		}
		
		val pendingIntent = PendingIntent.getActivity(
			this, 0, intent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			startActivityAndCollapse(pendingIntent)
		} else {
			@Suppress("DEPRECATION")
			startActivityAndCollapse(intent)
		}
	}
}
