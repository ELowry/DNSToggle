package com.ericlowry.dnstoggle

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.core.content.edit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object DnsSettingsRepository {
	private lateinit var app: DnsToggleApplication
	private lateinit var sharedPreferences: SharedPreferences
	private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

	private val _blacklist = MutableStateFlow<Set<String>?>(null)
	val blacklist: StateFlow<Set<String>?> = _blacklist.asStateFlow()

	private val _dnsHostnames = MutableStateFlow<Set<String>?>(null)
	val dnsHostnames: StateFlow<Set<String>?> = _dnsHostnames.asStateFlow()

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
			val encryptedSet =
				sharedPreferences.getStringSet(Constants.PREF_DNS_HOSTNAMES, emptySet())
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
				sharedPreferences.edit {
					remove(Constants.PREF_SSID_BLACKLIST)
					remove(Constants.PREF_DNS_HOSTNAMES)
					remove(Constants.PREF_LAST_USED_HOSTNAME)
				}
			}

			var resultHostnames = decryptedSet
			if (resultHostnames.isEmpty()) {
				val legacyHostname = Settings.Global.getString(
					app.contentResolver,
					Constants.SETTINGS_PRIVATE_DNS_SPECIFIER
				)
				if (!legacyHostname.isNullOrEmpty() && NetworkUtils.isValidDnsHostname(
						legacyHostname
					)
				) {
					resultHostnames = setOf(legacyHostname)
					saveHostnamesAsync(resultHostnames)
				}
			}
			_dnsHostnames.value = resultHostnames
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

	fun addHostname(hostname: String) {
		_dnsHostnames.update { current ->
			val safeCurrent = current ?: emptySet()
			if (hostname in safeCurrent) return@update safeCurrent
			val next = safeCurrent + hostname
			saveHostnamesAsync(next)
			next
		}
	}

	fun removeHostname(hostname: String) {
		_dnsHostnames.update { current ->
			val safeCurrent = current ?: emptySet()
			if (hostname !in safeCurrent || safeCurrent.size <= 1) return@update safeCurrent
			val next = safeCurrent - hostname

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

	fun updateHostname(oldHostname: String, newHostname: String) {
		_dnsHostnames.update { current ->
			val safeCurrent = current ?: emptySet()
			if (oldHostname !in safeCurrent) return@update safeCurrent
			val next = safeCurrent - oldHostname + newHostname

			if (_vpnDnsHostname.value == oldHostname) {
				updateVpnDnsHostname(newHostname)
			}

			saveHostnamesAsync(next)
			next
		}
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

	private fun saveHostnamesAsync(list: Set<String>) {
		scope.launch {
			val encryptedSet = list.map { EncryptionManager.encrypt(it) }.toSet()
			sharedPreferences.edit { putStringSet(Constants.PREF_DNS_HOSTNAMES, encryptedSet) }
		}
	}
}
