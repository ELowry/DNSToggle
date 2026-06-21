package com.ericlowry.dnstoggle

object Constants {
    // Settings.Global keys
    const val SETTINGS_PRIVATE_DNS_MODE = "private_dns_mode"
    const val SETTINGS_PRIVATE_DNS_SPECIFIER = "private_dns_specifier"

    // DNS Modes
    const val DNS_MODE_OPPORTUNISTIC = "opportunistic"
    const val DNS_MODE_HOSTNAME = "hostname"

    // Shared Preferences Keys
    const val PREF_SSID_BLACKLIST = "ssid_blacklist"
    const val PREF_AUTO_BLACKLIST = "auto_blacklist"
    const val PREF_AUTO_WHITELIST = "auto_whitelist"
    const val PREF_HIDE_LAUNCHER_ICON = "hide_launcher_icon"
    const val PREF_DISABLE_DNS_TEST = "disable_dns_test"
    const val PREF_PREFERRED_DNS_MODE = "preferred_dns_mode"
    const val PREF_DYNAMIC_APP_NAME = "dynamic_app_name"

    // Notification Channels
    const val CHANNEL_ID_SERVICE = "wifi_monitoring"
    const val CHANNEL_ID_ALERT = "network_status"

    // Notification IDs
    const val NOTIFICATION_ID_FOREGROUND = 2001
    const val NOTIFICATION_ID_STATUS = 1001
}
