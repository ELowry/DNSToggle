package com.ericlowry.dnstoggle.data

import android.app.Application
import android.content.ComponentName
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.Patterns
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.ericlowry.dnstoggle.DnsToggleApplication
import com.ericlowry.dnstoggle.R
import com.ericlowry.dnstoggle.data.repository.HostnameRepository
import com.ericlowry.dnstoggle.data.repository.NetworkProfileRepository
import com.ericlowry.dnstoggle.data.repository.SecurityRepository
import com.ericlowry.dnstoggle.data.repository.VpnRepository
import com.ericlowry.dnstoggle.service.DnsToggleService
import com.ericlowry.dnstoggle.service.TileServiceCompat
import com.ericlowry.dnstoggle.util.EncryptionManager
import com.ericlowry.dnstoggle.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class DnsViewModel(application: Application) : AndroidViewModel(application) {

	enum class ReachabilityState { IDLE, TESTING, REACHABLE, UNREACHABLE }

	companion object {
		private const val TAG = "DnsViewModel"
	}

	var ioDispatcher = Dispatchers.IO

	private val sharedPreferences = (application as DnsToggleApplication).getPrefs()

	private val _privateDnsMode = MutableLiveData<String?>()
	val privateDnsMode: LiveData<String?> = _privateDnsMode

	private val _privateDnsSpecifier = MutableLiveData<String?>()
	val privateDnsSpecifier: LiveData<String?> = _privateDnsSpecifier

	private val _dnsReachability = MutableLiveData<Map<String, ReachabilityState>>(emptyMap())
	val dnsReachability: LiveData<Map<String, ReachabilityState>> = _dnsReachability

	private val _hasPermissionError = MutableLiveData(false)
	val hasPermissionError: LiveData<Boolean> = _hasPermissionError

	private val reachabilityJobs = ConcurrentHashMap<String, Job>()

	private val _networkProfiles = MutableLiveData<List<NetworkProfile>>()
	val networkProfiles: LiveData<List<NetworkProfile>> = _networkProfiles

	private val _dnsHostnames = MutableLiveData<List<DnsHostname>>()
	val dnsHostnames: LiveData<List<DnsHostname>> = _dnsHostnames

	private val _autoSaveStateEnabled = MutableLiveData<Boolean>()
	val autoSaveStateEnabled: LiveData<Boolean> = _autoSaveStateEnabled

	private val _autoSaveHostEnabled = MutableLiveData<Boolean>()
	val autoSaveHostEnabled: LiveData<Boolean> = _autoSaveHostEnabled

	private val _connectivityWatchdogEnabled = MutableLiveData<Boolean>()
	val connectivityWatchdogEnabled: LiveData<Boolean> = _connectivityWatchdogEnabled

	private val _connectivityWatchdogDebounceSeconds = MutableLiveData<Int>()
	val connectivityWatchdogDebounceSeconds: LiveData<Int> = _connectivityWatchdogDebounceSeconds

	private val _connectivityWatchdogProbeTargets = MutableLiveData<String>()
	val connectivityWatchdogProbeTargets: LiveData<String> = _connectivityWatchdogProbeTargets

	private val _hideLauncherIcon = MutableLiveData<Boolean>()
	val hideLauncherIcon: LiveData<Boolean> = _hideLauncherIcon

	private val _disableDnsTest = MutableLiveData<Boolean>()
	val disableDnsTest: LiveData<Boolean> = _disableDnsTest

	private val _showToastEnabled = MutableLiveData<Boolean>()
	val showToastEnabled: LiveData<Boolean> = _showToastEnabled

	private val _vpnOverrideEnabled = MutableLiveData<Boolean>()
	val vpnOverrideEnabled: LiveData<Boolean> = _vpnOverrideEnabled

	private val _vpnDnsHostname = MutableLiveData<String?>()
	val vpnDnsHostname: LiveData<String?> = _vpnDnsHostname

	private val _vpnDnsMode = MutableLiveData<String>()
	val vpnDnsMode: LiveData<String> = _vpnDnsMode

	private val _enableStrictOffOption = MutableLiveData<Boolean>()
	val enableStrictOffOption: LiveData<Boolean> = _enableStrictOffOption

	private val _defaultOffMode = MutableLiveData<String>()
	val defaultOffMode: LiveData<String> = _defaultOffMode

	private val _vpnHostnameRemovedWarning = MutableLiveData<Boolean>()
	val vpnHostnameRemovedWarning: LiveData<Boolean> = _vpnHostnameRemovedWarning

	private val _isInVpnOverride = MutableLiveData<Boolean>()
	val isInVpnOverride: LiveData<Boolean> = _isInVpnOverride

	private val _activeSsidOverride = MutableLiveData<String?>()
	val activeSsidOverride: LiveData<String?> = _activeSsidOverride

	private val _currentSsid = MutableLiveData<String?>(null)
	val currentSsid: LiveData<String?> = _currentSsid

	private val _isKeyInvalidated = MutableLiveData(false)
	val isKeyInvalidated: LiveData<Boolean> = _isKeyInvalidated

	private val dnsSettingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
		override fun onChange(selfChange: Boolean) {
			loadSettings()
			TileServiceCompat.requestListeningState(
				getApplication(),
				ComponentName(getApplication(), DnsToggleService::class.java),
			)
		}
	}

	private val preferenceChangeListener =
		SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
			when (key) {
				Constants.PREF_AUTO_SAVE_STATE -> {
					_autoSaveStateEnabled.value = prefs.getBoolean(key, false)
				}

				Constants.PREF_AUTO_SAVE_HOST -> {
					_autoSaveHostEnabled.value = prefs.getBoolean(key, false)
				}

				Constants.PREF_HIDE_LAUNCHER_ICON -> {
					_hideLauncherIcon.value = prefs.getBoolean(key, false)
				}

				Constants.PREF_DISABLE_DNS_TEST -> {
					_disableDnsTest.value = prefs.getBoolean(key, false)
					refreshDisplayList()
				}

				Constants.PREF_SHOW_TOAST -> {
					_showToastEnabled.value = prefs.getBoolean(key, true)
				}

				Constants.PREF_IS_IN_VPN_OVERRIDE -> {
					_isInVpnOverride.value = prefs.getBoolean(key, false)
				}

				Constants.PREF_ACTIVE_SSID_OVERRIDE -> {
					_activeSsidOverride.value = prefs.getString(key, null)
				}

				Constants.PREF_CONNECTIVITY_WATCHDOG_ENABLED -> {
					_connectivityWatchdogEnabled.value = prefs.getBoolean(key, false)
				}

				Constants.PREF_CONNECTIVITY_WATCHDOG_DEBOUNCE_SECONDS -> {
					_connectivityWatchdogDebounceSeconds.value = prefs.getInt(
						key,
						Constants.CONNECTIVITY_WATCHDOG_DEFAULT_DEBOUNCE_SECONDS
					)
				}

				Constants.PREF_CONNECTIVITY_WATCHDOG_PROBE_TARGETS -> {
					_connectivityWatchdogProbeTargets.value = prefs.getString(
						key,
						Constants.CONNECTIVITY_WATCHDOG_DEFAULT_PROBE_TARGETS
					)
				}

				Constants.PREF_ENABLE_STRICT_OFF_OPTION -> {
					_enableStrictOffOption.value = prefs.getBoolean(key, false)
				}

				Constants.PREF_DEFAULT_OFF_MODE -> {
					_defaultOffMode.value =
						prefs.getString(key, Constants.DNS_MODE_OPPORTUNISTIC)
							?: Constants.DNS_MODE_OPPORTUNISTIC
				}
			}
		}

	init {
		loadSettings()
		viewModelScope.launch {
			NetworkProfileRepository.networkProfiles.collect { list ->
				if (list != null) {
					_networkProfiles.postValue(list)
				}
			}
		}
		viewModelScope.launch {
			HostnameRepository.dnsHostnames.collect { hostnames ->
				if (hostnames != null) {
					refreshDisplayList()
				}
			}
		}
		viewModelScope.launch {
			SecurityRepository.isKeyInvalidated.collect { invalidated ->
				if (invalidated) {
					_isKeyInvalidated.postValue(true)
				}
			}
		}
		viewModelScope.launch {
			VpnRepository.vpnOverrideEnabled.collect {
				_vpnOverrideEnabled.postValue(it)
			}
		}
		viewModelScope.launch {
			VpnRepository.vpnDnsHostname.collect {
				_vpnDnsHostname.postValue(it)
			}
		}
		viewModelScope.launch {
			VpnRepository.vpnDnsMode.collect {
				_vpnDnsMode.postValue(it)
			}
		}
		sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)

		getApplication<Application>().contentResolver.apply {
			registerContentObserver(
				Settings.Global.getUriFor(Constants.SETTINGS_PRIVATE_DNS_MODE),
				false,
				dnsSettingsObserver,
			)
			registerContentObserver(
				Settings.Global.getUriFor(Constants.SETTINGS_PRIVATE_DNS_SPECIFIER),
				false,
				dnsSettingsObserver,
			)
		}
	}

	fun loadSettings() {
		viewModelScope.launch(ioDispatcher) {
			val resolver = getApplication<Application>().contentResolver
			val mode = Settings.Global.getString(resolver, Constants.SETTINGS_PRIVATE_DNS_MODE)
			val specifier =
				Settings.Global.getString(resolver, Constants.SETTINGS_PRIVATE_DNS_SPECIFIER)

			val autoSaveState = sharedPreferences.getBoolean(Constants.PREF_AUTO_SAVE_STATE, false)
			val autoSaveHost = sharedPreferences.getBoolean(Constants.PREF_AUTO_SAVE_HOST, false)
			val connectivityWatchdog =
				sharedPreferences.getBoolean(Constants.PREF_CONNECTIVITY_WATCHDOG_ENABLED, false)
			val watchdogDebounce = sharedPreferences.getInt(
				Constants.PREF_CONNECTIVITY_WATCHDOG_DEBOUNCE_SECONDS,
				Constants.CONNECTIVITY_WATCHDOG_DEFAULT_DEBOUNCE_SECONDS
			)
			val watchdogTargets = sharedPreferences.getString(
				Constants.PREF_CONNECTIVITY_WATCHDOG_PROBE_TARGETS,
				Constants.CONNECTIVITY_WATCHDOG_DEFAULT_PROBE_TARGETS
			) ?: Constants.CONNECTIVITY_WATCHDOG_DEFAULT_PROBE_TARGETS
			val hideLauncher =
				sharedPreferences.getBoolean(Constants.PREF_HIDE_LAUNCHER_ICON, false)
			val disableDnsTest =
				sharedPreferences.getBoolean(Constants.PREF_DISABLE_DNS_TEST, false)
			val showToast = sharedPreferences.getBoolean(Constants.PREF_SHOW_TOAST, true)
			val vpnRemovedWarning =
				sharedPreferences.getBoolean(Constants.PREF_VPN_HOSTNAME_REMOVED_WARNING, false)
			val isInVpn = sharedPreferences.getBoolean(Constants.PREF_IS_IN_VPN_OVERRIDE, false)
			val enableStrictOff =
				sharedPreferences.getBoolean(Constants.PREF_ENABLE_STRICT_OFF_OPTION, false)
			val defaultOffMode = sharedPreferences.getString(
				Constants.PREF_DEFAULT_OFF_MODE,
				Constants.DNS_MODE_OPPORTUNISTIC
			) ?: Constants.DNS_MODE_OPPORTUNISTIC
			val ssidOverride =
				sharedPreferences.getString(Constants.PREF_ACTIVE_SSID_OVERRIDE, null)

			withContext(Dispatchers.Main) {
				_privateDnsMode.value = mode
				_privateDnsSpecifier.value = specifier
				_autoSaveStateEnabled.value = autoSaveState
				_autoSaveHostEnabled.value = autoSaveHost
				_connectivityWatchdogEnabled.value = connectivityWatchdog
				_connectivityWatchdogDebounceSeconds.value = watchdogDebounce
				_connectivityWatchdogProbeTargets.value = watchdogTargets
				_hideLauncherIcon.value = hideLauncher
				_disableDnsTest.value = disableDnsTest
				_showToastEnabled.value = showToast
				_enableStrictOffOption.value = enableStrictOff
				_defaultOffMode.value = defaultOffMode
				_vpnHostnameRemovedWarning.value = vpnRemovedWarning
				_isInVpnOverride.value = isInVpn
				_activeSsidOverride.value = ssidOverride
				refreshCurrentSsid()
				refreshDisplayList()
			}
		}
	}

	fun upsertNetworkProfile(
		ssid: String,
		isEnabled: Boolean,
		targetHostname: String? = null,
		isAutoDetected: Boolean = false,
		isUnsaved: Boolean = false,
		preserveExistingHostname: Boolean = false
	) {
		NetworkProfileRepository.upsertNetworkProfile(
			ssid,
			isEnabled,
			targetHostname,
			isAutoDetected,
			isUnsaved,
			preserveExistingHostname
		)
	}

	fun saveNetworkProfile(
		oldSsid: String?,
		newSsid: String,
		isEnabled: Boolean,
		targetHostname: String?,
		targetMode: String? = null
	) {
		if (oldSsid != null && oldSsid != newSsid) {
			NetworkProfileRepository.removeNetworkProfile(oldSsid)
		}
		NetworkProfileRepository.upsertNetworkProfile(
			ssid = newSsid,
			isEnabled = isEnabled,
			targetHostname = targetHostname,
			targetMode = targetMode
		)
	}

	private fun refreshDisplayList() {
		val savedHostnames = HostnameRepository.dnsHostnames.value ?: emptyList()
		val currentSpecifier = _privateDnsSpecifier.value
		val currentMode = _privateDnsMode.value

		val displayList = savedHostnames.toMutableList()

		if (!currentSpecifier.isNullOrEmpty()) {
			if (savedHostnames.none { it.hostname == currentSpecifier }) {
				val isTrulyActive = currentMode == Constants.DNS_MODE_HOSTNAME
				val labelRes =
					if (isTrulyActive) R.string.unsaved_active_label else R.string.unsaved_inactive_label

				displayList.add(
					0,
					DnsHostname(
						hostname = currentSpecifier,
						label = getApplication<Application>().getString(labelRes),
						isUnsaved = true
					)
				)
			}
		}

		_dnsHostnames.postValue(displayList)

		displayList.forEach { dnsEntry ->
			val hostname = dnsEntry.hostname
			val currentMap = _dnsReachability.value ?: emptyMap()
			if (!currentMap.containsKey(hostname) && NetworkUtils.isValidDnsHostname(hostname)) {
				testReachability(hostname)
			}
		}
	}

	fun removeNetworkProfile(ssid: String) {
		NetworkProfileRepository.removeNetworkProfile(ssid)
	}

	fun promoteSsidToPermanent(ssid: String) {
		NetworkProfileRepository.promoteUnsavedProfile(ssid)
	}

	fun addHostname(hostname: String, label: String? = null) {
		HostnameRepository.addHostname(hostname, label)
		if (NetworkUtils.isValidDnsHostname(hostname)) {
			testReachability(hostname)
		}
	}

	fun removeHostname(hostname: String) {
		HostnameRepository.removeHostname(hostname)
		reachabilityJobs[hostname]?.cancel()
		reachabilityJobs.remove(hostname)
		viewModelScope.launch {
			val currentMap = _dnsReachability.value?.toMutableMap() ?: mutableMapOf()
			currentMap.remove(hostname)
			_dnsReachability.value = currentMap
		}
	}

	fun updateHostname(oldHostname: String, newHostname: String, newLabel: String? = null) {
		HostnameRepository.updateHostname(oldHostname, newHostname, newLabel)
		reachabilityJobs[oldHostname]?.cancel()
		reachabilityJobs.remove(oldHostname)
		viewModelScope.launch {
			val currentMap = _dnsReachability.value?.toMutableMap() ?: mutableMapOf()
			currentMap.remove(oldHostname)
			_dnsReachability.value = currentMap
		}

		if (NetworkUtils.isValidDnsHostname(newHostname)) {
			testReachability(newHostname)
		}
	}

	fun togglePrivateDns(
		enabled: Boolean,
		targetHostname: String? = null,
		targetMode: String? = null
	) {
		viewModelScope.launch(ioDispatcher) {
			val result = DnsManager.togglePrivateDns(
				getApplication(),
				enabled,
				targetHostname,
				targetMode,
				isInteractiveMainUi = true
			)
			_hasPermissionError.postValue(result is DnsManager.ToggleResult.PermissionRequired)
			if (result is DnsManager.ToggleResult.Success) {
				val offMode = sharedPreferences.getString(
					Constants.PREF_DEFAULT_OFF_MODE,
					Constants.DNS_MODE_OPPORTUNISTIC
				) ?: Constants.DNS_MODE_OPPORTUNISTIC
				val newMode = if (enabled) Constants.DNS_MODE_HOSTNAME else (targetMode ?: offMode)
				_privateDnsMode.postValue(newMode)
				if (enabled && (targetHostname != null)) {
					_privateDnsSpecifier.postValue(targetHostname)
					if (NetworkUtils.isValidDnsHostname(targetHostname)) {
						testReachability(targetHostname)
					}
				}
			}
		}
	}

	fun setEnableStrictOffOption(enabled: Boolean) {
		sharedPreferences.edit { putBoolean(Constants.PREF_ENABLE_STRICT_OFF_OPTION, enabled) }
		_enableStrictOffOption.value = enabled

		if (!enabled) {
			setDefaultOffMode(Constants.DNS_MODE_OPPORTUNISTIC)

			if (_vpnDnsMode.value == Constants.DNS_MODE_OFF) {
				setVpnDns(Constants.DNS_MODE_OPPORTUNISTIC, null)
			}

			NetworkProfileRepository.sanitizeStrictOffProfiles()
		}
	}

	fun setDefaultOffMode(mode: String) {
		sharedPreferences.edit { putString(Constants.PREF_DEFAULT_OFF_MODE, mode) }
		_defaultOffMode.value = mode
	}

	fun setShowToast(enabled: Boolean) {
		sharedPreferences.edit { putBoolean(Constants.PREF_SHOW_TOAST, enabled) }
		_showToastEnabled.value = enabled
	}

	private fun testReachability(hostname: String) {
		reachabilityJobs[hostname]?.cancel()

		val job = viewModelScope.launch(ioDispatcher) {
			if (hostname.isEmpty() || sharedPreferences.getBoolean(
					Constants.PREF_DISABLE_DNS_TEST,
					false
				)
			) {
				withContext(Dispatchers.Main) {
					val currentMap = _dnsReachability.value?.toMutableMap() ?: mutableMapOf()
					currentMap[hostname] = ReachabilityState.IDLE
					_dnsReachability.value = currentMap
				}
				return@launch
			}

			withContext(Dispatchers.Main) {
				val startMap = _dnsReachability.value?.toMutableMap() ?: mutableMapOf()
				startMap[hostname] = ReachabilityState.TESTING
				_dnsReachability.value = startMap
			}

			val isReachable = try {
				// Tests DoT availability using a TCP handshake on port 853.
				val socket = Socket()
				socket.connect(InetSocketAddress(hostname, 853), 3000) // DoT port
				socket.close()
				true
			} catch (e: Exception) {
				Log.w(TAG, "Reachability test failed for $hostname: ${e.message}")
				false
			}

			withContext(Dispatchers.Main) {
				val endMap = _dnsReachability.value?.toMutableMap() ?: mutableMapOf()
				endMap[hostname] =
					if (isReachable) ReachabilityState.REACHABLE else ReachabilityState.UNREACHABLE
				_dnsReachability.value = endMap
			}
		}
		reachabilityJobs[hostname] = job
	}

	fun setAutoSaveState(enabled: Boolean) {
		sharedPreferences.edit { putBoolean(Constants.PREF_AUTO_SAVE_STATE, enabled) }
		_autoSaveStateEnabled.value = enabled
	}

	fun setAutoSaveHost(enabled: Boolean) {
		sharedPreferences.edit { putBoolean(Constants.PREF_AUTO_SAVE_HOST, enabled) }
		_autoSaveHostEnabled.value = enabled
	}

	fun setConnectivityWatchdogEnabled(enabled: Boolean) {
		sharedPreferences.edit { putBoolean(Constants.PREF_CONNECTIVITY_WATCHDOG_ENABLED, enabled) }
		_connectivityWatchdogEnabled.value = enabled
	}

	fun setConnectivityWatchdogDebounceSeconds(seconds: Int) {
		sharedPreferences.edit {
			putInt(
				Constants.PREF_CONNECTIVITY_WATCHDOG_DEBOUNCE_SECONDS,
				seconds
			)
		}
		_connectivityWatchdogDebounceSeconds.value = seconds
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
		_connectivityWatchdogProbeTargets.value = finalValue
	}

	fun setHideLauncherIcon(hidden: Boolean) {
		sharedPreferences.edit { putBoolean(Constants.PREF_HIDE_LAUNCHER_ICON, hidden) }
		_hideLauncherIcon.value = hidden
	}

	fun setDisableDnsTest(disabled: Boolean) {
		sharedPreferences.edit { putBoolean(Constants.PREF_DISABLE_DNS_TEST, disabled) }
		_disableDnsTest.value = disabled
	}

	fun setVpnOverrideEnabled(enabled: Boolean) {
		VpnRepository.updateVpnOverrideEnabled(enabled)
	}

	fun setVpnDns(mode: String, hostname: String?) {
		VpnRepository.updateVpnDns(mode, hostname)
	}

	fun updateHostnameOrder(newList: List<DnsHostname>) {
		HostnameRepository.updateHostnamesOrder(newList.filter { !it.isUnsaved })
	}

	fun updateNetworkProfileOrder(newList: List<NetworkProfile>) {
		NetworkProfileRepository.updateNetworkProfilesOrder(newList.filter { !it.isAutoDetected })
	}

	fun dismissVpnHostnameWarning() {
		sharedPreferences.edit { remove(Constants.PREF_VPN_HOSTNAME_REMOVED_WARNING) }
		_vpnHostnameRemovedWarning.value = false
	}

	fun dismissKeyInvalidatedAlert() {
		_isKeyInvalidated.value = false
		SecurityRepository.resetKeyInvalidated()
	}

	fun refreshCurrentSsid() {
		_currentSsid.postValue(NetworkUtils.getCurrentWifiSsid(getApplication()))
	}

	fun getGlobalPreferredHostname(): String? {
		val app = getApplication<DnsToggleApplication>()
		val encryptedPrefs = app.getEncryptedPrefs()
		val encryptedHostname = encryptedPrefs.getString(Constants.PREF_LAST_USED_HOSTNAME, null)
		val lastUsed = encryptedHostname?.let {
			when (val result = EncryptionManager.decrypt(it)) {
				is EncryptionManager.DecryptResult.Success -> result.data
				else -> null
			}
		}
		if (!lastUsed.isNullOrEmpty()) return lastUsed

		return Settings.Global.getString(
			app.contentResolver,
			Constants.SETTINGS_PRIVATE_DNS_SPECIFIER
		)
	}

	override fun onCleared() {
		sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
		getApplication<Application>().contentResolver.unregisterContentObserver(dnsSettingsObserver)
	}
}
