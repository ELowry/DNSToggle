package com.ericlowry.dnstoggle.service

import android.content.Context
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.Settings.Global
import com.ericlowry.dnstoggle.DnsToggleApplication
import com.ericlowry.dnstoggle.data.Constants
import com.ericlowry.dnstoggle.data.repository.NetworkProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Manages the lifecycle and execution of connectivity watchdog jobs.
 */
class ConnectivityWatchdogManager(
	private val context: Context,
	private val serviceScope: CoroutineScope,
	private val isDnsSpecificFailureFunc: suspend (String, String) -> Boolean = { hostname, targets ->
		ConnectivityWatchdog.isDnsSpecificFailure(hostname, targets)
	},
	private val isRecoveredFunc: suspend (String, String) -> Boolean = { hostname, targets ->
		ConnectivityWatchdog.isRecovered(hostname, targets)
	}
) {
	private var debounceJob: Job? = null
	private var connectivityWatchdogJob: Job? = null
	private var autoRecoveryJob: Job? = null

	/**
	 * BSSID of the last network where auto-recovery was attempted.
	 */
	var retriedAutoProfileBssid: String? = null

	/**
	 * Cancels all active watchdog and debounce jobs.
	 */
	fun cancelAll() {
		debounceJob?.cancel()
		connectivityWatchdogJob?.cancel()
		autoRecoveryJob?.cancel()
		debounceJob = null
		connectivityWatchdogJob = null
		autoRecoveryJob = null
	}

	/**
	 * Cancels only the active restoration debounce job.
	 */
	fun cancelDebounce() {
		debounceJob?.cancel()
		debounceJob = null
	}

	/**
	 * Evaluates if the connectivity watchdog should be started for the current network.
	 */
	fun evaluateConnectivityWatchdog(
		ssid: String,
		wifiCaps: NetworkCapabilities?,
		activeNetworks: Map<Network, NetworkCapabilities>,
		cachedDnsMode: String?
	) {
		val prefs = getPrefs()
		val watchdogEnabled = prefs.getBoolean(Constants.PREF_CONNECTIVITY_WATCHDOG_ENABLED, false)
		if (!watchdogEnabled || cachedDnsMode != Constants.DNS_MODE_HOSTNAME) {
			connectivityWatchdogJob?.cancel()
			connectivityWatchdogJob = null
			return
		}

		if (wifiCaps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) {
			connectivityWatchdogJob?.cancel()
			connectivityWatchdogJob = null
			return
		}

		if (connectivityWatchdogJob?.isActive == true) {
			return
		}

		val hostname =
			Global.getString(context.contentResolver, Constants.SETTINGS_PRIVATE_DNS_SPECIFIER)
		if (hostname.isNullOrEmpty()) {
			return
		}

		val debounceSeconds = prefs.getInt(
			Constants.PREF_CONNECTIVITY_WATCHDOG_DEBOUNCE_SECONDS,
			Constants.CONNECTIVITY_WATCHDOG_DEFAULT_DEBOUNCE_SECONDS
		)
		val probeTargets = prefs.getString(
			Constants.PREF_CONNECTIVITY_WATCHDOG_PROBE_TARGETS,
			Constants.CONNECTIVITY_WATCHDOG_DEFAULT_PROBE_TARGETS
		) ?: Constants.CONNECTIVITY_WATCHDOG_DEFAULT_PROBE_TARGETS

		connectivityWatchdogJob = serviceScope.launch {
			delay(debounceSeconds.seconds)

			// Re-verify validation state after debounce to prevent race conditions if the network gets validated during the delay.
			val stillNotValidated = activeNetworks.values
				.find {
					it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
				}
				?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true

			if (!stillNotValidated ||
				cachedDnsMode != Constants.DNS_MODE_HOSTNAME ||
				(context.applicationContext as DnsToggleApplication).detectedSsid != ssid ||
				!getPrefs().getBoolean(Constants.PREF_CONNECTIVITY_WATCHDOG_ENABLED, false)
			) {
				return@launch
			}

			if (isDnsSpecificFailureFunc(hostname, probeTargets)) {
				NetworkProfileRepository.upsertNetworkProfile(
					ssid = ssid,
					isEnabled = false,
					isAutoDetected = true,
					preserveExistingHostname = true,
					targetMode = Constants.DNS_MODE_OPPORTUNISTIC
				)
			}
		}
	}

	/**
	 * Checks if an auto-detected (blocked) SSID has recovered connectivity.
	 */
	fun maybeRetryAutoDetectedSsid(
		ssid: String,
		isAutoDetected: Boolean,
		bssid: String?,
		onRecovered: (String) -> Unit
	) {
		val prefs = getPrefs()
		if (!isAutoDetected ||
			!prefs.getBoolean(Constants.PREF_CONNECTIVITY_WATCHDOG_ENABLED, false) ||
			(bssid != null && retriedAutoProfileBssid == bssid)
		) {
			return
		}
		retriedAutoProfileBssid = bssid

		val hostname =
			Global.getString(context.contentResolver, Constants.SETTINGS_PRIVATE_DNS_SPECIFIER)
		if (hostname.isNullOrEmpty()) {
			return
		}

		val probeTargets = prefs.getString(
			Constants.PREF_CONNECTIVITY_WATCHDOG_PROBE_TARGETS,
			Constants.CONNECTIVITY_WATCHDOG_DEFAULT_PROBE_TARGETS
		) ?: Constants.CONNECTIVITY_WATCHDOG_DEFAULT_PROBE_TARGETS

		autoRecoveryJob?.cancel()
		autoRecoveryJob = serviceScope.launch {
			if (isRecoveredFunc(hostname, probeTargets)) {
				NetworkProfileRepository.removeNetworkProfile(ssid)
				onRecovered(ssid)
			}
		}
	}

	/**
	 * Schedules the restoration of the preferred DNS setting with a debounce delay.
	 */
	fun restorePreferredDns(immediate: Boolean, onRestore: suspend () -> Unit) {
		debounceJob?.cancel()
		if (immediate) {
			serviceScope.launch {
				onRestore()
			}
		} else {
			debounceJob = serviceScope.launch {
				delay(Constants.WATCHDOG_RESTORE_DEBOUNCE_MS.milliseconds) // Wait to avoid rapid ping-pong
				onRestore()
			}
		}
	}

	private fun getPrefs() = (context.applicationContext as DnsToggleApplication).getPrefs()
}
