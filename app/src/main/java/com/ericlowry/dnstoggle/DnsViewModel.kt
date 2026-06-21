package com.ericlowry.dnstoggle

import android.app.Application
import android.content.ComponentName
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

import java.net.InetSocketAddress
import java.net.Socket

import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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

    private val _dnsReachability = MutableLiveData(ReachabilityState.IDLE)
    val dnsReachability: LiveData<ReachabilityState> = _dnsReachability

    private val _hasPermissionError = MutableLiveData(false)
    val hasPermissionError: LiveData<Boolean> = _hasPermissionError

    private var reachabilityJob: Job? = null

    private val _ssidBlacklist = MutableLiveData<Set<String>>()
    val ssidBlacklist: LiveData<Set<String>> = _ssidBlacklist

    private val _autoBlacklistEnabled = MutableLiveData<Boolean>()
    val autoBlacklistEnabled: LiveData<Boolean> = _autoBlacklistEnabled

    private val _autoWhitelistEnabled = MutableLiveData<Boolean>()
    val autoWhitelistEnabled: LiveData<Boolean> = _autoWhitelistEnabled

    private val _hideLauncherIcon = MutableLiveData<Boolean>()
    val hideLauncherIcon: LiveData<Boolean> = _hideLauncherIcon

    private val _disableDnsTest = MutableLiveData<Boolean>()
    val disableDnsTest: LiveData<Boolean> = _disableDnsTest

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

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Constants.PREF_AUTO_BLACKLIST, Constants.PREF_AUTO_WHITELIST, Constants.PREF_HIDE_LAUNCHER_ICON, Constants.PREF_DISABLE_DNS_TEST -> loadSettings()
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
            DnsSettingsRepository.isKeyInvalidated.collect { invalidated ->
                if (invalidated) {
                    _isKeyInvalidated.postValue(true)
                }
            }
        }
        sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)

        getApplication<Application>().contentResolver.apply {
            registerContentObserver(Settings.Global.getUriFor(Constants.SETTINGS_PRIVATE_DNS_MODE), false, dnsSettingsObserver)
            registerContentObserver(Settings.Global.getUriFor(Constants.SETTINGS_PRIVATE_DNS_SPECIFIER), false, dnsSettingsObserver)
        }
    }

    fun loadSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val specifier = Settings.Global.getString(resolver, Constants.SETTINGS_PRIVATE_DNS_SPECIFIER)
            _privateDnsMode.postValue(Settings.Global.getString(resolver, Constants.SETTINGS_PRIVATE_DNS_MODE))
            _privateDnsSpecifier.postValue(specifier)
            
            _autoBlacklistEnabled.postValue(sharedPreferences.getBoolean(Constants.PREF_AUTO_BLACKLIST, false))
            _autoWhitelistEnabled.postValue(sharedPreferences.getBoolean(Constants.PREF_AUTO_WHITELIST, false))
            _hideLauncherIcon.postValue(sharedPreferences.getBoolean(Constants.PREF_HIDE_LAUNCHER_ICON, false))
            _disableDnsTest.postValue(sharedPreferences.getBoolean(Constants.PREF_DISABLE_DNS_TEST, false))
            
            if (!specifier.isNullOrEmpty() && NetworkUtils.isValidDnsHostname(specifier)) {
                testReachability(specifier)
            }
        }
    }

    fun addToBlacklist(ssid: String) {
        DnsSettingsRepository.addToBlacklist(ssid)
    }

    fun removeFromBlacklist(ssid: String) {
        DnsSettingsRepository.removeFromBlacklist(ssid)
    }

    fun updateSsidInBlacklist(oldSsid: String, newSsid: String) {
        DnsSettingsRepository.updateSsidInBlacklist(oldSsid, newSsid)
    }

    fun togglePrivateDns(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = DnsManager.togglePrivateDns(getApplication(), enabled)
            _hasPermissionError.postValue(result is DnsManager.ToggleResult.PermissionRequired)
            if (result is DnsManager.ToggleResult.Success) {
                _privateDnsMode.postValue(if (enabled) Constants.DNS_MODE_HOSTNAME else Constants.DNS_MODE_OPPORTUNISTIC)
            }
        }
    }

    fun updateCustomDns(address: String) {
        if (address.isNotEmpty() && !NetworkUtils.isValidDnsHostname(address)) {
            _dnsReachability.postValue(ReachabilityState.IDLE)
            return
        }
        try {
            Settings.Global.putString(getApplication<Application>().contentResolver, Constants.SETTINGS_PRIVATE_DNS_SPECIFIER, address)
            _privateDnsSpecifier.value = address
            _hasPermissionError.value = false
            testReachability(address)
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to update custom DNS specifier", e)
            _hasPermissionError.value = true
        }
    }

    private fun testReachability(hostname: String) {
        reachabilityJob?.cancel()
        if (hostname.isEmpty() || sharedPreferences.getBoolean(Constants.PREF_DISABLE_DNS_TEST, false)) {
            _dnsReachability.postValue(ReachabilityState.IDLE)
            return
        }

        reachabilityJob = viewModelScope.launch(Dispatchers.IO) {
            _dnsReachability.postValue(ReachabilityState.TESTING)
            val isReachable = try {
                val socket = Socket()
                socket.connect(InetSocketAddress(hostname, 853), 3000) // DoT port
                socket.close()
                true
            } catch (e: Exception) {
                Log.w(TAG, "Reachability test failed for $hostname: ${e.message}")
                false
            }
            _dnsReachability.postValue(if (isReachable) ReachabilityState.REACHABLE else ReachabilityState.UNREACHABLE)
        }
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

    fun dismissKeyInvalidatedAlert() {
        _isKeyInvalidated.value = false
        DnsSettingsRepository.resetKeyInvalidated()
    }

    override fun onCleared() {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        getApplication<Application>().contentResolver.unregisterContentObserver(dnsSettingsObserver)
    }
}
