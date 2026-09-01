package com.ericlowry.dnstoggle.data

import android.content.ComponentName
import android.content.Context
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.provider.Settings.Global
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import com.ericlowry.dnstoggle.DnsToggleApplication
import com.ericlowry.dnstoggle.R
import com.ericlowry.dnstoggle.data.repository.NetworkProfileRepository
import com.ericlowry.dnstoggle.data.repository.VpnRepository
import com.ericlowry.dnstoggle.service.ConnectivityWatchdogManager
import com.ericlowry.dnstoggle.service.DnsToggleService
import com.ericlowry.dnstoggle.service.TileServiceCompat
import com.ericlowry.dnstoggle.util.EncryptionManager
import com.ericlowry.dnstoggle.util.NetworkUtils
import com.ericlowry.dnstoggle.util.NotificationUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Handles the logic for deciding which DNS configuration to apply based on current network state.
 * Extracted from WifiMonitoringService to separate policy from Android lifecycle management.
 */
object DnsPolicyEvaluator {
	private const val TAG = "DnsPolicyEvaluator"

	private var dnsSettleJob: Job? = null
	private var isTransitioning = false
	private var lastBssid: String? = null
	private var lastNotifiedSsid: String? = null
	private var hasShownLocationWarning: Boolean = false
	private val evaluationMutex = Mutex()

