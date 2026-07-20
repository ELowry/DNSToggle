package com.ericlowry.dnstoggle.service

import com.ericlowry.dnstoggle.util.NetworkUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

object ConnectivityWatchdog {

	suspend fun isDnsSpecificFailure(
		dnsHostname: String,
		probeTargetsStr: String,
		isNetworkReachable: suspend () -> Boolean = { probeNetwork(probeTargetsStr) },
		isDnsHostReachable: suspend () -> Boolean = {
			NetworkUtils.isHostReachable(
				dnsHostname,
				853
			)
		}
	): Boolean {
		if (!isNetworkReachable()) return false
		return !isDnsHostReachable()
	}

	suspend fun isRecovered(
		dnsHostname: String,
		probeTargetsStr: String,
		isNetworkReachable: suspend () -> Boolean = { probeNetwork(probeTargetsStr) },
		isDnsHostReachable: suspend () -> Boolean = {
			NetworkUtils.isHostReachable(
				dnsHostname,
				853
			)
		}
	): Boolean {
		return isNetworkReachable() && isDnsHostReachable()
	}

	private suspend fun probeNetwork(targetsStr: String): Boolean = coroutineScope {
		val targets = targetsStr.split(",")
			.map { it.trim() }
			.filter { it.isNotEmpty() }

		val effectiveTargets = targets.ifEmpty { listOf("9.9.9.9") }

		effectiveTargets.map { target ->
			async { NetworkUtils.isHostReachable(target, 443) }
		}.awaitAll().any { it }
	}
}
