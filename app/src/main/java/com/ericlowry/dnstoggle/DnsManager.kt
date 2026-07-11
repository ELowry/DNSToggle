package com.ericlowry.dnstoggle

import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit

object DnsManager {
	private const val TAG = "DnsManager"

	sealed class ToggleResult {
		data object Success : ToggleResult()
		data object PermissionRequired : ToggleResult()
		data object MissingHostname : ToggleResult()
		data object Error : ToggleResult()
	}

	fun togglePrivateDns(
		context: Context,
		enabled: Boolean,
		targetHostname: String? = null,
		isInteractiveMainUi: Boolean = false
	): ToggleResult {
		val resolver = context.contentResolver
		val app = context.applicationContext as DnsToggleApplication
		val sharedPreferences = app.getPrefs()

		val previousMode = Settings.Global.getString(resolver, Constants.SETTINGS_PRIVATE_DNS_MODE)
		val previousHostname =
			Settings.Global.getString(resolver, Constants.SETTINGS_PRIVATE_DNS_SPECIFIER)

		var effectiveHostname = targetHostname

		if (enabled) {
			if (effectiveHostname.isNullOrEmpty()) {
				// Try last used hostname
				val encryptedHostname =
					sharedPreferences.getString(Constants.PREF_LAST_USED_HOSTNAME, null)
				if (encryptedHostname != null) {
					effectiveHostname =
						when (val result = EncryptionManager.decrypt(encryptedHostname)) {
							is EncryptionManager.DecryptResult.Success -> result.data
							else -> null
						}
				}
			}
			if (effectiveHostname.isNullOrEmpty()) {
				// Try current system specifier
				effectiveHostname =
					Settings.Global.getString(resolver, Constants.SETTINGS_PRIVATE_DNS_SPECIFIER)
			}

			if (effectiveHostname.isNullOrEmpty() || !NetworkUtils.isValidDnsHostname(
					effectiveHostname
				)
			) {
				return ToggleResult.MissingHostname
			}
		}

		val newMode = if (enabled) Constants.DNS_MODE_HOSTNAME else Constants.DNS_MODE_OPPORTUNISTIC
		val currentSsid = NetworkUtils.getCurrentWifiSsid(context)

		if (sharedPreferences.getBoolean(Constants.PREF_IS_IN_VPN_OVERRIDE, false)) {
			if (enabled) {
				effectiveHostname?.let { hostname ->
					DnsSettingsRepository.updateVpnDnsHostname(hostname)
				}
			} else {
				DnsSettingsRepository.updateVpnDnsHostname("off")
			}
		}

		var handledByAuto = false

		if (currentSsid != null) {
			if (!enabled && sharedPreferences.getBoolean(Constants.PREF_AUTO_BLACKLIST, false)) {
				DnsSettingsRepository.addToBlacklist(currentSsid)
				if (sharedPreferences.getBoolean(Constants.PREF_SHOW_TOAST, true)) {
					NotificationUtils.showStatusNotification(
						context,
						context.getString(R.string.notif_ssid_added, currentSsid)
					)
				}
				handledByAuto = true
			} else if (enabled && sharedPreferences.getBoolean(
					Constants.PREF_AUTO_WHITELIST,
					false
				)
			) {
				DnsSettingsRepository.removeFromBlacklist(currentSsid)
				if (sharedPreferences.getBoolean(Constants.PREF_SHOW_TOAST, true)) {
					NotificationUtils.showStatusNotification(
						context,
						context.getString(R.string.notif_ssid_removed, currentSsid)
					)
				}
				handledByAuto = true
			}
		}

		if (!handledByAuto) {
			effectiveHostname?.let { hostname ->
				sharedPreferences.edit {
					putString(
						Constants.PREF_LAST_USED_HOSTNAME,
						EncryptionManager.encrypt(hostname)
					)
				}
			}
		}

		return try {
			if (enabled) {
				effectiveHostname?.let { hostname ->
					Settings.Global.putString(
						resolver,
						Constants.SETTINGS_PRIVATE_DNS_SPECIFIER,
						hostname
					)
					sharedPreferences.edit {
						putString(
							Constants.PREF_LAST_USED_HOSTNAME,
							EncryptionManager.encrypt(hostname)
						)
					}
				}
			}
			Settings.Global.putString(resolver, Constants.SETTINGS_PRIVATE_DNS_MODE, newMode)
			if (!isInteractiveMainUi && sharedPreferences.getBoolean(
					Constants.PREF_SHOW_TOAST,
					true
				) && !handledByAuto
			) {
				android.os.Handler(android.os.Looper.getMainLooper()).post {
					if (enabled) {
						if (previousMode != Constants.DNS_MODE_HOSTNAME) {
							android.widget.Toast.makeText(
								context,
								"${context.getString(R.string.private_dns)}: ${context.getString(R.string.on_label)}",
								android.widget.Toast.LENGTH_SHORT
							).show()
						} else if (previousHostname != effectiveHostname) {
							android.widget.Toast.makeText(
								context,
								context.getString(R.string.toast_dns_changed, effectiveHostname),
								android.widget.Toast.LENGTH_SHORT
							).show()
						}
					} else if (previousMode == Constants.DNS_MODE_HOSTNAME) {
						android.widget.Toast.makeText(
							context,
							"${context.getString(R.string.private_dns)}: ${context.getString(R.string.off_label)}",
							android.widget.Toast.LENGTH_SHORT
						).show()
					}
				}
			}
			ToggleResult.Success
		} catch (e: SecurityException) {
			Log.e(TAG, "Failed to update private DNS mode", e)
			ToggleResult.PermissionRequired
		} catch (e: Exception) {
			Log.e(TAG, "Unexpected error updating private DNS mode", e)
			ToggleResult.Error
		}
	}
}
