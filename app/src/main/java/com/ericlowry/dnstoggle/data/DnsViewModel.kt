package com.ericlowry.dnstoggle.data

import android.app.Application
import android.content.ComponentName
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.ericlowry.dnstoggle.DnsToggleApplication
import com.ericlowry.dnstoggle.R
import com.ericlowry.dnstoggle.data.repository.AppSettingsRepository
import com.ericlowry.dnstoggle.data.repository.HostnameRepository
import com.ericlowry.dnstoggle.data.repository.NetworkProfileRepository
import com.ericlowry.dnstoggle.data.repository.SecurityRepository
import com.ericlowry.dnstoggle.data.repository.VpnRepository
import com.ericlowry.dnstoggle.service.DnsToggleService
import com.ericlowry.dnstoggle.service.TileServiceCompat
import com.ericlowry.dnstoggle.util.EncryptionManager
import com.ericlowry.dnstoggle.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the DNS Toggle UI.
 * Acts as a bridge between the UI and multiple repository singletons.
 * Cleaned up by delegating settings and reachability testing to specialized repositories.
 */
class DnsViewModel(application: Application) : AndroidViewModel(application) {

	var ioDispatcher = Dispatchers.IO

	private val _privateDnsMode = MutableLiveData<String?>()
	val privateDnsMode: LiveData<String?> = _privateDnsMode

	private val _privateDnsSpecifier = MutableLiveData<String?>()
	val privateDnsSpecifier: LiveData<String?> = _privateDnsSpecifier

	private val _dnsReachability =
		MutableLiveData<Map<String, ReachabilityManager.ReachabilityState>>(emptyMap())
	val dnsReachability: LiveData<Map<String, ReachabilityManager.ReachabilityState>> =
		_dnsReachability

