package com.ericlowry.dnstoggle.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class DnsHostname(
	@SerialName("h") val hostname: String,
	@SerialName("l") val label: String? = null,
	@Transient val isUnsaved: Boolean = false
) {
	init {
		require(hostname.isNotBlank()) { "Hostname cannot be blank" }
	}

	fun getDisplayName(): String {
		return label ?: hostname
	}
}
