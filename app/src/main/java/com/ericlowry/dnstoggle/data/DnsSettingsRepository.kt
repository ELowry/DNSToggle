package com.ericlowry.dnstoggle.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.ericlowry.dnstoggle.DnsToggleApplication
import com.ericlowry.dnstoggle.util.EncryptionManager
import com.ericlowry.dnstoggle.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

object DnsSettingsRepository {
	private lateinit var app: DnsToggleApplication
	private lateinit var sharedPreferences: SharedPreferences
	private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

	private val _blacklist = MutableStateFlow<Set<String>?>(null)
	val blacklist: StateFlow<Set<String>?> = _blacklist.asStateFlow()

	private val _autoDetectedBlacklist = MutableStateFlow<Set<String>?>(null)
	val autoDetectedBlacklist: StateFlow<Set<String>?> = _autoDetectedBlacklist.asStateFlow()

	private val _dnsHostnames = MutableStateFlow<List<DnsHostname>?>(null)
	val dnsHostnames: StateFlow<List<DnsHostname>?> = _dnsHostnames.asStateFlow()

	private val _isKeyInvalidated = MutableStateFlow(false)
	val isKeyInvalidated: StateFlow<Boolean> = _isKeyInvalidated.asStateFlow()

	private val _vpnOverrideEnabled = MutableStateFlow(false)
	val vpnOverrideEnabled: StateFlow<Boolean> = _vpnOverrideEnabled.asStateFlow()

	private val _vpnDnsHostname = MutableStateFlow<String?>(null)
	val vpnDnsHostname: StateFlow<String?> = _vpnDnsHostname.asStateFlow()

	fun initialize(context: Context) {
		if (::app.isInitialized) return
		app = context.applicationContext as DnsToggleApplication
		sharedPreferences = app.getPrefs()
		loadBlacklist()
		loadAutoDetectedBlacklist()
		loadHostnames()
		_vpnOverrideEnabled.value =
			sharedPreferences.getBoolean(Constants.PREF_VPN_OVERRIDE_ENABLED, false)

		val encryptedVpnHostname =
			sharedPreferences.getString(Constants.PREF_VPN_DNS_HOSTNAME, null)
		_vpnDnsHostname.value = if (encryptedVpnHostname == null) {
			"off"
		} else {
			when (val result = EncryptionManager.decrypt(encryptedVpnHostname)) {
				is EncryptionManager.DecryptResult.Success -> result.data
				is EncryptionManager.DecryptResult.KeyInvalidated -> {
					_isKeyInvalidated.value = true
					"off"
				}

				else -> "off"
			}
		}
	}

	private fun loadBlacklist() {
		scope.launch {
			val encryptedSet =
				sharedPreferences.getStringSet(Constants.PREF_SSID_BLACKLIST, emptySet())
					?: emptySet()

			var keyInvalidated = false
			val decryptedSet = encryptedSet.mapNotNull {
				when (val result = EncryptionManager.decrypt(it)) {
					is EncryptionManager.DecryptResult.Success -> result.data
					is EncryptionManager.DecryptResult.KeyInvalidated -> {
						keyInvalidated = true
						null
					}

					else -> null
				}
			}.toSet()

			if (keyInvalidated) {
				_isKeyInvalidated.value = true
				sharedPreferences.edit { remove(Constants.PREF_SSID_BLACKLIST) }
			}
			_blacklist.value = decryptedSet
		}
	}

	private fun loadAutoDetectedBlacklist() {
		scope.launch {
			val encryptedSet =
				sharedPreferences.getStringSet(
					Constants.PREF_SSID_AUTO_DETECTED_BLACKLIST,
					emptySet()
				)
					?: emptySet()

			var keyInvalidated = false
			val decryptedSet = encryptedSet.mapNotNull {
				when (val result = EncryptionManager.decrypt(it)) {
					is EncryptionManager.DecryptResult.Success -> result.data
					is EncryptionManager.DecryptResult.KeyInvalidated -> {
						keyInvalidated = true
						null
					}

					else -> null
				}
			}.toSet()

			if (keyInvalidated) {
				_isKeyInvalidated.value = true
				sharedPreferences.edit { remove(Constants.PREF_SSID_AUTO_DETECTED_BLACKLIST) }
			}
			_autoDetectedBlacklist.value = decryptedSet
		}
	}

