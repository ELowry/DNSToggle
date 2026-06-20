package com.ericlowry.dnstoggle

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.material.color.DynamicColors

class DnsToggleApplication : Application() {

    var detectedSsid: String? = null

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        initializeNotificationChannels()
        initializePreferredDnsMode()
        updateWifiMonitoringRegistration()
    }

    fun getPrefs(): SharedPreferences {
        return getSharedPreferences("app_prefs_v2", MODE_PRIVATE)
    }

    private fun initializeNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        
        val statusChannel = NotificationChannel(
            "network_status",
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        
        val serviceChannel = NotificationChannel(
            "wifi_monitoring",
            getString(R.string.service_notif_title),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
        }
        
        manager.createNotificationChannels(listOf(statusChannel, serviceChannel))
    }

    private fun initializePreferredDnsMode() {
        val sharedPreferences = getPrefs()
        if (!sharedPreferences.contains("preferred_dns_mode")) {
            val currentMode = Settings.Global.getString(contentResolver, "private_dns_mode") ?: "opportunistic"
            sharedPreferences.edit { putString("preferred_dns_mode", currentMode) }
        }
    }

    fun updateWifiMonitoringRegistration() {
        val serviceIntent = Intent(this, WifiMonitoringService::class.java)
        
        if (isWifiMonitoringRequired()) {
            startForegroundService(serviceIntent)
        } else {
            stopService(serviceIntent)
            detectedSsid = null
        }
    }

    private fun isWifiMonitoringRequired(): Boolean {
        val sharedPreferences = getPrefs()
        val blacklistSet = sharedPreferences.getStringSet("ssid_blacklist", emptySet<String>()) ?: emptySet()
        val isAutoManagementEnabled = sharedPreferences.getBoolean("auto_blacklist", false) || 
                                      sharedPreferences.getBoolean("auto_whitelist", false)
        
        if (blacklistSet.isEmpty() && !isAutoManagementEnabled) return false

        val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        
        return ContextCompat.checkSelfPermission(this, requiredPermission) == PackageManager.PERMISSION_GRANTED
    }
}
