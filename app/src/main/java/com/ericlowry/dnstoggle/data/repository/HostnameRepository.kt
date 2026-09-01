package com.ericlowry.dnstoggle.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.ericlowry.dnstoggle.DnsToggleApplication
import com.ericlowry.dnstoggle.data.Constants
import com.ericlowry.dnstoggle.data.DnsHostname
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

/**
 * Repository for managing the list of saved DNS hostnames.
 * Hostnames are stored in encrypted SharedPreferences.
 */
object HostnameRepository {
	private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
	private lateinit var sharedPreferences: SharedPreferences
	private lateinit var encryptedPrefs: SharedPreferences
	private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

	private val _dnsHostnames = MutableStateFlow<List<DnsHostname>?>(null)
	val dnsHostnames: StateFlow<List<DnsHostname>?> = _dnsHostnames.asStateFlow()

	/**
	 * Initializes the repository and loads saved hostnames.
	 */
	fun initialize(context: Context) {
		val app = context.applicationContext as DnsToggleApplication
		sharedPreferences = app.getPrefs()
		encryptedPrefs = app.getEncryptedPrefs()
		loadHostnames()
	}

	fun loadHostnames() {
		scope.launch {
			SecurityRepository.isInitialized.first { it }
			val rawData = encryptedPrefs.all[Constants.PREF_DNS_HOSTNAMES]

			var keyInvalidated = false
			var resultList = mutableListOf<DnsHostname>()

			// START_LEGACY_MIGRATION_CODE: Legacy StringSet to JSON Array hostname migration
			var migratedFormat = false
			val needsPrefixMigration = (rawData is String) && !rawData.startsWith("enc:")
			if (rawData is Set<*>) {
				@Suppress("UNCHECKED_CAST")
				val encryptedSet = rawData as? Set<String> ?: emptySet()
				encryptedSet.forEach {
					when (val result = EncryptionManager.decrypt(it)) {
						is EncryptionManager.DecryptResult.Success -> {
							val entry = result.data
							if (entry.startsWith("j:")) {
								try {
									resultList.add(
										json.decodeFromString<DnsHostname>(
											entry.substring(
												2
											)
										)
									)
								} catch (_: Exception) {
									resultList.add(DnsHostname(hostname = entry))
								}
							} else {
								resultList.add(DnsHostname(hostname = entry))
							}
						}

						is EncryptionManager.DecryptResult.KeyInvalidated -> keyInvalidated = true
						else -> {}
					}
				}
				resultList.sortWith { a, b ->
					String.CASE_INSENSITIVE_ORDER.compare(
						a.getDisplayName(),
						b.getDisplayName()
					)
				}
				if (resultList.isNotEmpty() && !keyInvalidated) {
					saveHostnamesAsync(resultList)
				}
			}
			// END_LEGACY_MIGRATION_CODE

			if (rawData is String) {
				when (val result = EncryptionManager.decrypt(rawData)) {
					is EncryptionManager.DecryptResult.Success -> {
						try {
							resultList =
								json.decodeFromString<MutableList<DnsHostname>>(result.data)
						} catch (_: Exception) {
							// START_LEGACY_MIGRATION_CODE: JSONArray of strings to List<DnsHostname> migration
							try {
								val jsonArray = JSONArray(result.data)
								migratedFormat = true
								for (i in 0 until jsonArray.length()) {
									val entry = jsonArray.getString(i)
									if (entry.startsWith("j:")) {
										resultList.add(
											json.decodeFromString<DnsHostname>(
												entry.substring(
													2
												)
											)
										)
									} else {
										resultList.add(DnsHostname(hostname = entry))
									}
								}
							} catch (e2: Exception) {
								Log.e("HostnameRepository", "Failed to parse hostnames JSON", e2)
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
				encryptedPrefs.edit {
					remove(Constants.PREF_NETWORK_PROFILES)
					remove(Constants.PREF_DNS_HOSTNAMES)
					remove(Constants.PREF_LAST_USED_HOSTNAME)
				}
			}

			_dnsHostnames.value = resultList

			// START_LEGACY_MIGRATION_CODE: Legacy StringSet to JSON Array hostname migration
			if ((migratedFormat || needsPrefixMigration) && resultList.isNotEmpty()) {
				saveHostnamesAsync(resultList)
			}
			// END_LEGACY_MIGRATION_CODE
		}
	}

	/**
	 * Adds a new hostname to the repository or updates an existing one.
	 */
	fun addHostname(hostname: String, label: String? = null) {
		_dnsHostnames.update { current ->
			val safeCurrent = current ?: emptyList()
			val newEntry = DnsHostname(hostname, label)

			val existingIndex = safeCurrent.indexOfFirst { it.hostname == hostname }
			val next = if (existingIndex != -1) {
				safeCurrent.mapIndexed { index, dnsHostname ->
					if (index == existingIndex) {
						newEntry
					} else {
						dnsHostname
					}
				}
			} else {
				listOf(newEntry) + safeCurrent
			}

			saveHostnamesAsync(next)
			next
		}
	}

	/**
	 * Removes a hostname from the repository.
	 * Also handles fallback logic if the removed hostname was active in VPN override.
	 */
	fun removeHostname(hostname: String) {
		_dnsHostnames.update { current ->
			val safeCurrent = current ?: emptyList()
			if (safeCurrent.none { it.hostname == hostname }) {
				return@update safeCurrent
			}
			val next = safeCurrent.filter { it.hostname != hostname }
			val offMode = sharedPreferences.getString(
				Constants.PREF_DEFAULT_OFF_MODE,
				Constants.DNS_MODE_OPPORTUNISTIC
			) ?: Constants.DNS_MODE_OPPORTUNISTIC

			if (VpnRepository.vpnDnsHostname.value == hostname) {
				VpnRepository.updateVpnDns(offMode, null)
				sharedPreferences.edit {
					putBoolean(Constants.PREF_VPN_HOSTNAME_REMOVED_WARNING, true)
				}
			} else if (next.size == 1 && VpnRepository.vpnDnsHostname.value != null) {
				VpnRepository.updateVpnDns(offMode, null)
			}

			saveHostnamesAsync(next)
			next
		}

		NetworkProfileRepository.updateProfilesOnHostnameRemoval(hostname)
	}

	fun updateHostname(oldHostname: String, newHostname: String, newLabel: String? = null) {
		_dnsHostnames.update { current ->
			val safeCurrent = current ?: emptyList()
			val next = safeCurrent.map {
				if (it.hostname == oldHostname) {
					DnsHostname(newHostname, newLabel)
				} else {
					it
				}
			}

			if (VpnRepository.vpnDnsHostname.value == oldHostname) {
				VpnRepository.updateVpnDns(Constants.DNS_MODE_HOSTNAME, newHostname)
			}

			saveHostnamesAsync(next)
			next
		}
	}

	fun updateHostnamesOrder(newList: List<DnsHostname>) {
		_dnsHostnames.value = newList
		saveHostnamesAsync(newList)
	}

	fun saveHostnamesAsync(list: List<DnsHostname>) {
		scope.launch {
			val jsonString = json.encodeToString(list)
			val encrypted = EncryptionManager.encrypt(jsonString)
			encryptedPrefs.edit { putString(Constants.PREF_DNS_HOSTNAMES, encrypted) }
		}
	}
}