	private fun loadHostnames() {
		scope.launch {
			val rawData = sharedPreferences.all[Constants.PREF_DNS_HOSTNAMES]

			var keyInvalidated = false
			val resultList = mutableListOf<DnsHostname>()

			if (rawData is Set<*>) {
				// Legacy StringSet migration
				@Suppress("UNCHECKED_CAST")
				val encryptedSet = rawData as? Set<String> ?: emptySet()
				encryptedSet.forEach {
					when (val result = EncryptionManager.decrypt(it)) {
						is EncryptionManager.DecryptResult.Success -> {
							resultList.add(DnsHostname.fromSerializedString(result.data))
						}

						is EncryptionManager.DecryptResult.KeyInvalidated -> keyInvalidated = true
						else -> {}
					}
				}
				// Initial sort for legacy data
				resultList.sortWith { a, b ->
					String.CASE_INSENSITIVE_ORDER.compare(
						a.getDisplayName(),
						b.getDisplayName()
					)
				}
				if (resultList.isNotEmpty() && !keyInvalidated) {
					saveHostnamesAsync(resultList)
				}
			} else if (rawData is String) {
				// New JSON format
				when (val result = EncryptionManager.decrypt(rawData)) {
					is EncryptionManager.DecryptResult.Success -> {
						try {
							val jsonArray = JSONArray(result.data)
							for (i in 0 until jsonArray.length()) {
								resultList.add(
									DnsHostname.fromSerializedString(
										jsonArray.getString(
											i
										)
									)
								)
							}
						} catch (e: Exception) {
							Log.e("DnsSettingsRepository", "Failed to parse hostnames JSON", e)
						}
					}

					is EncryptionManager.DecryptResult.KeyInvalidated -> keyInvalidated = true
					else -> {}
				}
			}

			if (keyInvalidated) {
				_isKeyInvalidated.value = true
				sharedPreferences.edit {
					remove(Constants.PREF_SSID_BLACKLIST)
					remove(Constants.PREF_DNS_HOSTNAMES)
					remove(Constants.PREF_LAST_USED_HOSTNAME)
				}
			}

			_dnsHostnames.value = resultList
		}
	}

	fun resetKeyInvalidated() {
		_isKeyInvalidated.value = false
	}

	fun promoteAutoDetectedSsid(ssid: String) {
		_autoDetectedBlacklist.update { current ->
			val safeCurrent = current ?: emptySet()
			if (ssid !in safeCurrent) return@update safeCurrent
			val next = safeCurrent - ssid
			saveAutoDetectedBlacklistAsync(next)
			next
		}
	}

	fun addToBlacklist(ssid: String, autoDetected: Boolean = false) {
		if (autoDetected) {
			_autoDetectedBlacklist.update { current ->
				val safeCurrent = current ?: emptySet()
				if (ssid in safeCurrent) return@update safeCurrent
				val next = safeCurrent + ssid
				saveAutoDetectedBlacklistAsync(next)
				next
			}
		}
		_blacklist.update { current ->
			val safeCurrent = current ?: emptySet()
			if (ssid in safeCurrent) return@update safeCurrent
			val next = safeCurrent + ssid
			saveBlacklistAsync(next)
			next
		}
	}

	fun removeFromBlacklist(ssid: String) {
		_blacklist.update { current ->
			val safeCurrent = current ?: emptySet()
			if (ssid !in safeCurrent) return@update safeCurrent
			val next = safeCurrent - ssid
			saveBlacklistAsync(next)
			next
		}
		_autoDetectedBlacklist.update { current ->
			val safeCurrent = current ?: emptySet()
			if (ssid !in safeCurrent) return@update safeCurrent
			val next = safeCurrent - ssid
			saveAutoDetectedBlacklistAsync(next)
			next
		}
	}

