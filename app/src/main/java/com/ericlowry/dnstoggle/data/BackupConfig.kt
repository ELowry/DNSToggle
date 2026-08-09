package com.ericlowry.dnstoggle.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BackupConfig(
	val hostnames: List<DnsHostname> = emptyList(),
	val networkProfiles: List<NetworkProfile> = emptyList(),
	val autoSaveState: Boolean = false,
	val autoSaveHost: Boolean = false,
	val vpnOverride: Boolean = false,
	val vpnDns: String? = null,
	val hideLauncherIcon: Boolean = false,
	val disableDnsTest: Boolean = false,
	val showToast: Boolean = false,
	@SerialName("eso") val enableStrictOff: Boolean = false,
	@SerialName("dom") val defaultOffMode: String = Constants.DNS_MODE_OPPORTUNISTIC,
	@SerialName("vdm") val vpnDnsMode: String = Constants.DNS_MODE_OPPORTUNISTIC
)
