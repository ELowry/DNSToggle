package com.ericlowry.dnstoggle.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Patterns
import androidx.core.content.edit
import com.ericlowry.dnstoggle.DnsToggleApplication
import com.ericlowry.dnstoggle.data.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Repository for general application settings and preferences.
 * Extracted from DnsViewModel to follow the singleton repository pattern.
 */
object AppSettingsRepository {
	private lateinit var sharedPreferences: SharedPreferences
	private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

	private val _autoSaveStateEnabled = MutableStateFlow(false)
	val autoSaveStateEnabled: StateFlow<Boolean> = _autoSaveStateEnabled.asStateFlow()

	private val _autoSaveHostEnabled = MutableStateFlow(false)
	val autoSaveHostEnabled: StateFlow<Boolean> = _autoSaveHostEnabled.asStateFlow()

	private val _connectivityWatchdogEnabled = MutableStateFlow(false)
	val connectivityWatchdogEnabled: StateFlow<Boolean> = _connectivityWatchdogEnabled.asStateFlow()

	private val _connectivityWatchdogDebounceSeconds =
		MutableStateFlow(Constants.CONNECTIVITY_WATCHDOG_DEFAULT_DEBOUNCE_SECONDS)
	val connectivityWatchdogDebounceSeconds: StateFlow<Int> =
		_connectivityWatchdogDebounceSeconds.asStateFlow()

	private val _connectivityWatchdogProbeTargets =
		MutableStateFlow(Constants.CONNECTIVITY_WATCHDOG_DEFAULT_PROBE_TARGETS)
	val connectivityWatchdogProbeTargets: StateFlow<String> =
		_connectivityWatchdogProbeTargets.asStateFlow()

	private val _hideLauncherIcon = MutableStateFlow(false)
	val hideLauncherIcon: StateFlow<Boolean> = _hideLauncherIcon.asStateFlow()

	private val _disableDnsTest = MutableStateFlow(false)
	val disableDnsTest: StateFlow<Boolean> = _disableDnsTest.asStateFlow()

	private val _showToastEnabled = MutableStateFlow(true)
	val showToastEnabled: StateFlow<Boolean> = _showToastEnabled.asStateFlow()

	private val _enableStrictOffOption = MutableStateFlow(false)
	val enableStrictOffOption: StateFlow<Boolean> = _enableStrictOffOption.asStateFlow()

	private val _defaultOffMode = MutableStateFlow(Constants.DNS_MODE_OPPORTUNISTIC)
	val defaultOffMode: StateFlow<String> = _defaultOffMode.asStateFlow()

	private val _vpnHostnameRemovedWarning = MutableStateFlow(false)
	val vpnHostnameRemovedWarning: StateFlow<Boolean> = _vpnHostnameRemovedWarning.asStateFlow()

	private val _isInVpnOverride = MutableStateFlow(false)
	val isInVpnOverride: StateFlow<Boolean> = _isInVpnOverride.asStateFlow()

	private val _activeSsidOverride = MutableStateFlow<String?>(null)
	val activeSsidOverride: StateFlow<String?> = _activeSsidOverride.asStateFlow()

