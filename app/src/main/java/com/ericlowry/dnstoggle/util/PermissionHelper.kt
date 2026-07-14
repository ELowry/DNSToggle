package com.ericlowry.dnstoggle.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object PermissionHelper {

	fun hasSecureSettingsPermission(context: Context): Boolean {
		return ContextCompat.checkSelfPermission(
			context,
			Manifest.permission.WRITE_SECURE_SETTINGS
		) == PackageManager.PERMISSION_GRANTED
	}

	fun hasSsidPermissions(context: Context): Boolean {
		val requiredPermissions = mutableListOf<String>()
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			requiredPermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
			requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
		} else {
			requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
			requiredPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
		}

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			requiredPermissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
		}

		return requiredPermissions.all { permission ->
			ContextCompat.checkSelfPermission(
				context,
				permission
			) == PackageManager.PERMISSION_GRANTED
		}
	}

	fun hasNotificationPermission(context: Context): Boolean {
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			ContextCompat.checkSelfPermission(
				context,
				Manifest.permission.POST_NOTIFICATIONS
			) == PackageManager.PERMISSION_GRANTED
		} else {
			true
		}
	}

	fun getForegroundSsidPermissions(): Array<String> {
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			arrayOf(
				Manifest.permission.NEARBY_WIFI_DEVICES,
				Manifest.permission.ACCESS_FINE_LOCATION
			)
		} else {
			arrayOf(
				Manifest.permission.ACCESS_FINE_LOCATION,
				Manifest.permission.ACCESS_COARSE_LOCATION
			)
		}
	}
}