	fun updateSsidInBlacklist(oldSsid: String, newSsid: String) {
		_blacklist.update { current ->
			val safeCurrent = current ?: emptySet()
			if (oldSsid !in safeCurrent) return@update safeCurrent
			val next = safeCurrent - oldSsid + newSsid
			saveBlacklistAsync(next)
			next
		}
		_autoDetectedBlacklist.update { current ->
			val safeCurrent = current ?: emptySet()
			if (oldSsid !in safeCurrent) return@update safeCurrent
			val next = safeCurrent - oldSsid
			saveAutoDetectedBlacklistAsync(next)
			next
		}
	}

	private fun saveBlacklistAsync(list: Set<String>) {
		scope.launch {
			val encryptedSet = list.map { EncryptionManager.encrypt(it) }.toSet()
			sharedPreferences.edit { putStringSet(Constants.PREF_SSID_BLACKLIST, encryptedSet) }
		}
	}

	private fun saveAutoDetectedBlacklistAsync(list: Set<String>) {
		scope.launch {
			val encryptedSet = list.map { EncryptionManager.encrypt(it) }.toSet()
			sharedPreferences.edit {
				putStringSet(Constants.PREF_SSID_AUTO_DETECTED_BLACKLIST, encryptedSet)
			}
		}
	}

	fun addHostname(hostname: String, label: String? = null) {
		_dnsHostnames.update { current ->
			val safeCurrent = current ?: emptyList()
			val newEntry = DnsHostname(hostname, label)

			val existingIndex = safeCurrent.indexOfFirst { it.hostname == hostname }
			val next = if (existingIndex != -1) {
				safeCurrent.mapIndexed { index, dnsHostname ->
					if (index == existingIndex) newEntry else dnsHostname
				}
			} else {
				listOf(newEntry) + safeCurrent
			}

			saveHostnamesAsync(next)
			next
		}
	}

	fun removeHostname(hostname: String) {
		_dnsHostnames.update { current ->
			val safeCurrent = current ?: emptyList()
			if (safeCurrent.none { it.hostname == hostname }) return@update safeCurrent
			val next = safeCurrent.filter { it.hostname != hostname }

			// Check if the removed hostname was being used for VPN override
			if (_vpnDnsHostname.value == hostname) {
				updateVpnDnsHostname("off")
				sharedPreferences.edit {
					putBoolean(Constants.PREF_VPN_HOSTNAME_REMOVED_WARNING, true)
				}
			} else if (next.size == 1 && _vpnDnsHostname.value != "off") {
				updateVpnDnsHostname("off")
			}

			saveHostnamesAsync(next)
			next
		}
	}

	fun updateHostname(oldHostname: String, newHostname: String, newLabel: String? = null) {
		_dnsHostnames.update { current ->
			val safeCurrent = current ?: emptyList()
			val next = safeCurrent.map {
				if (it.hostname == oldHostname) DnsHostname(newHostname, newLabel) else it
			}

			if (_vpnDnsHostname.value == oldHostname) {
				updateVpnDnsHostname(newHostname)
			}

			saveHostnamesAsync(next)
			next
		}
	}

	fun updateHostnamesOrder(newList: List<DnsHostname>) {
		_dnsHostnames.value = newList
		saveHostnamesAsync(newList)
	}

	fun updateVpnOverrideEnabled(enabled: Boolean) {
		_vpnOverrideEnabled.value = enabled
		sharedPreferences.edit { putBoolean(Constants.PREF_VPN_OVERRIDE_ENABLED, enabled) }
	}

	fun updateVpnDnsHostname(hostname: String) {
		_vpnDnsHostname.value = hostname
		val encrypted = EncryptionManager.encrypt(hostname)
		sharedPreferences.edit { putString(Constants.PREF_VPN_DNS_HOSTNAME, encrypted) }
	}

	private fun saveHostnamesAsync(list: List<DnsHostname>) {
		scope.launch {
			val jsonArray = JSONArray()
			list.forEach { jsonArray.put(it.toSerializedString()) }
			val encrypted = EncryptionManager.encrypt(jsonArray.toString())
			sharedPreferences.edit { putString(Constants.PREF_DNS_HOSTNAMES, encrypted) }
		}
	}