	private val preferenceChangeListener =
		SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
			when (key) {
				Constants.PREF_AUTO_SAVE_STATE -> _autoSaveStateEnabled.value =
					prefs.getBoolean(key, false)

				Constants.PREF_AUTO_SAVE_HOST -> _autoSaveHostEnabled.value =
					prefs.getBoolean(key, false)

				Constants.PREF_HIDE_LAUNCHER_ICON -> _hideLauncherIcon.value =
					prefs.getBoolean(key, false)

				Constants.PREF_DISABLE_DNS_TEST -> _disableDnsTest.value =
					prefs.getBoolean(key, false)

				Constants.PREF_SHOW_TOAST -> _showToastEnabled.value = prefs.getBoolean(key, true)
				Constants.PREF_IS_IN_VPN_OVERRIDE -> _isInVpnOverride.value =
					prefs.getBoolean(key, false)

				Constants.PREF_ACTIVE_SSID_OVERRIDE -> _activeSsidOverride.value =
					prefs.getString(key, null)

				Constants.PREF_CONNECTIVITY_WATCHDOG_ENABLED -> _connectivityWatchdogEnabled.value =
					prefs.getBoolean(key, false)

				Constants.PREF_CONNECTIVITY_WATCHDOG_DEBOUNCE_SECONDS -> _connectivityWatchdogDebounceSeconds.value =
					prefs.getInt(key, Constants.CONNECTIVITY_WATCHDOG_DEFAULT_DEBOUNCE_SECONDS)

				Constants.PREF_CONNECTIVITY_WATCHDOG_PROBE_TARGETS -> _connectivityWatchdogProbeTargets.value =
					prefs.getString(key, Constants.CONNECTIVITY_WATCHDOG_DEFAULT_PROBE_TARGETS)
						?: Constants.CONNECTIVITY_WATCHDOG_DEFAULT_PROBE_TARGETS

				Constants.PREF_ENABLE_STRICT_OFF_OPTION -> _enableStrictOffOption.value =
					prefs.getBoolean(key, false)

				Constants.PREF_DEFAULT_OFF_MODE -> _defaultOffMode.value =
					prefs.getString(key, Constants.DNS_MODE_OPPORTUNISTIC)
						?: Constants.DNS_MODE_OPPORTUNISTIC

				Constants.PREF_VPN_HOSTNAME_REMOVED_WARNING -> _vpnHostnameRemovedWarning.value =
					prefs.getBoolean(key, false)
			}
		}

	/**
	 * Initializes the repository with the application context.
	 */
	fun initialize(context: Context) {
		val app = context.applicationContext as DnsToggleApplication
		sharedPreferences = app.getPrefs()

		scope.launch {
			_autoSaveStateEnabled.value =
				sharedPreferences.getBoolean(Constants.PREF_AUTO_SAVE_STATE, false)
			_autoSaveHostEnabled.value =
				sharedPreferences.getBoolean(Constants.PREF_AUTO_SAVE_HOST, false)
			_connectivityWatchdogEnabled.value =
				sharedPreferences.getBoolean(Constants.PREF_CONNECTIVITY_WATCHDOG_ENABLED, false)
			_connectivityWatchdogDebounceSeconds.value = sharedPreferences.getInt(
				Constants.PREF_CONNECTIVITY_WATCHDOG_DEBOUNCE_SECONDS,
				Constants.CONNECTIVITY_WATCHDOG_DEFAULT_DEBOUNCE_SECONDS
			)
			_connectivityWatchdogProbeTargets.value = sharedPreferences.getString(
				Constants.PREF_CONNECTIVITY_WATCHDOG_PROBE_TARGETS,
				Constants.CONNECTIVITY_WATCHDOG_DEFAULT_PROBE_TARGETS
			) ?: Constants.CONNECTIVITY_WATCHDOG_DEFAULT_PROBE_TARGETS
			_hideLauncherIcon.value =
				sharedPreferences.getBoolean(Constants.PREF_HIDE_LAUNCHER_ICON, false)
			_disableDnsTest.value =
				sharedPreferences.getBoolean(Constants.PREF_DISABLE_DNS_TEST, false)
			_showToastEnabled.value = sharedPreferences.getBoolean(Constants.PREF_SHOW_TOAST, true)
			_enableStrictOffOption.value =
				sharedPreferences.getBoolean(Constants.PREF_ENABLE_STRICT_OFF_OPTION, false)
			_defaultOffMode.value = sharedPreferences.getString(
				Constants.PREF_DEFAULT_OFF_MODE,
				Constants.DNS_MODE_OPPORTUNISTIC
			) ?: Constants.DNS_MODE_OPPORTUNISTIC
			_vpnHostnameRemovedWarning.value =
				sharedPreferences.getBoolean(Constants.PREF_VPN_HOSTNAME_REMOVED_WARNING, false)
			_isInVpnOverride.value =
				sharedPreferences.getBoolean(Constants.PREF_IS_IN_VPN_OVERRIDE, false)
			_activeSsidOverride.value =
				sharedPreferences.getString(Constants.PREF_ACTIVE_SSID_OVERRIDE, null)
		}

		sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
	}

	fun setAutoSaveState(enabled: Boolean) {
		sharedPreferences.edit {
			putBoolean(Constants.PREF_AUTO_SAVE_STATE, enabled)
		}
	}

	fun setAutoSaveHost(enabled: Boolean) {
		sharedPreferences.edit {
			putBoolean(Constants.PREF_AUTO_SAVE_HOST, enabled)
		}
	}

	fun setConnectivityWatchdogEnabled(enabled: Boolean) {
		sharedPreferences.edit {
			putBoolean(Constants.PREF_CONNECTIVITY_WATCHDOG_ENABLED, enabled)
		}
	}

	fun setConnectivityWatchdogDebounceSeconds(seconds: Int) {
		sharedPreferences.edit {
			putInt(
				Constants.PREF_CONNECTIVITY_WATCHDOG_DEBOUNCE_SECONDS,
				seconds
			)
		}
	}

	fun setConnectivityWatchdogProbeTargets(targets: String) {
		val sanitized = targets.split(",")
			.map { it.trim() }
			.filter {
				Patterns.DOMAIN_NAME.matcher(it).matches() ||
						@Suppress("DEPRECATION") Patterns.IP_ADDRESS.matcher(it).matches()
			}
			.joinToString(", ")
		val finalValue = sanitized.ifEmpty { Constants.CONNECTIVITY_WATCHDOG_DEFAULT_PROBE_TARGETS }
		sharedPreferences.edit {
			putString(
				Constants.PREF_CONNECTIVITY_WATCHDOG_PROBE_TARGETS,
				finalValue
			)
		}
	}

	fun setHideLauncherIcon(hidden: Boolean) {
		sharedPreferences.edit {
			putBoolean(Constants.PREF_HIDE_LAUNCHER_ICON, hidden)
		}
	}

	fun setDisableDnsTest(disabled: Boolean) {
		sharedPreferences.edit {
			putBoolean(Constants.PREF_DISABLE_DNS_TEST, disabled)
		}
	}

	fun setShowToast(enabled: Boolean) {
		sharedPreferences.edit {
			putBoolean(Constants.PREF_SHOW_TOAST, enabled)
		}
	}

	fun setEnableStrictOffOption(enabled: Boolean) {
		sharedPreferences.edit { putBoolean(Constants.PREF_ENABLE_STRICT_OFF_OPTION, enabled) }
		if (!enabled) {
			setDefaultOffMode(Constants.DNS_MODE_OPPORTUNISTIC)
			if (VpnRepository.vpnDnsMode.value == Constants.DNS_MODE_OFF) {
				VpnRepository.updateVpnDns(Constants.DNS_MODE_OPPORTUNISTIC, null)
			}
			NetworkProfileRepository.sanitizeStrictOffProfiles()
		}
	}

	fun setDefaultOffMode(mode: String) {
		sharedPreferences.edit {
			putString(Constants.PREF_DEFAULT_OFF_MODE, mode)
		}
	}

	fun dismissVpnHostnameWarning() {
		sharedPreferences.edit {
			remove(Constants.PREF_VPN_HOSTNAME_REMOVED_WARNING)
		}
	}
}
