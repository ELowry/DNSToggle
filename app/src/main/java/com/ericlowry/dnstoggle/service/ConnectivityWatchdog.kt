package com.ericlowry.dnstoggle.service

import com.ericlowry.dnstoggle.data.Constants
import com.ericlowry.dnstoggle.util.NetworkUtils
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

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
		if (!isNetworkReachable()) {
			return false
		}
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

		val effectiveTargets = targets.ifEmpty {
			Constants.CONNECTIVITY_WATCHDOG_DEFAULT_PROBE_TARGETS.split(",")
				.map { it.trim() }
				.filter { it.isNotEmpty() }
		}

		val resultChannel = Channel<Boolean>()

		effectiveTargets.forEach { target ->
			launch {
				resultChannel.send(NetworkUtils.isHostReachable(target, 443))
			}
		}

		var isConnected = false
		var receivedCount = 0
		while (receivedCount < effectiveTargets.size) {
			if (resultChannel.receive()) {
				isConnected = true
				break
			}
			receivedCount++
		}

		coroutineContext.cancelChildren()

		return@coroutineScope isConnected
	}
}
