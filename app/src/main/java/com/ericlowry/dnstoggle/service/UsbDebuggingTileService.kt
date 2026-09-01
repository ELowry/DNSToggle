package com.ericlowry.dnstoggle.service

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.ericlowry.dnstoggle.R
import com.ericlowry.dnstoggle.ui.MainActivity

class UsbDebuggingTileService : TileService() {

	companion object {
		private const val TAG = "UsbDebuggingTile"
	}

	override fun onTileAdded() {
		super.onTileAdded()
		updateTile()
	}

	override fun onStartListening() {
		super.onStartListening()
		updateTile()
	}

	override fun onClick() {
		super.onClick()

		val isEnabled = getAdbEnabledState()
		val nextState = if (isEnabled) {
			0
		} else {
			1
		}

		Log.i(TAG, "Toggling ADB: $isEnabled -> ${nextState != 0}")

		try {
			val success =
				Settings.Global.putInt(contentResolver, Settings.Global.ADB_ENABLED, nextState)
			if (!success) {
				Log.e(TAG, "Failed to update ADB_ENABLED setting")
			}
		} catch (e: SecurityException) {
			Log.e(TAG, "Permission denied toggling ADB: ${e.message}")
			launchMainActivityWithPermissionPrompt()
		}

		updateTile()
	}

	private fun updateTile() {
		val tile = qsTile ?: return
		val isEnabled = getAdbEnabledState()

		tile.state = if (isEnabled) {
			Tile.STATE_ACTIVE
		} else {
			Tile.STATE_INACTIVE
		}
		tile.icon = Icon.createWithResource(this, R.drawable.ic_tool)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			tile.subtitle = null
		}
		tile.updateTile()
	}

	private fun getAdbEnabledState(): Boolean {
		return try {
			Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) != 0
		} catch (e: SecurityException) {
			Log.w(TAG, "SecurityException reading ADB_ENABLED: ${e.message}")
			false
		}
	}

	private fun launchMainActivityWithPermissionPrompt() {
		val intent = Intent(this, MainActivity::class.java).apply {
			flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
			putExtra("show_permission_dialog", true)
		}

		TileServiceCompat.startActivityAndCollapse(this, intent)
	}
}
