package com.ericlowry.dnstoggle.ui.dialog

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.ericlowry.dnstoggle.R
import com.ericlowry.dnstoggle.util.RootUtils
import com.ericlowry.dnstoggle.util.ShizukuUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object PermissionDialogHelper {

	fun showPermissionDeniedDialog(context: Context, onOpenSettings: () -> Unit) {
		MaterialAlertDialogBuilder(context)
			.setTitle(context.getString(R.string.permission_required))
			.setMessage(context.getString(R.string.permissions_permanently_denied))
			.setPositiveButton(context.getString(R.string.open_settings)) { _, _ -> onOpenSettings() }
			.setNegativeButton(context.getString(R.string.cancel), null)
			.show()
	}

	fun showBackgroundLocationRationale(
		context: Context,
		onAccept: () -> Unit,
		onDecline: () -> Unit
	) {
		MaterialAlertDialogBuilder(context)
			.setTitle(context.getString(R.string.permission_required))
			.setMessage(context.getString(R.string.background_location_explanation))
			.setPositiveButton(context.getString(R.string.ok)) { _, _ -> onAccept() }
			.setNegativeButton(context.getString(R.string.cancel)) { _, _ -> onDecline() }
			.show()
	}

	fun showNotificationPermissionRationale(context: Context, onAccept: () -> Unit) {
		MaterialAlertDialogBuilder(context)
			.setTitle(context.getString(R.string.permission_required))
			.setMessage(context.getString(R.string.notification_permission_explanation))
			.setPositiveButton(context.getString(R.string.ok)) { _, _ -> onAccept() }
			.setNegativeButton(context.getString(R.string.cancel), null)
			.setCancelable(false)
			.show()
	}

	fun showSecureSettingsPermissionDialog(
		context: Context,
		packageName: String,
		onCopyCommand: (String) -> Unit,
		onAttemptElevatedGrant: (AlertDialog, View) -> Unit
	): AlertDialog {
		val adbCommand = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"

		val dialogView =
			LayoutInflater.from(context).inflate(
				R.layout.dialog_permission_secure_settings,
				(context as? Activity)?.findViewById(android.R.id.content),
				false
			)
		val tvAdbCommand = dialogView.findViewById<TextView>(R.id.tvAdbCommand)
		val btnCopy = dialogView.findViewById<ImageButton>(R.id.btnCopyAdbCommand)

		tvAdbCommand.text = adbCommand
		tvAdbCommand.contentDescription =
			context.getString(R.string.adb_command_content_description, adbCommand)
		btnCopy.setOnClickListener { onCopyCommand(adbCommand) }

		val builder = MaterialAlertDialogBuilder(context)
			.setTitle(context.getString(R.string.permission_required))
			.setView(dialogView)
			.setNegativeButton(context.getString(R.string.close), null)

		val shizukuAvailable = ShizukuUtils.isAvailable()
		val rootAvailable = RootUtils.isAvailable()

		val btnTextRes = when {
			shizukuAvailable -> {
				R.string.grant_via_shizuku
			}

			rootAvailable -> {
				R.string.grant_via_root
			}

			else -> {
				R.string.grant_auto_fallback
			}
		}
		builder.setPositiveButton(btnTextRes, null) // Set null to override later

		val dialog = builder.create()
		dialog.show()

		dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
			onAttemptElevatedGrant(dialog, dialogView)
		}

		return dialog
	}
}
