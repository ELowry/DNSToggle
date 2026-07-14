package com.ericlowry.dnstoggle.data

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
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

object DnsSettingsRepository {
	private lateinit var app: DnsToggleApplication
	private lateinit var sharedPreferences: SharedPreferences
	private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

	private val _blacklist = MutableStateFlow<Set<String>?>(null)
	val blacklist: StateFlow<Set<String>?> = _blacklist.asStateFlow()

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

			if (resultList.isEmpty()) {
				val legacyHostname = Settings.Global.getString(
					app.contentResolver,
					Constants.SETTINGS_PRIVATE_DNS_SPECIFIER
				)
				if (!legacyHostname.isNullOrEmpty() && NetworkUtils.isValidDnsHostname(
						legacyHostname
					)
				) {
					val entry = DnsHostname(legacyHostname)
					resultList.add(entry)
					saveHostnamesAsync(resultList)
				}
			}
			_dnsHostnames.value = resultList
		}
	}

	fun resetKeyInvalidated() {
		_isKeyInvalidated.value = false
	}

	fun addToBlacklist(ssid: String) {
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
	}

	fun updateSsidInBlacklist(oldSsid: String, newSsid: String) {
		_blacklist.update { current ->
			val safeCurrent = current ?: emptySet()
			if (oldSsid !in safeCurrent) return@update safeCurrent
			val next = safeCurrent - oldSsid + newSsid
			saveBlacklistAsync(next)
			next
		}
	}

	private fun saveBlacklistAsync(list: Set<String>) {
		scope.launch {
			val encryptedSet = list.map { EncryptionManager.encrypt(it) }.toSet()
			sharedPreferences.edit { putStringSet(Constants.PREF_SSID_BLACKLIST, encryptedSet) }
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
				safeCurrent + newEntry
			}

			saveHostnamesAsync(next)
			next
		}
	}

	fun removeHostname(hostname: String) {
		_dnsHostnames.update { current ->
			val safeCurrent = current ?: emptyList()
			if (safeCurrent.none { it.hostname == hostname } || safeCurrent.size <= 1) return@update safeCurrent
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
}
