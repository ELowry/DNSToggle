package com.ericlowry.dnstoggle

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit

object DnsManager {
    private const val TAG = "DnsManager"

    sealed class ToggleResult {
        data object Success : ToggleResult()
        data object PermissionRequired : ToggleResult()
        data object MissingHostname : ToggleResult()
        data object Error : ToggleResult()
    }

    fun togglePrivateDns(context: Context, enabled: Boolean): ToggleResult {
        val resolver = context.contentResolver
        val app = context.applicationContext as DnsToggleApplication
        
        if (enabled) {
            val specifier = Settings.Global.getString(resolver, Constants.SETTINGS_PRIVATE_DNS_SPECIFIER)
            if (specifier.isNullOrEmpty() || !NetworkUtils.isValidDnsHostname(specifier)) {
                return ToggleResult.MissingHostname
            }
        }

        val newMode = if (enabled) Constants.DNS_MODE_HOSTNAME else Constants.DNS_MODE_OPPORTUNISTIC
        val currentSsid = NetworkUtils.getCurrentWifiSsid(context)
        val sharedPreferences = app.getPrefs()
        
        var handledByAuto = false
        
        if (currentSsid != null) {
            if (!enabled && sharedPreferences.getBoolean(Constants.PREF_AUTO_BLACKLIST, false)) {
                DnsSettingsRepository.addToBlacklist(currentSsid)
                NotificationUtils.showStatusNotification(context, context.getString(R.string.notif_ssid_added, currentSsid))
                handledByAuto = true
            } else if (enabled && sharedPreferences.getBoolean(Constants.PREF_AUTO_WHITELIST, false)) {
                DnsSettingsRepository.removeFromBlacklist(currentSsid)
                NotificationUtils.showStatusNotification(context, context.getString(R.string.notif_ssid_removed, currentSsid))
                handledByAuto = true
            }
        }
        
        if (!handledByAuto) {
            sharedPreferences.edit { putString(Constants.PREF_PREFERRED_DNS_MODE, newMode) }
        }

        return try {
            Settings.Global.putString(resolver, Constants.SETTINGS_PRIVATE_DNS_MODE, newMode)
            requestTileUpdate(context)
            ToggleResult.Success
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to update private DNS mode", e)
            ToggleResult.PermissionRequired
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error updating private DNS mode", e)
            ToggleResult.Error
        }
    }

    fun requestTileUpdate(context: Context) {
        TileServiceCompat.requestListeningState(context, ComponentName(context, DnsToggleService::class.java))
    }
}
