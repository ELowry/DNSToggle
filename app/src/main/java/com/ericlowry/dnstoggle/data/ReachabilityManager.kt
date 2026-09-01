package com.ericlowry.dnstoggle.data

import com.ericlowry.dnstoggle.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages DNS reachability testing for hostnames.
 * Extracted from DnsViewModel to follow the singleton manager pattern.
 */
object ReachabilityManager {

	enum class ReachabilityState { IDLE, TESTING, REACHABLE, UNREACHABLE }

	private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
	private val reachabilityJobs = ConcurrentHashMap<String, Job>()
	private val _reachabilityStates = MutableStateFlow<Map<String, ReachabilityState>>(emptyMap())
	val reachabilityStates: StateFlow<Map<String, ReachabilityState>> =
		_reachabilityStates.asStateFlow()

	/**
	 * Tests the reachability of a DNS hostname using a TCP handshake on port 853.
	 */
	fun testHost(hostname: String, disableTest: Boolean = false) {
		reachabilityJobs[hostname]?.cancel()

		if (hostname.isEmpty() || disableTest) {
			_reachabilityStates.update { it + (hostname to ReachabilityState.IDLE) }
			return
		}

		val job = scope.launch {
			_reachabilityStates.update { it + (hostname to ReachabilityState.TESTING) }

			val isReachable = NetworkUtils.isHostReachable(hostname, 853)

			_reachabilityStates.update {
				it + (hostname to (if (isReachable) ReachabilityState.REACHABLE else ReachabilityState.UNREACHABLE))
			}
		}
		reachabilityJobs[hostname] = job
	}

	/**
	 * Cancels any ongoing test for the hostname and removes its state.
	 */
	fun clearHost(hostname: String) {
		reachabilityJobs[hostname]?.cancel()
		reachabilityJobs.remove(hostname)
		_reachabilityStates.update { it - hostname }
	}
}
