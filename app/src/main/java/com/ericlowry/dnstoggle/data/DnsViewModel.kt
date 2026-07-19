package com.ericlowry.dnstoggle.data

import android.app.Application
import android.content.ComponentName
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.ericlowry.dnstoggle.DnsToggleApplication
import com.ericlowry.dnstoggle.R
import com.ericlowry.dnstoggle.service.DnsToggleService
import com.ericlowry.dnstoggle.service.TileServiceCompat
import com.ericlowry.dnstoggle.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class DnsViewModel(application: Application) : AndroidViewModel(application) {

	enum class ReachabilityState { IDLE, TESTING, REACHABLE, UNREACHABLE }

	companion object {
		private const val TAG = "DnsViewModel"
	}

	private val sharedPreferences = (application as DnsToggleApplication).getPrefs()

	private val _privateDnsMode = MutableLiveData<String?>()
	val privateDnsMode: LiveData<String?> = _privateDnsMode

	private val _privateDnsSpecifier = MutableLiveData<String?>()
	val privateDnsSpecifier: LiveData<String?> = _privateDnsSpecifier

	private val _dnsReachability = MutableLiveData<Map<String, ReachabilityState>>(emptyMap())
	val dnsReachability: LiveData<Map<String, ReachabilityState>> = _dnsReachability

	private val _hasPermissionError = MutableLiveData(false)
	val hasPermissionError: LiveData<Boolean> = _hasPermissionError

	private val reachabilityJobs = mutableMapOf<String, Job>()

	private val _ssidBlacklist = MutableLiveData<Set<String>>()
	val ssidBlacklist: LiveData<Set<String>> = _ssidBlacklist

	private val _dnsHostnames = MutableLiveData<List<DnsHostname>>()
	val dnsHostnames: LiveData<List<DnsHostname>> = _dnsHostnames

	private val _autoBlacklistEnabled = MutableLiveData<Boolean>()
	val autoBlacklistEnabled: LiveData<Boolean> = _autoBlacklistEnabled

	private val _autoWhitelistEnabled = MutableLiveData<Boolean>()
	val autoWhitelistEnabled: LiveData<Boolean> = _autoWhitelistEnabled

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

	private val _vpnHostnameRemovedWarning = MutableLiveData<Boolean>()
	val vpnHostnameRemovedWarning: LiveData<Boolean> = _vpnHostnameRemovedWarning

	private val _isInVpnOverride = MutableLiveData<Boolean>()
	val isInVpnOverride: LiveData<Boolean> = _isInVpnOverride

	private val _activeSsidOverride = MutableLiveData<String?>()
	val activeSsidOverride: LiveData<String?> = _activeSsidOverride

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
		SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
			when (key) {
				Constants.PREF_AUTO_BLACKLIST,
				Constants.PREF_AUTO_WHITELIST,
				Constants.PREF_HIDE_LAUNCHER_ICON,
				Constants.PREF_DISABLE_DNS_TEST,
				Constants.PREF_SHOW_TOAST,
				Constants.PREF_IS_IN_VPN_OVERRIDE,
				Constants.PREF_ACTIVE_SSID_OVERRIDE,
					-> loadSettings()
			}
		}

	init {
		loadSettings()
		viewModelScope.launch {
			DnsSettingsRepository.blacklist.collect { list ->
				list?.let { _ssidBlacklist.postValue(it) }
			}
		}
		viewModelScope.launch {
			DnsSettingsRepository.dnsHostnames.collect {
				refreshDisplayList()
			}
		}
		viewModelScope.launch {
			DnsSettingsRepository.isKeyInvalidated.collect { invalidated ->
				if (invalidated) {
					_isKeyInvalidated.postValue(true)
				}
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
		viewModelScope.launch(Dispatchers.IO) {
			val resolver = getApplication<Application>().contentResolver
			val mode = Settings.Global.getString(resolver, Constants.SETTINGS_PRIVATE_DNS_MODE)
			val specifier =
				Settings.Global.getString(resolver, Constants.SETTINGS_PRIVATE_DNS_SPECIFIER)

			val autoBlacklist = sharedPreferences.getBoolean(Constants.PREF_AUTO_BLACKLIST, false)
			val autoWhitelist = sharedPreferences.getBoolean(Constants.PREF_AUTO_WHITELIST, false)
			val hideLauncher =
				sharedPreferences.getBoolean(Constants.PREF_HIDE_LAUNCHER_ICON, false)
			val disableDnsTest =
				sharedPreferences.getBoolean(Constants.PREF_DISABLE_DNS_TEST, false)
			val showToast = sharedPreferences.getBoolean(Constants.PREF_SHOW_TOAST, true)
			val vpnOverride = DnsSettingsRepository.vpnOverrideEnabled.value
			val vpnDns = DnsSettingsRepository.vpnDnsHostname.value
			val vpnRemovedWarning =
				sharedPreferences.getBoolean(Constants.PREF_VPN_HOSTNAME_REMOVED_WARNING, false)
			val isInVpn = sharedPreferences.getBoolean(Constants.PREF_IS_IN_VPN_OVERRIDE, false)
			val ssidOverride =
				sharedPreferences.getString(Constants.PREF_ACTIVE_SSID_OVERRIDE, null)

			withContext(Dispatchers.Main) {
				_privateDnsMode.value = mode
				_privateDnsSpecifier.value = specifier
				_autoBlacklistEnabled.value = autoBlacklist
				_autoWhitelistEnabled.value = autoWhitelist
				_hideLauncherIcon.value = hideLauncher
				_disableDnsTest.value = disableDnsTest
				_showToastEnabled.value = showToast
				_vpnOverrideEnabled.value = vpnOverride
				_vpnDnsHostname.value = vpnDns
				_vpnHostnameRemovedWarning.value = vpnRemovedWarning
				_isInVpnOverride.value = isInVpn
				_activeSsidOverride.value = ssidOverride
				refreshDisplayList()
			}
		}

		viewModelScope.launch {
			DnsSettingsRepository.vpnOverrideEnabled.collect {
				_vpnOverrideEnabled.postValue(it)
			}
		}

		viewModelScope.launch {
			DnsSettingsRepository.vpnDnsHostname.collect {
				_vpnDnsHostname.postValue(it)
			}
		}
	}

	fun addToBlacklist(ssid: String) {
		DnsSettingsRepository.addToBlacklist(ssid)
	}

	private fun refreshDisplayList() {
		val savedHostnames = DnsSettingsRepository.dnsHostnames.value ?: emptyList()
		val currentSpecifier = _privateDnsSpecifier.value
		val currentMode = _privateDnsMode.value

		val displayList = savedHostnames.toMutableList()

		if ((currentMode == Constants.DNS_MODE_HOSTNAME) && !currentSpecifier.isNullOrEmpty()) {
			if (savedHostnames.none { it.hostname == currentSpecifier }) {
				displayList.add(
					0,
					DnsHostname(
						hostname = currentSpecifier,
						label = getApplication<Application>().getString(R.string.unsaved_active_label),
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

	fun removeFromBlacklist(ssid: String) {
		DnsSettingsRepository.removeFromBlacklist(ssid)
	}

	fun updateSsidInBlacklist(oldSsid: String, newSsid: String) {
		DnsSettingsRepository.updateSsidInBlacklist(oldSsid, newSsid)
	}

	fun addHostname(hostname: String, label: String? = null) {
		DnsSettingsRepository.addHostname(hostname, label)
		if (NetworkUtils.isValidDnsHostname(hostname)) {
			testReachability(hostname)
		}
	}

	fun removeHostname(hostname: String) {
		DnsSettingsRepository.removeHostname(hostname)
		reachabilityJobs[hostname]?.cancel()
		reachabilityJobs.remove(hostname)
		val currentMap = _dnsReachability.value?.toMutableMap() ?: mutableMapOf()
		currentMap.remove(hostname)
		_dnsReachability.postValue(currentMap)
	}

	fun updateHostname(oldHostname: String, newHostname: String, newLabel: String? = null) {
		DnsSettingsRepository.updateHostname(oldHostname, newHostname, newLabel)
		reachabilityJobs[oldHostname]?.cancel()
		reachabilityJobs.remove(oldHostname)
		val currentMap = _dnsReachability.value?.toMutableMap() ?: mutableMapOf()
		currentMap.remove(oldHostname)
		_dnsReachability.postValue(currentMap)

		if (NetworkUtils.isValidDnsHostname(newHostname)) {
			testReachability(newHostname)
		}
	}

	fun togglePrivateDns(enabled: Boolean, targetHostname: String? = null) {
		viewModelScope.launch(Dispatchers.IO) {
			val result = DnsManager.togglePrivateDns(
				getApplication(),
				enabled,
				targetHostname,
				isInteractiveMainUi = true
			)
			_hasPermissionError.postValue(result is DnsManager.ToggleResult.PermissionRequired)
			if (result is DnsManager.ToggleResult.Success) {
				_privateDnsMode.postValue(if (enabled) Constants.DNS_MODE_HOSTNAME else Constants.DNS_MODE_OPPORTUNISTIC)
				if (enabled && (targetHostname != null)) {
					_privateDnsSpecifier.postValue(targetHostname)
					if (NetworkUtils.isValidDnsHostname(targetHostname)) {
						testReachability(targetHostname)
					}
				}
			}
		}
	}

	fun setShowToast(enabled: Boolean) {
		sharedPreferences.edit { putBoolean(Constants.PREF_SHOW_TOAST, enabled) }
		_showToastEnabled.value = enabled
	}

	private fun testReachability(hostname: String) {
		reachabilityJobs[hostname]?.cancel()

		if (hostname.isEmpty() || sharedPreferences.getBoolean(
				Constants.PREF_DISABLE_DNS_TEST,
				false
			)
		) {
			val currentMap = _dnsReachability.value?.toMutableMap() ?: mutableMapOf()
			currentMap[hostname] = ReachabilityState.IDLE
			_dnsReachability.postValue(currentMap)
			return
		}

		val job = viewModelScope.launch(Dispatchers.IO) {
			val startMap = _dnsReachability.value?.toMutableMap() ?: mutableMapOf()
			startMap[hostname] = ReachabilityState.TESTING
			_dnsReachability.postValue(startMap)

			val isReachable = try {
				val socket = Socket()
				socket.connect(InetSocketAddress(hostname, 853), 3000) // DoT port
				socket.close()
				true
			} catch (e: Exception) {
				Log.w(TAG, "Reachability test failed for $hostname: ${e.message}")
				false
			}

			val endMap = _dnsReachability.value?.toMutableMap() ?: mutableMapOf()
			endMap[hostname] =
				if (isReachable) ReachabilityState.REACHABLE else ReachabilityState.UNREACHABLE
			_dnsReachability.postValue(endMap)
		}
		reachabilityJobs[hostname] = job
	}

	fun setAutoBlacklist(enabled: Boolean) {
		sharedPreferences.edit { putBoolean(Constants.PREF_AUTO_BLACKLIST, enabled) }
		_autoBlacklistEnabled.value = enabled
	}

	fun setAutoWhitelist(enabled: Boolean) {
		sharedPreferences.edit { putBoolean(Constants.PREF_AUTO_WHITELIST, enabled) }
		_autoWhitelistEnabled.value = enabled
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
		DnsSettingsRepository.updateVpnOverrideEnabled(enabled)
	}

	fun setVpnDnsHostname(hostname: String) {
		DnsSettingsRepository.updateVpnDnsHostname(hostname)
	}

	fun updateHostnameOrder(newList: List<DnsHostname>) {
		DnsSettingsRepository.updateHostnamesOrder(newList.filter { !it.isUnsaved })
	}

	fun dismissVpnHostnameWarning() {
		sharedPreferences.edit { remove(Constants.PREF_VPN_HOSTNAME_REMOVED_WARNING) }
		_vpnHostnameRemovedWarning.value = false
	}

	fun dismissKeyInvalidatedAlert() {
		_isKeyInvalidated.value = false
		DnsSettingsRepository.resetKeyInvalidated()
	}

	override fun onCleared() {
		sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
		getApplication<Application>().contentResolver.unregisterContentObserver(dnsSettingsObserver)
	}
}
