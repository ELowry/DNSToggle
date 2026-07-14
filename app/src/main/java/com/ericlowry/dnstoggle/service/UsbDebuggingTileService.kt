package com.ericlowry.dnstoggle.service

import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.ericlowry.dnstoggle.ui.MainActivity

class UsbDebuggingTileService : TileService() {

	override fun onStartListening() {
		super.onStartListening()
		updateTile()
	}

	override fun onClick() {
		super.onClick()
		val isEnabled = Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) != 0
		val nextState = if (isEnabled) 0 else 1

		try {
			Settings.Global.putInt(contentResolver, Settings.Global.ADB_ENABLED, nextState)
		} catch (_: SecurityException) {
			launchMainActivityWithPermissionPrompt()
		}
		updateTile()
	}

	private fun updateTile() {
		val tile = qsTile ?: return
		val isEnabled = Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) != 0

		tile.state = if (isEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
		tile.updateTile()
	}

	private fun launchMainActivityWithPermissionPrompt() {
		val intent = Intent(this, MainActivity::class.java).apply {
			flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
			putExtra("show_permission_dialog", true)
		}

		TileServiceCompat.startActivityAndCollapse(this, intent)
	}
}