	/**
	 * Evaluates the current network state and applies DNS policies.
	 */
	fun evaluate(
		context: Context,
		serviceScope: CoroutineScope,
		network: CurrentNetwork,
		activeNetworks: Map<Network, NetworkCapabilities>,
		watchdogManager: ConnectivityWatchdogManager,
		cachedDnsMode: String?
	) {
		serviceScope.launch {
			evaluationMutex.withLock {
				val app = context.applicationContext as DnsToggleApplication
				val prefs = app.getPrefs()

				val currentSsid = network.ssid
				val currentBssid = network.bssid
				val isVpnActive = network.isVpnActive
				val isValidated = network.isValidated
				val hasInternet = network.hasInternet
				val wifiCaps = network.wifiCapabilities

				app.detectedSsid = currentSsid

				val vpnOverrideEnabled =
					prefs.getBoolean(Constants.PREF_VPN_OVERRIDE_ENABLED, false)
				val isInVpnOverride = prefs.getBoolean(Constants.PREF_IS_IN_VPN_OVERRIDE, false)

				if (isVpnActive && vpnOverrideEnabled) {
					dnsSettleJob?.cancel()
					watchdogManager.cancelAll()

					if (!isInVpnOverride) {
						prefs.edit(commit = true) {
							putBoolean(Constants.PREF_IS_IN_VPN_OVERRIDE, true)
							putString(Constants.PREF_ACTIVE_SSID_OVERRIDE, null)
						}
					}

					dnsSettleJob = serviceScope.launch {
						isTransitioning = true
						try {
							delay(Constants.DNS_SETTLE_DELAY_NORMAL_MS.milliseconds)
							applyVpnDns(context)
						} finally {
							isTransitioning = false
						}
					}
					return@withLock
				} else if (!isVpnActive && isInVpnOverride) {
					prefs.edit(commit = true) {
						putBoolean(Constants.PREF_IS_IN_VPN_OVERRIDE, false)
					}
					dispatchStatusNotification(
						context,
						context.getString(R.string.notif_vpn_dns_restored)
					)
				}

				if (!NetworkUtils.isValidSsid(currentSsid)) {
					dnsSettleJob?.cancel()
					isTransitioning = false
					lastBssid = null
					lastNotifiedSsid = null
					watchdogManager.cancelAll()
					if (prefs.getString(Constants.PREF_ACTIVE_SSID_OVERRIDE, null) != null) {
						prefs.edit(commit = true) {
							putString(Constants.PREF_ACTIVE_SSID_OVERRIDE, null)
						}
					}

					val locationManager = context.getSystemService(LocationManager::class.java)
					val locationEnabled = locationManager.isLocationEnabled
					val hasProfiles =
						!NetworkProfileRepository.networkProfiles.value.isNullOrEmpty()

					if (!locationEnabled && hasProfiles) {
						if (!hasShownLocationWarning) {
							NotificationUtils.showStatusNotification(
								context,
								context.getString(R.string.warning_location_disabled)
							)
							hasShownLocationWarning = true
						}
					} else {
						hasShownLocationWarning = false
					}

					restorePreferredDns(context, watchdogManager)
					return@withLock
				} else {
					hasShownLocationWarning = false
				}

				val profiles = NetworkProfileRepository.networkProfiles.value ?: emptyList()
				val profile = profiles.find { it.ssid == currentSsid }
				val targetHostname = profile?.targetHostname ?: getGlobalPreferredHostname(context)
				val isApplyingHostname =
					(profile == null) || (profile.isEnabled && targetHostname != null)
				val isRoam = currentBssid != null && lastBssid != null && currentBssid != lastBssid

				if (isApplyingHostname && (!isValidated || !hasInternet)) {
					watchdogManager.cancelDebounce()
					watchdogManager.evaluateConnectivityWatchdog(
						currentSsid!!,
						wifiCaps,
						activeNetworks,
						cachedDnsMode
					)
					return@withLock
				}

				lastBssid = currentBssid

				dnsSettleJob?.cancel()
				watchdogManager.cancelAll()

				dnsSettleJob = serviceScope.launch {
					isTransitioning = true
					try {
						if (!isApplyingHostname) {
							delay(Constants.DNS_SETTLE_DELAY_FAST_MS.milliseconds)
						} else if (isRoam) {
							delay(Constants.DNS_SETTLE_DELAY_ROAM_MS.milliseconds)
						} else {
							delay(Constants.DNS_SETTLE_DELAY_NORMAL_MS.milliseconds)
						}

						if (profile != null) {
							if (prefs.getString(
									Constants.PREF_ACTIVE_SSID_OVERRIDE,
									null
								) != currentSsid
							) {
								prefs.edit(commit = true) {
									putString(Constants.PREF_ACTIVE_SSID_OVERRIDE, currentSsid)
								}
							}

							if (profile.isEnabled) {
								watchdogManager.cancelAll()
								val target =
									profile.targetHostname ?: getGlobalPreferredHostname(context)
								if (target != null) {
									applyHostnameDns(
										context,
										watchdogManager,
										currentSsid!!,
										target
									)
								} else {
									restorePreferredDnsSync(
										context,
										isFromSettleJob = true
									)
								}
								watchdogManager.evaluateConnectivityWatchdog(
									currentSsid!!,
									wifiCaps,
									activeNetworks,
									cachedDnsMode
								)
							} else {
								watchdogManager.cancelAll()
								applyOffDns(
									context,
									watchdogManager,
									currentSsid!!,
									profile.targetMode,
									if (profile.isAutoDetected) {
										R.string.notif_connectivity_watchdog_disabled
									} else {
										R.string.notif_dns_disabled_auto
									}
								)
								watchdogManager.maybeRetryAutoDetectedSsid(
									currentSsid,
									profile.isAutoDetected,
									currentBssid
								) { ssid ->
									dispatchStatusNotification(
										context,
										context.getString(
											R.string.notif_connectivity_watchdog_restored,
											ssid
										)
									)
								}
							}
						} else {
							if (prefs.getString(
									Constants.PREF_ACTIVE_SSID_OVERRIDE,
									null
								) != null
							) {
								prefs.edit(commit = true) {
									putString(Constants.PREF_ACTIVE_SSID_OVERRIDE, null)
								}
							}
							restorePreferredDnsSync(
								context,
								isFromSettleJob = true
							)
							watchdogManager.cancelAll()
							watchdogManager.evaluateConnectivityWatchdog(
								currentSsid!!,
								wifiCaps,
								activeNetworks,
								cachedDnsMode
							)
						}
					} finally {
						isTransitioning = false
					}
				}
			}
		}
	}

	/**
	 * Restores the preferred DNS setting asynchronously.
	 */
	fun restorePreferredDns(
		context: Context,
		watchdogManager: ConnectivityWatchdogManager,
		immediate: Boolean = false
	) {
		dnsSettleJob?.cancel()
		isTransitioning = false
		watchdogManager.restorePreferredDns(immediate) {
			restorePreferredDnsSync(context)
		}
	}

	private suspend fun restorePreferredDnsSync(
		context: Context,
		isFromSettleJob: Boolean = false
	) {
		evaluationMutex.withLock {
			restorePreferredDnsSyncInternal(context, isFromSettleJob)
		}
	}

