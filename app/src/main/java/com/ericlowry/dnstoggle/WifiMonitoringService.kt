package com.ericlowry.dnstoggle

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings.Global
import android.util.Log

import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class WifiMonitoringService : Service() {

    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: NetworkCallback? = null

    companion object {
        private const val TAG = "WifiMonitoringService"
        private const val NOTIFICATION_ID_FOREGROUND = 2001
        private const val NOTIFICATION_ID_STATUS = 1001
        private const val CHANNEL_ID_SERVICE = "wifi_monitoring"
        private const val CHANNEL_ID_ALERT = "network_status"
    }

    private fun getPrefs(): SharedPreferences {
        return (application as DnsToggleApplication).getPrefs()
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        initializeNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID_FOREGROUND, createPersistentNotification())
        registerNetworkCallback()
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        (application as DnsToggleApplication).detectedSsid = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun initializeNotificationChannel() {
        val channelName = getString(R.string.service_notif_title)
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID_SERVICE, channelName, importance).apply {
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createPersistentNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID_SERVICE)
            .setContentTitle(getString(R.string.service_notif_title))
            .setContentText(getString(R.string.service_notif_text))
            .setSmallIcon(R.drawable.ic_qs_dns)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        networkCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    evaluateNetworkCapabilities(caps)
                }
                override fun onLost(network: Network) {
                    (application as DnsToggleApplication).detectedSsid = null
                    clearStatusNotification()
                    restorePreferredDns()
                }
            }
        } else {
            object : NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    evaluateNetworkCapabilities(caps)
                }
                override fun onLost(network: Network) {
                    (application as DnsToggleApplication).detectedSsid = null
                    clearStatusNotification()
                    restorePreferredDns()
                }
            }
        }

        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    private fun evaluateNetworkCapabilities(networkCapabilities: NetworkCapabilities) {
        val currentSsid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val wifiInfo = networkCapabilities.transportInfo as? WifiInfo
            wifiInfo?.ssid?.removePrefix("\"")?.removeSuffix("\"")
        } else {
            val wm = getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wm.connectionInfo?.ssid?.removePrefix("\"")?.removeSuffix("\"")
        }

        val app = application as DnsToggleApplication
        if ((currentSsid == null) || (currentSsid == "<unknown ssid>") || currentSsid.isEmpty()) {
            app.detectedSsid = null
            clearStatusNotification()
            restorePreferredDns()
            return
        }
        
        app.detectedSsid = currentSsid
        
        val sharedPreferences = getPrefs()
        val encryptedBlacklist = sharedPreferences.getStringSet("ssid_blacklist", emptySet()) ?: emptySet()
        val blacklistSet = encryptedBlacklist.asSequence()
            .mapNotNull { EncryptionManager.decrypt(it) }
            .toSet()

        if (blacklistSet.contains(currentSsid)) {
            applyOpportunisticDns(currentSsid)
        } else {
            clearStatusNotification()
            restorePreferredDns()
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { callback ->
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister network callback", e)
            }
        }
        networkCallback = null
    }

    private fun applyOpportunisticDns(ssid: String) {
        updateDnsSetting("opportunistic", ssid)
    }

    private fun restorePreferredDns() {
        val sharedPreferences = getPrefs()
        val preferredMode = sharedPreferences.getString("preferred_dns_mode", "hostname") ?: "hostname"
        updateDnsSetting(preferredMode, null)
    }

    private fun updateDnsSetting(newMode: String, ssidForNotification: String?) {
        try {
            val resolver = contentResolver
            val currentMode = Global.getString(resolver, "private_dns_mode")
            if (currentMode != newMode) {
                Global.putString(resolver, "private_dns_mode", newMode)
                ssidForNotification?.let { 
                    dispatchStatusNotification(getString(R.string.notif_dns_disabled_auto, it)) 
                }
                TileServiceCompat.requestListeningState(this, ComponentName(this, DnsToggleService::class.java))
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to update DNS setting", e)
        }
    }

    private fun dispatchStatusNotification(message: String) {
        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID_ALERT)
            .setSmallIcon(R.drawable.ic_qs_dns)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(this)) {
            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
            if (hasPermission) {
                notify(NOTIFICATION_ID_STATUS, notificationBuilder.build())
            }
        }
    }

    private fun clearStatusNotification() {
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID_STATUS)
    }
}
