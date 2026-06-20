package com.ericlowry.dnstoggle

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.core.content.edit

class DnsViewModel(application: Application) : AndroidViewModel(application) {

    private val sharedPreferences = application.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    private val _privateDnsMode = MutableLiveData<String?>()
    val privateDnsMode: LiveData<String?> = _privateDnsMode

    private val _privateDnsSpecifier = MutableLiveData<String?>()
    val privateDnsSpecifier: LiveData<String?> = _privateDnsSpecifier

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
        val resolver = getApplication<Application>().contentResolver
        _privateDnsMode.postValue(Settings.Global.getString(resolver, "private_dns_mode"))
        _privateDnsSpecifier.postValue(Settings.Global.getString(resolver, "private_dns_specifier"))
        
        _autoBlacklistEnabled.postValue(sharedPreferences.getBoolean("auto_blacklist", false))
        _autoWhitelistEnabled.postValue(sharedPreferences.getBoolean("auto_whitelist", false))
        _hideLauncherIcon.postValue(sharedPreferences.getBoolean("hide_launcher_icon", false))
    }

    fun loadBlacklist() {
        _ssidBlacklist.postValue(sharedPreferences.getStringSet("ssid_blacklist", emptySet()) ?: emptySet())
    }

    fun togglePrivateDns(enabled: Boolean) {
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
            Settings.Global.putString(getApplication<Application>().contentResolver, "private_dns_mode", newMode)
            _privateDnsMode.value = newMode
        } catch (_: SecurityException) { }
    }

    fun updateCustomDns(address: String) {
        try {
            Settings.Global.putString(getApplication<Application>().contentResolver, "private_dns_specifier", address)
            _privateDnsSpecifier.value = address
        } catch (_: SecurityException) { }
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
        sharedPreferences.edit { putStringSet("ssid_blacklist", list) }
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
