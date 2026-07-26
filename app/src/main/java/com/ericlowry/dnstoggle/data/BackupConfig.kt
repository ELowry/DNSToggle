package com.ericlowry.dnstoggle.data

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
	val showToast: Boolean = false
)
