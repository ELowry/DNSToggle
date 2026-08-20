package com.ericlowry.dnstoggle.data

object Constants {
	// Settings.Global keys
	const val SETTINGS_PRIVATE_DNS_MODE = "private_dns_mode"
	const val SETTINGS_PRIVATE_DNS_SPECIFIER = "private_dns_specifier"

	// DNS Modes
	const val DNS_MODE_OPPORTUNISTIC = "opportunistic"
	const val DNS_MODE_HOSTNAME = "hostname"
	const val DNS_MODE_OFF = "off"

	// START_LEGACY_MIGRATION_CODE: Old shared preference keys
	const val PREF_SSID_BLACKLIST = "ssid_blacklist"
	const val PREF_SSID_AUTO_DETECTED_BLACKLIST = "ssid_auto_detected_blacklist"
	// END_LEGACY_MIGRATION_CODE

	// Shared Preferences Keys
	const val PREF_NETWORK_PROFILES = "network_profiles"
	const val PREF_AUTO_SAVE_STATE = "pref_auto_save_state"
	const val PREF_AUTO_SAVE_HOST = "pref_auto_save_host"
	const val PREF_HIDE_LAUNCHER_ICON = "hide_launcher_icon"
	const val PREF_DISABLE_DNS_TEST = "disable_dns_test"
	const val PREF_PREFERRED_DNS_MODE = "preferred_dns_mode"
	const val PREF_DYNAMIC_APP_NAME = "dynamic_app_name"
	const val PREF_DNS_HOSTNAMES = "dns_hostnames"
	const val PREF_SHOW_TOAST = "show_toast_notification"
	const val PREF_LAST_USED_HOSTNAME = "last_used_hostname"
	const val PREF_USB_DEBUGGING_TILE_UNLOCKED = "usb_debugging_tile_unlocked"
	const val PREF_ENABLE_STRICT_OFF_OPTION = "enable_strict_off_option"
	const val PREF_DEFAULT_OFF_MODE = "default_off_mode"

	// Connectivity Watchdog Keys
	const val PREF_CONNECTIVITY_WATCHDOG_ENABLED = "connectivity_watchdog_enabled"
	const val PREF_CONNECTIVITY_WATCHDOG_DEBOUNCE_SECONDS = "connectivity_watchdog_debounce_seconds"
	const val PREF_CONNECTIVITY_WATCHDOG_PROBE_TARGETS = "connectivity_watchdog_probe_targets"
	const val CONNECTIVITY_WATCHDOG_DEFAULT_DEBOUNCE_SECONDS = 15
	const val CONNECTIVITY_WATCHDOG_DEFAULT_PROBE_TARGETS =
		"91.198.174.192, 103.102.166.224, 173.239.79.196"

	// VPN Override Keys
	const val PREF_VPN_OVERRIDE_ENABLED = "vpn_override_enabled"
	const val PREF_VPN_DNS_HOSTNAME = "vpn_dns_hostname"
	const val PREF_IS_IN_VPN_OVERRIDE = "is_in_vpn_override"
	const val PREF_ACTIVE_SSID_OVERRIDE = "active_ssid_override"
	const val PREF_VPN_HOSTNAME_REMOVED_WARNING = "vpn_hostname_removed_warning"
	const val PREF_VPN_DNS_MODE = "vpn_dns_mode"

	// Notification Channels
	const val CHANNEL_ID_SERVICE = "wifi_monitoring"
	const val CHANNEL_ID_ALERT = "network_status"

	// Notification IDs
	const val NOTIFICATION_ID_FOREGROUND = 2001
	const val NOTIFICATION_ID_STATUS = 1001

	// Security & Validation
	const val PASSWORD_MIN_LENGTH = 4
	const val BACKUP_ITERATION_COUNT = 65536
	const val BACKUP_KEY_LENGTH = 256
	const val BACKUP_SALT_LENGTH = 16

	// Operational Durations & Debounces
	const val DNS_SETTLE_DELAY_NORMAL_MS = 500L
	const val DNS_SETTLE_DELAY_ROAM_MS = 2000L
	const val DNS_SETTLE_DELAY_FAST_MS = 150L
	const val WATCHDOG_RESTORE_DEBOUNCE_MS = 2000L
	const val TILE_LISTENING_DEBOUNCE_MS = 500L
	const val UI_ARTIFICIAL_DELAY_MS = 5000L
	const val DEV_HIT_RESET_THRESHOLD_MS = 500L
	const val PERMISSION_POLLING_INTERVAL_MS = 2000L

	// Thresholds
	const val USB_DEBUGGING_TILE_THRESHOLD = 5

	// Request Codes
	const val REQUEST_CODE_SHIZUKU_PERMISSION = 12277

	// Security & Encryption
	const val ENCRYPTION_KEY_ALIAS = "dns_toggle_key"
	const val ENCRYPTION_PREFIX = "enc:"
	const val PERMISSION_WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"

	val ROOT_SU_BINARY_PATHS = arrayOf(
		"/system/bin/su",
		"/system/xbin/su",
		"/sbin/su",
		"/system/sd/xbin/su",
		"/system/bin/failsafe/su",
		"/data/local/xbin/su",
		"/data/local/bin/su",
		"/data/local/su",
		"/su/bin/su"
	)

	// Permission Tracking
	const val PREF_HAS_REQUESTED_NOTIF_PERMS = "has_requested_notif_perms"
	fun prefRequestedPermission(permission: String): String = "requested_$permission"
}
