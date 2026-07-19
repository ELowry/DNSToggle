package com.ericlowry.dnstoggle.service

import com.ericlowry.dnstoggle.util.NetworkUtils

object ConnectivityWatchdog {
	private val PROBE_TARGETS = listOf("1.1.1.1" to 443, "8.8.8.8" to 443)

	suspend fun isDnsSpecificFailure(
		dnsHostname: String,
		isNetworkReachable: suspend () -> Boolean = { probeNetwork() },
		isDnsHostReachable: suspend () -> Boolean = { NetworkUtils.isHostReachable(dnsHostname, 853) }
	): Boolean {
		if (!isNetworkReachable()) return false
		return !isDnsHostReachable()
	}

	suspend fun isRecovered(
		dnsHostname: String,
		isNetworkReachable: suspend () -> Boolean = { probeNetwork() },
		isDnsHostReachable: suspend () -> Boolean = { NetworkUtils.isHostReachable(dnsHostname, 853) }
	): Boolean {
		return isNetworkReachable() && isDnsHostReachable()
	}

	private suspend fun probeNetwork(): Boolean {
		for ((host, port) in PROBE_TARGETS) {
			if (NetworkUtils.isHostReachable(host, port)) return true
		}
		return false
	}
}
