package com.ericlowry.dnstoggle.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.ericlowry.dnstoggle.DnsToggleApplication
import com.ericlowry.dnstoggle.data.Constants
import com.ericlowry.dnstoggle.ui.dialog.PermissionDialogHelper
import com.ericlowry.dnstoggle.util.PermissionHelper

class MainPermissionHandler(
	private val activity: ComponentActivity,
	private val onSsidPermissionChanged: (Boolean) -> Unit,
	private val onNotificationPermissionChanged: (Boolean) -> Unit,
	private val onRegistrationUpdateRequired: () -> Unit
) {

	private val foregroundPermissionLauncher = activity.registerForActivityResult(
		ActivityResultContracts.RequestMultiplePermissions(),
	) { results ->
		val allGranted = results.entries.all { it.value }
		if (allGranted && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)) {
			showBackgroundLocationRationale()
		} else {
			onSsidPermissionChanged(allGranted)
			onRegistrationUpdateRequired()
			if (allGranted) {
				checkNotificationPermission()
			}
		}
	}

	private val backgroundPermissionLauncher = activity.registerForActivityResult(
		ActivityResultContracts.RequestPermission(),
	) { _ ->
		checkSsidPermissions(requestIfNotGranted = false)
		onRegistrationUpdateRequired()
	}

	private val notificationPermissionLauncher = activity.registerForActivityResult(
		ActivityResultContracts.RequestPermission()
	) { _ ->
		onNotificationPermissionChanged(PermissionHelper.hasNotificationPermission(activity))
		onRegistrationUpdateRequired()
	}

	fun checkSsidPermissions(requestIfNotGranted: Boolean = false) {
		val allGranted = PermissionHelper.hasSsidPermissions(activity)
		onSsidPermissionChanged(allGranted)
		if (!allGranted && requestIfNotGranted) {
			requestSsidPermissions()
		} else if (allGranted) {
			checkNotificationPermission()
		}
	}

	fun requestSsidPermissions() {
		val permissions = PermissionHelper.getForegroundSsidPermissions()
		val prefs = (activity.application as DnsToggleApplication).getPrefs()

		val isPermanentlyDenied = permissions.any { perm ->
			val hasRequestedThisPerm =
				prefs.getBoolean(Constants.prefRequestedPermission(perm), false)
			hasRequestedThisPerm && ContextCompat.checkSelfPermission(
				activity,
				perm
			) != PackageManager.PERMISSION_GRANTED &&
					!activity.shouldShowRequestPermissionRationale(perm)
		}

		if (isPermanentlyDenied) {
			PermissionDialogHelper.showPermissionDeniedDialog(activity) { openAppSettings() }
			return
		}

		prefs.edit {
			permissions.forEach { perm ->
				putBoolean(Constants.prefRequestedPermission(perm), true)
			}
		}
		foregroundPermissionLauncher.launch(permissions)
	}

	private fun showBackgroundLocationRationale() {
		PermissionDialogHelper.showBackgroundLocationRationale(
			context = activity,
			onAccept = {
				requestBackgroundLocationPermission()
			},
			onDecline = {
				checkSsidPermissions(requestIfNotGranted = false)
				onRegistrationUpdateRequired()
				checkNotificationPermission()
			}
		)
	}

	private fun requestBackgroundLocationPermission() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
		}
	}

	fun checkNotificationPermission() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			if (!PermissionHelper.hasNotificationPermission(activity)) {
				val prefs = (activity.application as DnsToggleApplication).getPrefs()
				val hasRequestedBefore =
					prefs.getBoolean(Constants.PREF_HAS_REQUESTED_NOTIF_PERMS, false)

				if (hasRequestedBefore && !activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
					PermissionDialogHelper.showPermissionDeniedDialog(activity) { openAppSettings() }
				} else if (activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
					PermissionDialogHelper.showNotificationPermissionRationale(activity) {
						prefs.edit { putBoolean(Constants.PREF_HAS_REQUESTED_NOTIF_PERMS, true) }
						notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
					}
				} else {
					prefs.edit { putBoolean(Constants.PREF_HAS_REQUESTED_NOTIF_PERMS, true) }
					notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
				}
			}
		}
	}

	private fun openAppSettings() {
		val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
			data = Uri.fromParts("package", activity.packageName, null)
		}
		activity.startActivity(intent)
	}
}
