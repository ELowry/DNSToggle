package com.ericlowry.dnstoggle.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NetworkProfile(
	@SerialName("s") val ssid: String,
	@SerialName("e") val isEnabled: Boolean,
	@SerialName("h") val targetHostname: String? = null,
	@SerialName("a") val isAutoDetected: Boolean = false,
	@SerialName("u") val isUnsaved: Boolean = false
) {
	init {
		require(ssid.isNotBlank()) { "SSID cannot be blank" }
	}
}
