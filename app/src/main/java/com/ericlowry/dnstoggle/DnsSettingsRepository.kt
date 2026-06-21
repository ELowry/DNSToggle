package com.ericlowry.dnstoggle

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object DnsSettingsRepository {
    private lateinit var app: DnsToggleApplication
    private lateinit var sharedPreferences: SharedPreferences
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _blacklist = MutableStateFlow<Set<String>?>(null)
    val blacklist: StateFlow<Set<String>?> = _blacklist.asStateFlow()

    private val _isKeyInvalidated = MutableStateFlow(false)
    val isKeyInvalidated: StateFlow<Boolean> = _isKeyInvalidated.asStateFlow()

    fun initialize(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext as DnsToggleApplication
        sharedPreferences = app.getPrefs()
        loadBlacklist()
    }

    private fun loadBlacklist() {
        scope.launch {
            val encryptedSet = sharedPreferences.getStringSet(Constants.PREF_SSID_BLACKLIST, emptySet()) ?: emptySet()
            
            var keyInvalidated = false
            val decryptedSet = encryptedSet.mapNotNull { 
                when (val result = EncryptionManager.decrypt(it)) {
                    is EncryptionManager.DecryptResult.Success -> result.data
                    is EncryptionManager.DecryptResult.KeyInvalidated -> {
                        keyInvalidated = true
                        null
                    }
                    else -> null
                }
            }.toSet()

            if (keyInvalidated) {
                _isKeyInvalidated.value = true
                sharedPreferences.edit { remove(Constants.PREF_SSID_BLACKLIST) }
            }
            _blacklist.value = decryptedSet
        }
    }

    fun resetKeyInvalidated() {
        _isKeyInvalidated.value = false
    }

    fun addToBlacklist(ssid: String) {
        _blacklist.update { current ->
            val safeCurrent = current ?: emptySet()
            if (ssid in safeCurrent) return@update safeCurrent
            val next = safeCurrent + ssid
            saveBlacklistAsync(next)
            next
        }
    }

    fun removeFromBlacklist(ssid: String) {
        _blacklist.update { current ->
            val safeCurrent = current ?: emptySet()
            if (ssid !in safeCurrent) return@update safeCurrent
            val next = safeCurrent - ssid
            saveBlacklistAsync(next)
            next
        }
    }

    fun updateSsidInBlacklist(oldSsid: String, newSsid: String) {
        _blacklist.update { current ->
            val safeCurrent = current ?: emptySet()
            if (oldSsid !in safeCurrent) return@update safeCurrent
            val next = safeCurrent - oldSsid + newSsid
            saveBlacklistAsync(next)
            next
        }
    }

    private fun saveBlacklistAsync(list: Set<String>) {
        scope.launch {
            val encryptedSet = list.map { EncryptionManager.encrypt(it) }.toSet()
            sharedPreferences.edit { putStringSet(Constants.PREF_SSID_BLACKLIST, encryptedSet) }
        }
    }
}