	fun exportConfigToJson(): String {
		val json = JSONObject()
		val hostnamesArray = JSONArray()
		_dnsHostnames.value?.forEach { hostnamesArray.put(it.toSerializedString()) }
		json.put("hostnames", hostnamesArray)

		val blacklistArray = JSONArray()
		_blacklist.value?.forEach { blacklistArray.put(it) }
		json.put("blacklist", blacklistArray)

		json.put(
			"auto_blacklist",
			sharedPreferences.getBoolean(Constants.PREF_AUTO_BLACKLIST, false)
		)
		json.put(
			"auto_whitelist",
			sharedPreferences.getBoolean(Constants.PREF_AUTO_WHITELIST, false)
		)
		json.put(
			"vpn_override",
			sharedPreferences.getBoolean(Constants.PREF_VPN_OVERRIDE_ENABLED, false)
		)
		json.put("vpn_dns", _vpnDnsHostname.value ?: "off")
		json.put(
			"hide_launcher_icon",
			sharedPreferences.getBoolean(Constants.PREF_HIDE_LAUNCHER_ICON, false)
		)
		json.put(
			"disable_dns_test",
			sharedPreferences.getBoolean(Constants.PREF_DISABLE_DNS_TEST, false)
		)
		json.put("show_toast", sharedPreferences.getBoolean(Constants.PREF_SHOW_TOAST, false))

		return json.toString()
	}

	fun importConfigFromJson(jsonString: String): Boolean {
		return try {
			val json = JSONObject(jsonString)

			if (json.has("hostnames")) {
				val hostnamesArray = json.getJSONArray("hostnames")
				val importedHostnames = mutableListOf<DnsHostname>()
				for (i in 0 until hostnamesArray.length()) {
					val parsedEntry = DnsHostname.fromSerializedString(hostnamesArray.getString(i))
					if (NetworkUtils.isValidDnsHostname(parsedEntry.hostname)) {
						importedHostnames.add(parsedEntry)
					}
				}
				updateHostnamesOrder(importedHostnames)
			}

			if (json.has("blacklist")) {
				val blacklistArray = json.getJSONArray("blacklist")
				val importedBlacklist = mutableSetOf<String>()
				for (i in 0 until blacklistArray.length()) {
					importedBlacklist.add(blacklistArray.getString(i))
				}
				scope.launch {
					val encryptedSet =
						importedBlacklist.map { EncryptionManager.encrypt(it) }.toSet()
					sharedPreferences.edit {
						putStringSet(
							Constants.PREF_SSID_BLACKLIST,
							encryptedSet,
						)
					}
					_blacklist.value = importedBlacklist
				}
			}

			sharedPreferences.edit {
				putBoolean(Constants.PREF_AUTO_BLACKLIST, json.optBoolean("auto_blacklist", false))
				putBoolean(Constants.PREF_AUTO_WHITELIST, json.optBoolean("auto_whitelist", false))
				putBoolean(
					Constants.PREF_VPN_OVERRIDE_ENABLED,
					json.optBoolean("vpn_override", false),
				)
				putBoolean(
					Constants.PREF_HIDE_LAUNCHER_ICON,
					json.optBoolean("hide_launcher_icon", false),
				)
				putBoolean(
					Constants.PREF_DISABLE_DNS_TEST,
					json.optBoolean("disable_dns_test", false),
				)
				putBoolean(Constants.PREF_SHOW_TOAST, json.optBoolean("show_toast", false))
			}
			_vpnOverrideEnabled.value = json.optBoolean("vpn_override", false)

			if (json.has("vpn_dns")) {
				val parsedVpnDns = json.getString("vpn_dns")
				if (parsedVpnDns == "off" || NetworkUtils.isValidDnsHostname(parsedVpnDns)) {
					updateVpnDnsHostname(parsedVpnDns)
				}
			}
			true
		} catch (_: Exception) {
			false
		}
	}
}
