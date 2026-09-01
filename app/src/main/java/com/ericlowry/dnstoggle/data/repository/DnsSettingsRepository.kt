package com.ericlowry.dnstoggle.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.ericlowry.dnstoggle.DnsToggleApplication
import com.ericlowry.dnstoggle.data.BackupConfig
import com.ericlowry.dnstoggle.data.Constants
import com.ericlowry.dnstoggle.data.NetworkProfile
import com.ericlowry.dnstoggle.util.NetworkUtils
import kotlinx.serialization.json.Json
import org.json.JSONObject

/**
 * Repository for bulk export and import of application configuration.
 * Handles JSON serialization and data migration for backups.
 */
object DnsSettingsRepository {
	private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
	private lateinit var sharedPreferences: SharedPreferences

	fun initialize(context: Context) {
		val app = context.applicationContext as DnsToggleApplication
		sharedPreferences = app.getPrefs()
	}

	/**
	 * Exports the current application configuration to a JSON string.
	 * Includes hostnames, network profiles, and general settings.
	 */
	fun exportConfigToJson(): String {
		val backupConfig = BackupConfig(
			hostnames = HostnameRepository.dnsHostnames.value ?: emptyList(),
			networkProfiles = NetworkProfileRepository.networkProfiles.value ?: emptyList(),
			autoSaveState = sharedPreferences.getBoolean(Constants.PREF_AUTO_SAVE_STATE, false),
			autoSaveHost = sharedPreferences.getBoolean(Constants.PREF_AUTO_SAVE_HOST, false),
			vpnOverride = VpnRepository.vpnOverrideEnabled.value,
			vpnDns = VpnRepository.vpnDnsHostname.value,
			vpnDnsMode = VpnRepository.vpnDnsMode.value,
			hideLauncherIcon = sharedPreferences.getBoolean(
				Constants.PREF_HIDE_LAUNCHER_ICON,
				false
			),
			disableDnsTest = sharedPreferences.getBoolean(Constants.PREF_DISABLE_DNS_TEST, false),
			showToast = sharedPreferences.getBoolean(Constants.PREF_SHOW_TOAST, false),
			enableStrictOff = sharedPreferences.getBoolean(
				Constants.PREF_ENABLE_STRICT_OFF_OPTION,
				false
			),
			defaultOffMode = sharedPreferences.getString(
				Constants.PREF_DEFAULT_OFF_MODE,
				Constants.DNS_MODE_OPPORTUNISTIC
			) ?: Constants.DNS_MODE_OPPORTUNISTIC
		)
		return json.encodeToString(backupConfig)
	}

	/**
	 * Imports application configuration from a JSON string.
	 *
	 * @param jsonString The JSON representation of the configuration.
	 * @return True if the import was successful, false otherwise.
	 */
	fun importConfigFromJson(jsonString: String): Boolean {
		return try {
			val config = json.decodeFromString<BackupConfig>(jsonString)

			if (config.hostnames.isNotEmpty()) {
				val validHostnames =
					config.hostnames.filter { NetworkUtils.isValidDnsHostname(it.hostname) }
				HostnameRepository.updateHostnamesOrder(validHostnames)
			}

			if (config.networkProfiles.isNotEmpty()) {
				NetworkProfileRepository.updateNetworkProfilesFromBackup(config.networkProfiles)
			} else {
				// START_LEGACY_MIGRATION_CODE: Legacy flat array JSON backup import
				try {
					val rawJson = JSONObject(jsonString)
					if (rawJson.has("blacklist")) {
						val blacklistArray = rawJson.getJSONArray("blacklist")
						val importedProfiles = mutableListOf<NetworkProfile>()
						for (i in 0 until blacklistArray.length()) {
							importedProfiles.add(
								NetworkProfile(
									ssid = blacklistArray.getString(i),
									isEnabled = false,
									targetHostname = null,
									isAutoDetected = false
								)
							)
						}
						NetworkProfileRepository.updateNetworkProfilesFromBackup(importedProfiles)
					}
				} catch (_: Exception) {
				}
				// END_LEGACY_MIGRATION_CODE
			}

			sharedPreferences.edit {
				putBoolean(Constants.PREF_AUTO_SAVE_STATE, config.autoSaveState)
				putBoolean(Constants.PREF_AUTO_SAVE_HOST, config.autoSaveHost)
				putBoolean(Constants.PREF_VPN_OVERRIDE_ENABLED, config.vpnOverride)
				putBoolean(Constants.PREF_HIDE_LAUNCHER_ICON, config.hideLauncherIcon)
				putBoolean(Constants.PREF_DISABLE_DNS_TEST, config.disableDnsTest)
				putBoolean(Constants.PREF_SHOW_TOAST, config.showToast)
				putBoolean(Constants.PREF_ENABLE_STRICT_OFF_OPTION, config.enableStrictOff)
				putString(Constants.PREF_DEFAULT_OFF_MODE, config.defaultOffMode)
			}
			VpnRepository.updateVpnOverrideEnabled(config.vpnOverride)

			val parsedVpnDns = config.vpnDns
			var parsedVpnMode = config.vpnDnsMode

			if (!config.enableStrictOff && parsedVpnMode == Constants.DNS_MODE_OFF) {
				parsedVpnMode = Constants.DNS_MODE_OPPORTUNISTIC
			}

			if (parsedVpnMode == Constants.DNS_MODE_HOSTNAME) {
				if (parsedVpnDns != null && NetworkUtils.isValidDnsHostname(parsedVpnDns)) {
					VpnRepository.updateVpnDns(parsedVpnMode, parsedVpnDns)
				} else {
					VpnRepository.updateVpnDns(Constants.DNS_MODE_OPPORTUNISTIC, null)
				}
			} else {
				VpnRepository.updateVpnDns(parsedVpnMode, null)
			}

			if (!config.enableStrictOff) {
				NetworkProfileRepository.sanitizeStrictOffProfiles()
			}
			true
		} catch (e: Exception) {
			Log.e("DnsSettingsRepository", "Failed to import config", e)
			false
		}
	}
}
