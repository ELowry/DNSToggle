package com.ericlowry.dnstoggle.data

import org.json.JSONObject

data class DnsHostname(
	val hostname: String,
	val label: String? = null,
	@Transient val isUnsaved: Boolean = false
) {
	init {
		require(hostname.isNotBlank()) { "Hostname cannot be blank" }
	}

	fun toSerializedString(): String {
		return if (label == null) {
			hostname
		} else {
			val json = JSONObject()
			json.put("h", hostname)
			json.put("l", label)
			"j:$json"
		}
	}

	fun getDisplayName(): String {
		return label ?: hostname
	}

	companion object {
		fun fromSerializedString(serialized: String): DnsHostname {
			return if (serialized.startsWith("j:")) {
				try {
					val json = JSONObject(serialized.substring(2))
					DnsHostname(
						hostname = json.getString("h"),
						label = json.optString("l").takeIf { it.isNotEmpty() },
					)
				} catch (_: Exception) {
					DnsHostname(hostname = serialized)
				}
			} else {
				DnsHostname(hostname = serialized)
			}
		}
	}
}