	private fun restorePreferredDnsSyncInternal(
		context: Context,
		isFromSettleJob: Boolean = false
	) {
		if (!isFromSettleJob && (isTransitioning || dnsSettleJob?.isActive == true)) {
			Log.d(TAG, "Aborting restoration: transition or settle job is active")
			return
		}

		val app = context.applicationContext as DnsToggleApplication
		val sharedPreferences = app.getPrefs()
		val connectivityManager =
			context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

		val isInVpnOverride = sharedPreferences.getBoolean(Constants.PREF_IS_IN_VPN_OVERRIDE, false)
		val vpnOverrideEnabled =
			sharedPreferences.getBoolean(Constants.PREF_VPN_OVERRIDE_ENABLED, false)
		val activeSsidOverride =
			sharedPreferences.getString(Constants.PREF_ACTIVE_SSID_OVERRIDE, null)

		val activeNetwork = connectivityManager.activeNetwork
		val activeCaps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }

		val isVpnActive = activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
		val currentSsid = app.detectedSsid

		val profiles = NetworkProfileRepository.networkProfiles.value ?: emptyList()
		val currentProfile = profiles.find {
			it.ssid == currentSsid
		}
		val hasActiveProfileOverride = currentSsid != null && currentProfile?.isEnabled == true

		val vpnActiveOverride = isInVpnOverride && vpnOverrideEnabled
		val vpnActiveTransport = isVpnActive && vpnOverrideEnabled

		if (vpnActiveOverride ||
			vpnActiveTransport ||
			activeSsidOverride != null ||
			hasActiveProfileOverride
		) {
			return
		}

		val preferredMode = sharedPreferences.getString(
			Constants.PREF_PREFERRED_DNS_MODE,
			Constants.DNS_MODE_HOSTNAME
		) ?: Constants.DNS_MODE_HOSTNAME

