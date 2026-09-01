package com.ericlowry.dnstoggle.data.repository

import android.content.Context
import androidx.core.content.edit
import com.ericlowry.dnstoggle.DnsToggleApplication
import com.ericlowry.dnstoggle.data.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object SecurityRepository {
	private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
	private val _isInitialized = MutableStateFlow(false)
	val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

	private val _isKeyInvalidated = MutableStateFlow(false)
	val isKeyInvalidated: StateFlow<Boolean> = _isKeyInvalidated.asStateFlow()

	fun initialize(context: Context) {
		scope.launch {
			val app = context.applicationContext as DnsToggleApplication
			val sharedPreferences = app.getPrefs()
			val encryptedPrefs = app.getEncryptedPrefs()

			// START_LEGACY_MIGRATION_CODE: Move unencrypted preferences to encrypted storage
			val keysToMigrate = listOf(
				Constants.PREF_DNS_HOSTNAMES,
				Constants.PREF_SSID_BLACKLIST,
				Constants.PREF_SSID_AUTO_DETECTED_BLACKLIST,
				Constants.PREF_LAST_USED_HOSTNAME,
				Constants.PREF_VPN_DNS_HOSTNAME
			)

			sharedPreferences.all.forEach { (key, value) ->
				if (key in keysToMigrate) {
					encryptedPrefs.edit {
						when (value) {
							is String -> putString(
								key,
								com.ericlowry.dnstoggle.util.EncryptionManager.encrypt(value)
							)

							is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(
								key,
								value as Set<String>
							)
						}
					}
					sharedPreferences.edit { remove(key) }
				}
			}

			val standaloneKeys =
				listOf(Constants.PREF_LAST_USED_HOSTNAME, Constants.PREF_VPN_DNS_HOSTNAME)
			standaloneKeys.forEach { key ->
				val value = encryptedPrefs.getString(key, null)
				if (value != null && !value.startsWith("enc:")) {
					encryptedPrefs.edit {
						putString(
							key,
							com.ericlowry.dnstoggle.util.EncryptionManager.encrypt(value)
						)
					}
				}
			}
			// END_LEGACY_MIGRATION_CODE
			_isInitialized.value = true
		}
	}

	fun setKeyInvalidated(invalidated: Boolean) {
		_isKeyInvalidated.value = invalidated
	}

	fun resetKeyInvalidated() {
		_isKeyInvalidated.value = false
	}
}
