package com.ericlowry.dnstoggle.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.ericlowry.dnstoggle.DnsToggleApplication
import com.ericlowry.dnstoggle.data.Constants
import com.ericlowry.dnstoggle.util.EncryptionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object VpnRepository {
	private lateinit var sharedPreferences: SharedPreferences
	private lateinit var encryptedPrefs: SharedPreferences
	private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

	private val _vpnOverrideEnabled = MutableStateFlow(false)
	val vpnOverrideEnabled: StateFlow<Boolean> = _vpnOverrideEnabled.asStateFlow()

	private val _vpnDnsHostname = MutableStateFlow<String?>(null)
	val vpnDnsHostname: StateFlow<String?> = _vpnDnsHostname.asStateFlow()

	fun initialize(context: Context) {
		val app = context.applicationContext as DnsToggleApplication
		sharedPreferences = app.getPrefs()
		encryptedPrefs = app.getEncryptedPrefs()

		_vpnOverrideEnabled.value =
			sharedPreferences.getBoolean(Constants.PREF_VPN_OVERRIDE_ENABLED, false)

		scope.launch {
			val encryptedVpnHostname =
				encryptedPrefs.getString(Constants.PREF_VPN_DNS_HOSTNAME, null)
			_vpnDnsHostname.value = if (encryptedVpnHostname == null) {
				null
			} else {
				when (val result = EncryptionManager.decrypt(encryptedVpnHostname)) {
					is EncryptionManager.DecryptResult.Success -> {
						// START_LEGACY_MIGRATION_CODE: VPN DNS 'off' string migration
						if (result.data == "off") {
							// Migration from app versions <1.6
							encryptedPrefs.edit { remove(Constants.PREF_VPN_DNS_HOSTNAME) }
							null
						} else {
							result.data
						}
						// END_LEGACY_MIGRATION_CODE
					}

					is EncryptionManager.DecryptResult.KeyInvalidated -> {
						SecurityRepository.setKeyInvalidated(true)
						null
					}

					else -> null
				}
			}
		}
	}

	fun updateVpnOverrideEnabled(enabled: Boolean) {
		_vpnOverrideEnabled.value = enabled
		sharedPreferences.edit { putBoolean(Constants.PREF_VPN_OVERRIDE_ENABLED, enabled) }
	}

	fun updateVpnDnsHostname(hostname: String?) {
		_vpnDnsHostname.value = hostname
		if (hostname == null) {
			encryptedPrefs.edit { remove(Constants.PREF_VPN_DNS_HOSTNAME) }
		} else {
			val encrypted = EncryptionManager.encrypt(hostname)
			encryptedPrefs.edit { putString(Constants.PREF_VPN_DNS_HOSTNAME, encrypted) }
		}
	}
}
