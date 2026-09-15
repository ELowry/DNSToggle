package com.ericlowry.dnstoggle.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.ericlowry.dnstoggle.DnsToggleApplication
import com.ericlowry.dnstoggle.data.Constants
import com.ericlowry.dnstoggle.data.NetworkProfile
import com.ericlowry.dnstoggle.util.EncryptionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Repository for managing network-specific (SSID) DNS profiles.
 * Profiles allow automatic DNS switching when connecting to specific Wi-Fi networks.
 */
object NetworkProfileRepository {
	private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
	private lateinit var encryptedPrefs: SharedPreferences
	private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
	private val saveMutex = Mutex()

	private val _networkProfiles = MutableStateFlow<List<NetworkProfile>?>(null)
	val networkProfiles: StateFlow<List<NetworkProfile>?> = _networkProfiles.asStateFlow()

	/**
	 * Initializes the repository and loads saved profiles.
	 */
	fun initialize(context: Context) {
		val app = context.applicationContext as DnsToggleApplication
		encryptedPrefs = app.getEncryptedPrefs()
		loadNetworkProfiles()
	}

	fun loadNetworkProfiles() {
		scope.launch {
			SecurityRepository.isInitialized.first {
				it
			}

			val encryptedData = encryptedPrefs.getString(Constants.PREF_NETWORK_PROFILES, null)
			var resultList = listOf<NetworkProfile>()
			var keyInvalidated = false

			if (encryptedData != null) {
				when (val result = EncryptionManager.decrypt(encryptedData)) {
					is EncryptionManager.DecryptResult.Success -> {
						resultList = try {
							json.decodeFromString<List<NetworkProfile>>(result.data)
						} catch (e: Exception) {
							Log.e("NetworkProfileRepo", "Failed to parse profiles JSON", e)
							emptyList()
						}
					}

					is EncryptionManager.DecryptResult.KeyInvalidated -> keyInvalidated = true
					else -> {}
				}
			}

			if (keyInvalidated) {
				SecurityRepository.setKeyInvalidated(true)
				encryptedPrefs.edit { remove(Constants.PREF_NETWORK_PROFILES) }
			}
			_networkProfiles.value = resultList
		}
	}

	/**
	 * Promotes an auto-detected or unsaved profile to a permanent one.
	 */
	fun promoteUnsavedProfile(ssid: String) {
		_networkProfiles.update { current ->
			val safeCurrent = current ?: emptyList()
			val index = safeCurrent.indexOfFirst { it.ssid == ssid }
			if (index == -1 || (!safeCurrent[index].isAutoDetected && !safeCurrent[index].isUnsaved)) {
				return@update safeCurrent
			}

			val next = safeCurrent.toMutableList()
			next[index] = next[index].copy(isAutoDetected = false, isUnsaved = false)
			saveNetworkProfilesAsync(next)
			next
		}
	}

	/**
	 * Adds or updates a network profile.
	 */
	fun upsertNetworkProfile(
		ssid: String,
		isEnabled: Boolean,
		targetHostname: String? = null,
		isAutoDetected: Boolean = false,
		isUnsaved: Boolean = false,
		preserveExistingHostname: Boolean = false,
		targetMode: String? = null,
		preserveExistingMode: Boolean = false
	) {
		_networkProfiles.update { current ->
			val safeCurrent = current ?: emptyList()
			val index = safeCurrent.indexOfFirst { it.ssid == ssid }
			val next = safeCurrent.toMutableList()

			val newProfile = if (index != -1) {
				val existing = safeCurrent[index]
				existing.copy(
					isEnabled = isEnabled,
					targetHostname = if (preserveExistingHostname) {
						existing.targetHostname
					} else {
						targetHostname
					},
					targetMode = if (preserveExistingMode) {
						existing.targetMode
					} else {
						targetMode
					},
					isAutoDetected = isAutoDetected,
					isUnsaved = isUnsaved
				)
			} else {
				NetworkProfile(
					ssid,
					isEnabled,
					targetHostname,
					isAutoDetected,
					isUnsaved,
					targetMode
				)
			}

			if (index != -1) {
				next[index] = newProfile
			} else {
				next.add(newProfile)
			}
			saveNetworkProfilesAsync(next)
			next
		}
	}

	fun removeNetworkProfile(ssid: String) {
		_networkProfiles.update { current ->
			val safeCurrent = current ?: emptyList()
			if (safeCurrent.none { it.ssid == ssid }) {
				return@update safeCurrent
			}
			val next = safeCurrent.filter { it.ssid != ssid }
			saveNetworkProfilesAsync(next)
			next
		}
	}

	fun updateNetworkProfilesFromBackup(list: List<NetworkProfile>) {
		_networkProfiles.value = list
		saveNetworkProfilesAsync(list)
	}

	fun updateNetworkProfilesOrder(newList: List<NetworkProfile>) {
		_networkProfiles.value = newList
		saveNetworkProfilesAsync(newList)
	}

	fun saveNetworkProfilesAsync(list: List<NetworkProfile>) {
		scope.launch {
			saveMutex.withLock {
				val jsonString = json.encodeToString(list)
				val encrypted = EncryptionManager.encrypt(jsonString)
				encryptedPrefs.edit { putString(Constants.PREF_NETWORK_PROFILES, encrypted) }
			}
		}
	}

	fun sanitizeStrictOffProfiles() {
		_networkProfiles.update { current ->
			val safeCurrent = current ?: emptyList()
			var changed = false
			val next = safeCurrent.map {
				if (it.targetMode == Constants.DNS_MODE_OFF) {
					changed = true
					it.copy(targetMode = Constants.DNS_MODE_OPPORTUNISTIC)
				} else {
					it
				}
			}
			if (changed) {
				saveNetworkProfilesAsync(next)
			}
			next
		}
	}

	internal fun updateProfilesOnHostnameRemoval(hostname: String) {
		_networkProfiles.update { current ->
			val safeCurrent = current ?: emptyList()
			val next = safeCurrent.map {
				if (it.targetHostname == hostname) {
					it.copy(targetHostname = null)
				} else {
					it
				}
			}
			if (next != safeCurrent) {
				saveNetworkProfilesAsync(next)
			}
			next
		}
	}

}
