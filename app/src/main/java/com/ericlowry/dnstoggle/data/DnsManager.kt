package com.ericlowry.dnstoggle.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import com.ericlowry.dnstoggle.DnsToggleApplication
import com.ericlowry.dnstoggle.R
import com.ericlowry.dnstoggle.data.repository.HostnameRepository
import com.ericlowry.dnstoggle.data.repository.NetworkProfileRepository
import com.ericlowry.dnstoggle.data.repository.VpnRepository
import com.ericlowry.dnstoggle.util.EncryptionManager
import com.ericlowry.dnstoggle.util.NetworkUtils
import com.ericlowry.dnstoggle.util.NotificationUtils

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
		targetMode: String? = null,
		isInteractiveMainUi: Boolean = false,
		forceFeedback: Boolean = false,
		isFromTile: Boolean = false
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
				val encryptedPrefs = app.getEncryptedPrefs()
				val encryptedHostname =
					encryptedPrefs.getString(Constants.PREF_LAST_USED_HOSTNAME, null)
				if (encryptedHostname != null) {
					effectiveHostname =
						when (val result = EncryptionManager.decrypt(encryptedHostname)) {
							is EncryptionManager.DecryptResult.Success -> result.data
							else -> null
						}
				}
			}
			if (effectiveHostname.isNullOrEmpty()) {
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

		val offMode = sharedPreferences.getString(
			Constants.PREF_DEFAULT_OFF_MODE,
			Constants.DNS_MODE_OPPORTUNISTIC
		) ?: Constants.DNS_MODE_OPPORTUNISTIC

		val newMode = if (enabled) Constants.DNS_MODE_HOSTNAME else (targetMode ?: offMode)
		val currentSsid = NetworkUtils.getCurrentWifiSsid(context)
		val isInVpn = sharedPreferences.getBoolean(Constants.PREF_IS_IN_VPN_OVERRIDE, false)

		if (isInVpn) {
			if (enabled) {
				effectiveHostname?.let { hostname ->
					VpnRepository.updateVpnDns(Constants.DNS_MODE_HOSTNAME, hostname)
				}
			} else {
				VpnRepository.updateVpnDns(targetMode ?: offMode, null)
			}
		}

		try {
			if (enabled) {
				effectiveHostname?.let { hostname ->
					Settings.Global.putString(
						resolver,
						Constants.SETTINGS_PRIVATE_DNS_SPECIFIER,
						hostname
					)
				}
			}
			Settings.Global.putString(resolver, Constants.SETTINGS_PRIVATE_DNS_MODE, newMode)
		} catch (e: SecurityException) {
			Log.e(TAG, "Failed to update private DNS mode", e)
			return ToggleResult.PermissionRequired
		} catch (e: Exception) {
			Log.e(TAG, "Unexpected error updating private DNS mode", e)
			return ToggleResult.Error
		}

		var handledByProfile = false
		if (currentSsid != null && isFromTile && !isInVpn) {
			val autoSaveState = sharedPreferences.getBoolean(Constants.PREF_AUTO_SAVE_STATE, false)
			val autoSaveHost = sharedPreferences.getBoolean(Constants.PREF_AUTO_SAVE_HOST, false)

			val isExplicitHostChange = targetHostname != null
			val shouldInterceptForProfile =
				if (isExplicitHostChange) autoSaveHost else autoSaveState

			if (shouldInterceptForProfile) {
				val targetHost = if (enabled && autoSaveHost) effectiveHostname else null
				val existingProfile =
					NetworkProfileRepository.networkProfiles.value?.find { it.ssid == currentSsid }

				NetworkProfileRepository.upsertNetworkProfile(
					ssid = currentSsid,
					isEnabled = enabled,
					targetHostname = targetHost,
					isAutoDetected = false,
					isUnsaved = existingProfile == null,
					preserveExistingHostname = !enabled,
					targetMode = if (!enabled) targetMode else null,
					preserveExistingMode = enabled
				)

				if (forceFeedback || sharedPreferences.getBoolean(
						Constants.PREF_SHOW_TOAST,
						true
					)
				) {
					val messageRes =
						if (enabled) R.string.notif_ssid_removed else R.string.notif_ssid_added
					NotificationUtils.showStatusNotification(
						context,
						context.getString(messageRes, currentSsid)
					)
				}
				handledByProfile = true
			}
		}

		if (!handledByProfile && !isInVpn) {
			effectiveHostname?.let { hostname ->
				app.getEncryptedPrefs().edit {
					putString(
						Constants.PREF_LAST_USED_HOSTNAME,
						EncryptionManager.encrypt(hostname)
					)
				}
			}

			if (!enabled && targetMode != null) {
				sharedPreferences.edit { putString(Constants.PREF_DEFAULT_OFF_MODE, targetMode) }
			}

			sharedPreferences.edit { putString(Constants.PREF_PREFERRED_DNS_MODE, newMode) }
		}

		if (!isInteractiveMainUi && (forceFeedback || sharedPreferences.getBoolean(
				Constants.PREF_SHOW_TOAST,
				true
			)) && !handledByProfile
		) {
			Handler(Looper.getMainLooper()).post {
				if (enabled) {
					if (previousMode != Constants.DNS_MODE_HOSTNAME) {
						Toast.makeText(
							context,
							"${context.getString(R.string.private_dns)}: ${context.getString(R.string.on_label)}",
							Toast.LENGTH_SHORT
						).show()
					} else if (previousHostname != effectiveHostname) {
						val displayName =
							HostnameRepository.dnsHostnames.value?.find { it.hostname == effectiveHostname }
								?.getDisplayName() ?: effectiveHostname
						Toast.makeText(
							context,
							context.getString(R.string.toast_dns_changed, displayName),
							Toast.LENGTH_SHORT
						).show()
					}
				} else if (previousMode == Constants.DNS_MODE_HOSTNAME) {
					val label = when (newMode) {
						Constants.DNS_MODE_OFF -> context.getString(R.string.off_strict_label)
						else -> context.getString(R.string.off_automatic_label)
					}
					Toast.makeText(
						context,
						"${context.getString(R.string.private_dns)}: $label",
						Toast.LENGTH_SHORT
					).show()
				}
			}
		}

		return ToggleResult.Success
	}
}