	private val _hasPermissionError = MutableLiveData(false)
	val hasPermissionError: LiveData<Boolean> = _hasPermissionError

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
			refreshSystemSettings()
			TileServiceCompat.requestListeningState(
				getApplication(),
				ComponentName(getApplication(), DnsToggleService::class.java),
			)
		}
	}

	init {
		refreshSystemSettings()

		// Repositories observation
		viewModelScope.launch {
			NetworkProfileRepository.networkProfiles.collect { _networkProfiles.postValue(it) }
		}
		viewModelScope.launch {
			HostnameRepository.dnsHostnames.collect { refreshDisplayList() }
		}
		viewModelScope.launch {
			SecurityRepository.isKeyInvalidated.collect { _isKeyInvalidated.postValue(it) }
		}
		viewModelScope.launch {
			VpnRepository.vpnOverrideEnabled.collect { _vpnOverrideEnabled.postValue(it) }
		}
		viewModelScope.launch {
			VpnRepository.vpnDnsHostname.collect { _vpnDnsHostname.postValue(it) }
		}
		viewModelScope.launch {
			VpnRepository.vpnDnsMode.collect { _vpnDnsMode.postValue(it) }
		}

		// Settings observation from AppSettingsRepository
		viewModelScope.launch {
			AppSettingsRepository.autoSaveStateEnabled.collect { _autoSaveStateEnabled.postValue(it) }
		}
		viewModelScope.launch {
			AppSettingsRepository.autoSaveHostEnabled.collect { _autoSaveHostEnabled.postValue(it) }
		}
		viewModelScope.launch {
			AppSettingsRepository.hideLauncherIcon.collect { _hideLauncherIcon.postValue(it) }
		}
		viewModelScope.launch {
			AppSettingsRepository.disableDnsTest.collect {
				_disableDnsTest.postValue(it)
				refreshDisplayList()
			}
		}
		viewModelScope.launch {
			AppSettingsRepository.showToastEnabled.collect { _showToastEnabled.postValue(it) }
		}
		viewModelScope.launch {
			AppSettingsRepository.isInVpnOverride.collect { _isInVpnOverride.postValue(it) }
		}
		viewModelScope.launch {
			AppSettingsRepository.activeSsidOverride.collect { _activeSsidOverride.postValue(it) }
		}
		viewModelScope.launch {
			AppSettingsRepository.connectivityWatchdogEnabled.collect {
				_connectivityWatchdogEnabled.postValue(it)
			}
		}
		viewModelScope.launch {
			AppSettingsRepository.connectivityWatchdogDebounceSeconds.collect {
				_connectivityWatchdogDebounceSeconds.postValue(it)
			}
		}
		viewModelScope.launch {
			AppSettingsRepository.connectivityWatchdogProbeTargets.collect {
				_connectivityWatchdogProbeTargets.postValue(it)
			}
		}
		viewModelScope.launch {
			AppSettingsRepository.enableStrictOffOption.collect {
				_enableStrictOffOption.postValue(it)
			}
		}
		viewModelScope.launch {
			AppSettingsRepository.defaultOffMode.collect { _defaultOffMode.postValue(it) }
		}
		viewModelScope.launch {
			AppSettingsRepository.vpnHostnameRemovedWarning.collect {
				_vpnHostnameRemovedWarning.postValue(it)
			}
		}

		// Reachability observation
		viewModelScope.launch {
			ReachabilityManager.reachabilityStates.collect { _dnsReachability.postValue(it) }
		}

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

	override fun onCleared() {
		getApplication<Application>().contentResolver.unregisterContentObserver(dnsSettingsObserver)
	}

	/**
	 * Reloads the current system-wide Private DNS settings.
	 */
	fun refreshSystemSettings() {
		viewModelScope.launch(ioDispatcher) {
			val resolver = getApplication<Application>().contentResolver
			val mode = Settings.Global.getString(resolver, Constants.SETTINGS_PRIVATE_DNS_MODE)
			val specifier =
				Settings.Global.getString(resolver, Constants.SETTINGS_PRIVATE_DNS_SPECIFIER)

			withContext(Dispatchers.Main) {
				_privateDnsMode.value = mode
				_privateDnsSpecifier.value = specifier
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

	fun removeNetworkProfile(ssid: String) {
		NetworkProfileRepository.removeNetworkProfile(ssid)
	}

	fun promoteSsidToPermanent(ssid: String) {
		NetworkProfileRepository.promoteUnsavedProfile(ssid)
	}

	fun addHostname(hostname: String, label: String? = null) {
		HostnameRepository.addHostname(hostname, label)
		if (NetworkUtils.isValidDnsHostname(hostname)) {
			ReachabilityManager.testHost(hostname, AppSettingsRepository.disableDnsTest.value)
		}
	}

	fun removeHostname(hostname: String) {
		HostnameRepository.removeHostname(hostname)
		ReachabilityManager.clearHost(hostname)
	}

	fun updateHostname(oldHostname: String, newHostname: String, newLabel: String? = null) {
		HostnameRepository.updateHostname(oldHostname, newHostname, newLabel)
		ReachabilityManager.clearHost(oldHostname)
		if (NetworkUtils.isValidDnsHostname(newHostname)) {
			ReachabilityManager.testHost(newHostname, AppSettingsRepository.disableDnsTest.value)
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
				refreshSystemSettings()
			}
		}
	}

	fun setEnableStrictOffOption(enabled: Boolean) {
		AppSettingsRepository.setEnableStrictOffOption(enabled)
	}

	fun setDefaultOffMode(mode: String) {
		AppSettingsRepository.setDefaultOffMode(mode)
	}

	fun setShowToast(enabled: Boolean) {
		AppSettingsRepository.setShowToast(enabled)
	}

	fun setAutoSaveState(enabled: Boolean) {
		AppSettingsRepository.setAutoSaveState(enabled)
	}

	fun setAutoSaveHost(enabled: Boolean) {
		AppSettingsRepository.setAutoSaveHost(enabled)
	}

	fun setConnectivityWatchdogEnabled(enabled: Boolean) {
		AppSettingsRepository.setConnectivityWatchdogEnabled(enabled)
	}

	fun setConnectivityWatchdogDebounceSeconds(seconds: Int) {
		AppSettingsRepository.setConnectivityWatchdogDebounceSeconds(seconds)
	}

	fun setConnectivityWatchdogProbeTargets(targets: String) {
		AppSettingsRepository.setConnectivityWatchdogProbeTargets(targets)
	}

	fun setHideLauncherIcon(hidden: Boolean) {
		AppSettingsRepository.setHideLauncherIcon(hidden)
	}

	fun setDisableDnsTest(disabled: Boolean) {
		AppSettingsRepository.setDisableDnsTest(disabled)
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
		AppSettingsRepository.dismissVpnHostnameWarning()
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
		if (!lastUsed.isNullOrEmpty()) {
			return lastUsed
		}

		return Settings.Global.getString(
			app.contentResolver,
			Constants.SETTINGS_PRIVATE_DNS_SPECIFIER
		)
	}

	private fun refreshDisplayList() {
		val savedHostnames = HostnameRepository.dnsHostnames.value ?: return
		val currentSpecifier = _privateDnsSpecifier.value
		val currentMode = _privateDnsMode.value

		val displayList = savedHostnames.toMutableList()

		if (!currentSpecifier.isNullOrEmpty()) {
			if (savedHostnames.none { it.hostname == currentSpecifier }) {
				val isTrulyActive = currentMode == Constants.DNS_MODE_HOSTNAME
				val labelRes = if (isTrulyActive) {
					R.string.unsaved_active_label
				} else {
					R.string.unsaved_inactive_label
				}

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

		val disableTest = AppSettingsRepository.disableDnsTest.value
		displayList.forEach { dnsEntry ->
			val hostname = dnsEntry.hostname
			if (NetworkUtils.isValidDnsHostname(hostname)) {
				ReachabilityManager.testHost(hostname, disableTest)
			}
		}
	}
}
