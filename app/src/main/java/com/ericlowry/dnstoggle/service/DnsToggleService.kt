package com.ericlowry.dnstoggle.service

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings.Global
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.ericlowry.dnstoggle.DnsToggleApplication
import com.ericlowry.dnstoggle.R
import com.ericlowry.dnstoggle.data.Constants
import com.ericlowry.dnstoggle.data.DnsManager
import com.ericlowry.dnstoggle.data.repository.HostnameRepository
import com.ericlowry.dnstoggle.ui.MainActivity
import com.ericlowry.dnstoggle.util.attemptSecureSettingsGrant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DnsToggleService : TileService() {

	private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

	private fun getPrefs(): SharedPreferences {
		return (application as DnsToggleApplication).getPrefs()
	}

	override fun onDestroy() {
		serviceScope.cancel()
		super.onDestroy()
	}

	override fun onTileAdded() {
		super.onTileAdded()
		updateTileFromSystemSettings()
	}

	override fun onClick() {
		super.onClick()

		serviceScope.launch(Dispatchers.IO) {
			if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
				// Attempt root grant
				if ((attemptSecureSettingsGrant(this@DnsToggleService)) &&
					(checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED)
				) {
					// Success
				} else {
					// Failed or missing root
					withContext(Dispatchers.Main) {
						showPermissionRequiredDialog()
					}
					return@launch
				}
			}

			val resolver = contentResolver
			val currentMode = Global.getString(resolver, Constants.SETTINGS_PRIVATE_DNS_MODE)
			val isEnabling = currentMode != Constants.DNS_MODE_HOSTNAME

			val result = DnsManager.togglePrivateDns(
				context = this@DnsToggleService,
				enabled = isEnabling,
				isFromTile = true
			)

			withContext(Dispatchers.Main) {
				when (result) {
					is DnsManager.ToggleResult.Success -> {
						updateTileState(if (isEnabling) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE)
					}

					is DnsManager.ToggleResult.MissingHostname -> {
						Toast.makeText(
							this@DnsToggleService,
							R.string.error_empty_dns_host,
							Toast.LENGTH_LONG
						).show()
						launchMainActivity(focusDnsInput = true)
					}

					is DnsManager.ToggleResult.PermissionRequired -> {
						showPermissionRequiredDialog()
					}

					is DnsManager.ToggleResult.Error -> {
						// Logged in DnsManager
					}
				}
			}
		}
	}

	override fun onStartListening() {
		super.onStartListening()
		updateTileFromSystemSettings()
	}

	private fun updateTileFromSystemSettings() {
		try {
			val currentMode = Global.getString(contentResolver, Constants.SETTINGS_PRIVATE_DNS_MODE)
			val tileState =
				if (currentMode == Constants.DNS_MODE_HOSTNAME) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
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
			val dynamicAppName = sharedPreferences.getString(
				Constants.PREF_DYNAMIC_APP_NAME,
				getString(R.string.app_name)
			)

			tile.label = dynamicAppName

			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				val isInVpn = sharedPreferences.getBoolean(Constants.PREF_IS_IN_VPN_OVERRIDE, false)
				val ssidOverride =
					sharedPreferences.getString(Constants.PREF_ACTIVE_SSID_OVERRIDE, null)

				val prefix = when {
					isInVpn -> "🛡️ "
					ssidOverride != null -> "🛜 "
					else -> ""
				}

				tile.subtitle = if (state == Tile.STATE_ACTIVE) {
					val specifier =
						Global.getString(contentResolver, Constants.SETTINGS_PRIVATE_DNS_SPECIFIER)
							?: getString(R.string.on_label)

					val displayName = HostnameRepository.dnsHostnames.value
						?.find { it.hostname == specifier }
						?.getDisplayName() ?: specifier

					"$prefix$displayName"
				} else {
					val currentMode =
						Global.getString(contentResolver, Constants.SETTINGS_PRIVATE_DNS_MODE)
					val offLabel = if (currentMode == Constants.DNS_MODE_OFF) {
						getString(R.string.mode_disabled)
					} else {
						getString(R.string.off_label)
					}
					"$prefix$offLabel"
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

	private fun launchMainActivity(
		showPermissionDialog: Boolean = false,
		focusDnsInput: Boolean = false
	) {
		val intent = Intent(this, MainActivity::class.java).apply {
			flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
			if (showPermissionDialog) putExtra("show_permission_dialog", true)
			if (focusDnsInput) {
				putExtra(MainActivity.EXTRA_FOCUS_DNS_INPUT, true)
				putExtra(MainActivity.EXTRA_ENABLE_DNS_AFTER_SAVE, true)
			}
		}

		TileServiceCompat.startActivityAndCollapse(this, intent)
	}

	private fun showPermissionRequiredDialog() {
		launchMainActivity(showPermissionDialog = true)
	}
}
