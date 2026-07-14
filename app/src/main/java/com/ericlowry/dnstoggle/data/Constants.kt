package com.ericlowry.dnstoggle.data

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
	const val PREF_DNS_HOSTNAMES = "dns_hostnames"
	const val PREF_SHOW_TOAST = "show_toast_notification"
	const val PREF_LAST_USED_HOSTNAME = "last_used_hostname"
	const val PREF_USB_DEBUGGING_TILE_UNLOCKED = "usb_debugging_tile_unlocked"

	// VPN Override Keys
	const val PREF_VPN_OVERRIDE_ENABLED = "vpn_override_enabled"
	const val PREF_VPN_DNS_HOSTNAME = "vpn_dns_hostname"
	const val PREF_PRE_VPN_DNS_MODE = "pre_vpn_dns_mode"
	const val PREF_PRE_VPN_DNS_SPECIFIER = "pre_vpn_dns_specifier"
	const val PREF_IS_IN_VPN_OVERRIDE = "is_in_vpn_override"
	const val PREF_ACTIVE_SSID_OVERRIDE = "active_ssid_override"
	const val PREF_VPN_HOSTNAME_REMOVED_WARNING = "vpn_hostname_removed_warning"

	// Notification Channels
	const val CHANNEL_ID_SERVICE = "wifi_monitoring"
	const val CHANNEL_ID_ALERT = "network_status"

	// Notification IDs
	const val NOTIFICATION_ID_FOREGROUND = 2001
	const val NOTIFICATION_ID_STATUS = 1001
}