		if (preferredMode == Constants.DNS_MODE_HOSTNAME) {
			val hostname = getGlobalPreferredHostname(context)
			if (hostname != null) {
				try {
					Global.putString(
						context.contentResolver,
						Constants.SETTINGS_PRIVATE_DNS_SPECIFIER,
						hostname
					)
					updateDnsSetting(context, preferredMode, null)
				} catch (e: SecurityException) {
					Log.e(TAG, "Failed to restore preferred DNS specifier", e)
				}
			} else {
				val offMode = sharedPreferences.getString(
					Constants.PREF_DEFAULT_OFF_MODE,
					Constants.DNS_MODE_OPPORTUNISTIC
				) ?: Constants.DNS_MODE_OPPORTUNISTIC
				updateDnsSetting(context, offMode, null)
				val label = if (offMode == Constants.DNS_MODE_OFF) {
					context.getString(R.string.off_strict_label)
				} else {
					context.getString(R.string.off_automatic_label)
				}
				dispatchStatusNotification(
					context,
					context.getString(R.string.keystore_error_title) + ": " + label
				)
			}
		} else {
			updateDnsSetting(context, preferredMode, null)
		}
	}

	private fun applyHostnameDns(
		context: Context,
		watchdogManager: ConnectivityWatchdogManager,
		ssid: String,
		hostname: String
	) {
		watchdogManager.cancelDebounce()
		updateDnsSetting(
			context,
			Constants.DNS_MODE_HOSTNAME,
			ssid,
			R.string.notif_dns_enabled_auto,
			hostname = hostname
		)
	}

	private fun applyVpnDns(context: Context) {
		val vpnDns = VpnRepository.vpnDnsHostname.value
		val vpnMode = VpnRepository.vpnDnsMode.value
		val resolver = context.contentResolver
		val currentMode = Global.getString(resolver, Constants.SETTINGS_PRIVATE_DNS_MODE)
		val currentSpecifier = Global.getString(resolver, Constants.SETTINGS_PRIVATE_DNS_SPECIFIER)

		if (vpnMode != Constants.DNS_MODE_HOSTNAME) {
			if (currentMode != vpnMode) {
				updateDnsSetting(context, vpnMode, null)
				dispatchStatusNotification(
					context,
					context.getString(R.string.notif_vpn_dns_applied)
				)
			}
		} else if (vpnDns != null) {
			if (currentMode != Constants.DNS_MODE_HOSTNAME || currentSpecifier != vpnDns) {
				try {
					Global.putString(resolver, Constants.SETTINGS_PRIVATE_DNS_SPECIFIER, vpnDns)
					updateDnsSetting(context, Constants.DNS_MODE_HOSTNAME, null)
					dispatchStatusNotification(
						context,
						context.getString(R.string.notif_vpn_dns_applied)
					)
				} catch (e: SecurityException) {
					Log.e(TAG, "Failed to apply VPN DNS hostname", e)
				}
			}
		}
	}

	private fun applyOffDns(
		context: Context,
		watchdogManager: ConnectivityWatchdogManager,
		ssid: String,
		targetMode: String?,
		reasonStringResId: Int = R.string.notif_dns_disabled_auto
	) {
		watchdogManager.cancelDebounce()
		val app = context.applicationContext as DnsToggleApplication
		val offMode = targetMode ?: app.getPrefs().getString(
			Constants.PREF_DEFAULT_OFF_MODE,
			Constants.DNS_MODE_OPPORTUNISTIC
		) ?: Constants.DNS_MODE_OPPORTUNISTIC
		updateDnsSetting(context, offMode, ssid, reasonStringResId)
	}

	private fun updateDnsSetting(
		context: Context,
		newMode: String,
		ssidForNotification: String?,
		reasonStringResId: Int = R.string.notif_dns_disabled_auto,
		hostname: String? = null
	) {
		try {
			val resolver = context.contentResolver
			var actuallyChanged = false

			if (newMode == Constants.DNS_MODE_HOSTNAME && hostname != null) {
				val currentHostname =
					Global.getString(resolver, Constants.SETTINGS_PRIVATE_DNS_SPECIFIER)
				if (currentHostname != hostname) {
					actuallyChanged = true
					Global.putString(resolver, Constants.SETTINGS_PRIVATE_DNS_SPECIFIER, hostname)
				}
			}

			val currentSystemMode = Global.getString(resolver, Constants.SETTINGS_PRIVATE_DNS_MODE)
			if (currentSystemMode != newMode) {
				actuallyChanged = true
			}

			// Force a dummy flip to bypass netd caching
			val dummyMode = if (newMode == Constants.DNS_MODE_OFF) {
				Constants.DNS_MODE_OPPORTUNISTIC
			} else {
				Constants.DNS_MODE_OFF
			}
			Global.putString(resolver, Constants.SETTINGS_PRIVATE_DNS_MODE, dummyMode)
			Global.putString(resolver, Constants.SETTINGS_PRIVATE_DNS_MODE, newMode)

			ssidForNotification?.let {
				val app = context.applicationContext as DnsToggleApplication
				if (!app.isAppInForeground && (actuallyChanged || lastNotifiedSsid != it)) {
					dispatchStatusNotification(
						context,
						context.getString(reasonStringResId, it)
					)
					lastNotifiedSsid = it
				}
			}
			TileServiceCompat.requestListeningState(
				context,
				ComponentName(context, DnsToggleService::class.java)
			)
		} catch (e: SecurityException) {
			Log.e(TAG, "Failed to update DNS setting", e)
		}
	}

	private fun getGlobalPreferredHostname(context: Context): String? {
		val app = context.applicationContext as DnsToggleApplication
		val encryptedPrefs = app.getEncryptedPrefs()
		val encryptedHostname = encryptedPrefs.getString(Constants.PREF_LAST_USED_HOSTNAME, null)
		return encryptedHostname?.let {
			when (val result = EncryptionManager.decrypt(it)) {
				is EncryptionManager.DecryptResult.Success -> {
					result.data
				}

				else -> {
					null
				}
			}
		} ?: Global.getString(context.contentResolver, Constants.SETTINGS_PRIVATE_DNS_SPECIFIER)
	}

	private fun dispatchStatusNotification(context: Context, message: String) {
		NotificationUtils.showStatusNotification(context, message)
		val app = context.applicationContext as DnsToggleApplication
		if (app.getPrefs().getBoolean(Constants.PREF_SHOW_TOAST, true)) {
			Handler(Looper.getMainLooper()).post {
				Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
			}
		}
	}
}
