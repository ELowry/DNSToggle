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
import kotlinx.serialization.json.Json
import org.json.JSONArray

object NetworkProfileRepository {
	private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
	private lateinit var encryptedPrefs: SharedPreferences
	private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

	private val _networkProfiles = MutableStateFlow<List<NetworkProfile>?>(null)
	val networkProfiles: StateFlow<List<NetworkProfile>?> = _networkProfiles.asStateFlow()

	fun initialize(context: Context) {
		val app = context.applicationContext as DnsToggleApplication
		encryptedPrefs = app.getEncryptedPrefs()
		loadNetworkProfiles()
	}

	fun loadNetworkProfiles() {
		scope.launch {
			SecurityRepository.isInitialized.first { it }
			// START_LEGACY_MIGRATION_CODE: Local PREF_SSID_BLACKLIST to NetworkProfile migration
			val legacyBlacklist = encryptedPrefs.getStringSet(Constants.PREF_SSID_BLACKLIST, null)
			val legacyAutoBlacklist =
				encryptedPrefs.getStringSet(Constants.PREF_SSID_AUTO_DETECTED_BLACKLIST, null)

			if (legacyBlacklist != null || legacyAutoBlacklist != null) {
				migrateLegacyBlacklist(legacyBlacklist, legacyAutoBlacklist)
				return@launch
			}
			// END_LEGACY_MIGRATION_CODE

			val encryptedData = encryptedPrefs.getString(Constants.PREF_NETWORK_PROFILES, null)
			var resultList = listOf<NetworkProfile>()
			var keyInvalidated = false

			// START_LEGACY_MIGRATION_CODE: JSONArray of strings to List<NetworkProfile> migration
			var migratedFormat = false
			val needsPrefixMigration = encryptedData != null && !encryptedData.startsWith("enc:")
			// END_LEGACY_MIGRATION_CODE

			if (encryptedData != null) {
				when (val result = EncryptionManager.decrypt(encryptedData)) {
					is EncryptionManager.DecryptResult.Success -> {
						resultList = try {
							json.decodeFromString<List<NetworkProfile>>(result.data)
						} catch (_: Exception) {
							// START_LEGACY_MIGRATION_CODE: JSONArray of strings to List<NetworkProfile> migration
							try {
								migratedFormat = true
								val legacyList = mutableListOf<NetworkProfile>()
								val jsonArray = JSONArray(result.data)
								for (i in 0 until jsonArray.length()) {
									json.decodeFromString<NetworkProfile>(jsonArray.getString(i))
										.let { legacyList.add(it) }
								}
								legacyList
							} catch (e2: Exception) {
								Log.e("NetworkProfileRepo", "Failed to parse profiles JSON", e2)
								emptyList()
							}
							// END_LEGACY_MIGRATION_CODE
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

			// START_LEGACY_MIGRATION_CODE: JSONArray of strings to List<NetworkProfile> migration
			if ((migratedFormat || needsPrefixMigration) && resultList.isNotEmpty()) {
				saveNetworkProfilesAsync(resultList)
			}
			// END_LEGACY_MIGRATION_CODE
		}
	}

	// START_LEGACY_MIGRATION_CODE: Local PREF_SSID_BLACKLIST to NetworkProfile migration
	private fun migrateLegacyBlacklist(legacy: Set<String>?, autoDetected: Set<String>?) {
		val profiles = mutableMapOf<String, NetworkProfile>()

		legacy?.forEach { encrypted ->
			when (val result = EncryptionManager.decrypt(encrypted)) {
				is EncryptionManager.DecryptResult.Success -> {
					profiles[result.data] = NetworkProfile(
						ssid = result.data,
						isEnabled = false,
						targetHostname = null,
						isAutoDetected = false
					)
				}

				is EncryptionManager.DecryptResult.KeyInvalidated -> SecurityRepository.setKeyInvalidated(
					true
				)

				else -> {}
			}
		}

		autoDetected?.forEach { encrypted ->
			when (val result = EncryptionManager.decrypt(encrypted)) {
				is EncryptionManager.DecryptResult.Success -> {
					val existing = profiles[result.data]
					profiles[result.data] = existing?.copy(isAutoDetected = true)
						?: NetworkProfile(
							ssid = result.data,
							isEnabled = false,
							targetHostname = null,
							isAutoDetected = true
						)
				}

				is EncryptionManager.DecryptResult.KeyInvalidated -> SecurityRepository.setKeyInvalidated(
					true
				)

				else -> {}
			}
		}

		val profileList = profiles.values.toList()
		_networkProfiles.value = profileList
		saveNetworkProfilesAsync(profileList)

		encryptedPrefs.edit {
			remove(Constants.PREF_SSID_BLACKLIST)
			remove(Constants.PREF_SSID_AUTO_DETECTED_BLACKLIST)
		}
	}
	// END_LEGACY_MIGRATION_CODE

	fun promoteUnsavedProfile(ssid: String) {
		_networkProfiles.update { current ->
			val safeCurrent = current ?: emptyList()
			val index = safeCurrent.indexOfFirst { it.ssid == ssid }
			if (index == -1 || (!safeCurrent[index].isAutoDetected && !safeCurrent[index].isUnsaved)) return@update safeCurrent

			val next = safeCurrent.toMutableList()
			next[index] = next[index].copy(isAutoDetected = false, isUnsaved = false)
			saveNetworkProfilesAsync(next)
			next
		}
	}

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
					targetHostname = if (preserveExistingHostname) existing.targetHostname else targetHostname,
					targetMode = if (preserveExistingMode) existing.targetMode else targetMode,
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

			if (index != -1) next[index] = newProfile else next.add(newProfile)
			saveNetworkProfilesAsync(next)
			next
		}
	}

	fun removeNetworkProfile(ssid: String) {
		_networkProfiles.update { current ->
			val safeCurrent = current ?: emptyList()
			if (safeCurrent.none { it.ssid == ssid }) return@update safeCurrent
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
			val jsonString = json.encodeToString(list)
			val encrypted = EncryptionManager.encrypt(jsonString)
			encryptedPrefs.edit { putString(Constants.PREF_NETWORK_PROFILES, encrypted) }
		}
	}

	internal fun updateProfilesOnHostnameRemoval(hostname: String) {
		_networkProfiles.update { current ->
			val safeCurrent = current ?: emptyList()
			val next = safeCurrent.map {
				if (it.targetHostname == hostname) it.copy(targetHostname = null) else it
			}
			if (next != safeCurrent) {
				saveNetworkProfilesAsync(next)
			}
			next
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
}
