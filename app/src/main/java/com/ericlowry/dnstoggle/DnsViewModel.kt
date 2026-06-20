package com.ericlowry.dnstoggle

import android.app.Application
import android.content.SharedPreferences
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

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "ssid_blacklist" -> loadBlacklist()
            "auto_blacklist", "auto_whitelist", "hide_launcher_icon" -> loadSettings()
        }
    }

    init {
        loadSettings()
        loadBlacklist()
        sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    fun loadSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val specifier = Settings.Global.getString(resolver, "private_dns_specifier")
            _privateDnsMode.postValue(Settings.Global.getString(resolver, "private_dns_mode"))
            _privateDnsSpecifier.postValue(specifier)
            
            _autoBlacklistEnabled.postValue(sharedPreferences.getBoolean("auto_blacklist", false))
            _autoWhitelistEnabled.postValue(sharedPreferences.getBoolean("auto_whitelist", false))
            _hideLauncherIcon.postValue(sharedPreferences.getBoolean("hide_launcher_icon", false))
            
            if (!specifier.isNullOrEmpty() && NetworkUtils.isValidDnsHostname(specifier)) {
                testReachability(specifier)
            }
        }
    }

    fun loadBlacklist() {
        val encryptedSet = sharedPreferences.getStringSet("ssid_blacklist", emptySet()) ?: emptySet()
        val decryptedSet = encryptedSet.asSequence()
            .mapNotNull { EncryptionManager.decrypt(it) }
            .toSet()
        _ssidBlacklist.postValue(decryptedSet)
    }

    fun togglePrivateDns(enabled: Boolean) {
        val resolver = getApplication<Application>().contentResolver
        if (enabled) {
            val specifier = Settings.Global.getString(resolver, "private_dns_specifier")
            if (specifier.isNullOrEmpty() || !NetworkUtils.isValidDnsHostname(specifier)) return
        }

        val newMode = if (enabled) "hostname" else "opportunistic"
        val currentSsid = NetworkUtils.getCurrentWifiSsid(getApplication())
        
        var handledByAuto = false
        
        if (currentSsid != null) {
            if (!enabled && sharedPreferences.getBoolean("auto_blacklist", false)) {
                addToBlacklist(currentSsid)
                handledByAuto = true
            } else if (enabled && sharedPreferences.getBoolean("auto_whitelist", false)) {
                removeFromBlacklist(currentSsid)
                handledByAuto = true
            }
        }
        
        if (!handledByAuto) {
            savePreferredDnsMode(newMode)
        }

        try {
            Settings.Global.putString(resolver, "private_dns_mode", newMode)
            _privateDnsMode.value = newMode
            _hasPermissionError.value = false
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to toggle Private DNS mode", e)
            _hasPermissionError.value = true
        }
    }

    fun updateCustomDns(address: String) {
        if (address.isNotEmpty() && !NetworkUtils.isValidDnsHostname(address)) {
            _dnsReachability.postValue(ReachabilityState.IDLE)
            return
        }
        try {
            Settings.Global.putString(getApplication<Application>().contentResolver, "private_dns_specifier", address)
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
        if (hostname.isEmpty()) {
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
        sharedPreferences.edit { putBoolean("auto_blacklist", enabled) }
        _autoBlacklistEnabled.value = enabled
        notifyMonitoringChange()
    }

    fun setAutoWhitelist(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("auto_whitelist", enabled) }
        _autoWhitelistEnabled.value = enabled
        notifyMonitoringChange()
    }

    fun setHideLauncherIcon(hidden: Boolean) {
        sharedPreferences.edit { putBoolean("hide_launcher_icon", hidden) }
        _hideLauncherIcon.value = hidden
    }

    fun addToBlacklist(ssid: String) {
        val currentList = _ssidBlacklist.value?.toMutableSet() ?: mutableSetOf()
        if (currentList.add(ssid)) {
            saveBlacklist(currentList)
        }
    }

    fun removeFromBlacklist(ssid: String) {
        val currentList = _ssidBlacklist.value?.toMutableSet() ?: mutableSetOf()
        if (currentList.remove(ssid)) {
            saveBlacklist(currentList)
        }
    }

    fun updateSsidInBlacklist(oldSsid: String, newSsid: String) {
        val currentList = _ssidBlacklist.value?.toMutableSet() ?: mutableSetOf()
        currentList.remove(oldSsid)
        currentList.add(newSsid)
        saveBlacklist(currentList)
    }

    private fun saveBlacklist(list: Set<String>) {
        val encryptedSet = list.map { EncryptionManager.encrypt(it) }.toSet()
        sharedPreferences.edit { putStringSet("ssid_blacklist", encryptedSet) }
        _ssidBlacklist.value = list
        notifyMonitoringChange()
    }

    private fun savePreferredDnsMode(mode: String) {
        sharedPreferences.edit { putString("preferred_dns_mode", mode) }
    }

    private fun notifyMonitoringChange() {
        (getApplication<Application>() as DnsToggleApplication).updateWifiMonitoringRegistration()
    }

    override fun onCleared() {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
    }
}
