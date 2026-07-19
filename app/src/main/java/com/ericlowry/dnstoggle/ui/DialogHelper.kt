package com.ericlowry.dnstoggle.ui

import android.app.Activity
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.ericlowry.dnstoggle.R
import com.ericlowry.dnstoggle.util.RootUtils
import com.ericlowry.dnstoggle.util.ShizukuUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

object DialogHelper {

	fun showAddHostnameDialog(
		activity: Activity,
		existingHostname: String?,
		existingLabel: String?,
		onSave: (hostname: String, label: String?) -> Unit,
	) {
		val dialogView =
			LayoutInflater.from(activity).inflate(R.layout.dialog_add_hostname, null, false)
		val textInputLayoutHostname =
			dialogView.findViewById<TextInputLayout>(R.id.textInputLayoutHostname)
		val etHostname = dialogView.findViewById<TextInputEditText>(R.id.etHostname)
		val textInputLayoutLabel =
			dialogView.findViewById<TextInputLayout>(R.id.textInputLayoutLabel)
		val etLabel = dialogView.findViewById<TextInputEditText>(R.id.etLabel)

		textInputLayoutHostname.hint = activity.getString(R.string.dns_hostname_hint)
		textInputLayoutLabel.hint = activity.getString(R.string.dns_label_hint)

		if (existingHostname != null) {
			etHostname.setText(existingHostname)
			etHostname.setSelection(existingHostname.length)
			existingLabel?.let { etLabel.setText(it) }
		}

		val dialog = MaterialAlertDialogBuilder(activity)
			.setTitle(
				if (existingHostname == null) activity.getString(R.string.add_hostname) else activity.getString(
					R.string.edit_hostname,
				)
			)
			.setView(dialogView)
			.setPositiveButton(activity.getString(R.string.ok)) { _, _ ->
				val newHostname = etHostname.text.toString().trim()
				val newLabel = etLabel.text.toString().trim().takeIf { it.isNotEmpty() }
				onSave(newHostname, newLabel)
			}
			.setNegativeButton(activity.getString(R.string.cancel), null)
			.create()

		dialog.setOnShowListener {
			etHostname.requestFocus()
			dialog.window?.let { window ->
				WindowCompat.getInsetsController(window, etHostname)
					.show(WindowInsetsCompat.Type.ime())
			}
		}

		dialog.show()
	}

	fun showDeleteConfirmation(context: Context, messageResId: Int, onConfirm: () -> Unit) {
		MaterialAlertDialogBuilder(context)
			.setMessage(context.getString(messageResId))
			.setPositiveButton(context.getString(R.string.ok)) { _, _ -> onConfirm() }
			.setNegativeButton(context.getString(R.string.cancel), null)
			.show()
	}

	fun showAddSsidDialog(
		activity: Activity,
		existingSsid: String?,
		suggestedSsid: String?,
		onSave: (String) -> Unit,
	) {
		val dialogView =
			LayoutInflater.from(activity).inflate(R.layout.dialog_text_input, null, false)
		val textInputLayout = dialogView.findViewById<TextInputLayout>(R.id.textInputLayout)
		val inputTextField = dialogView.findViewById<TextInputEditText>(R.id.etInput)

		textInputLayout.hint = activity.getString(R.string.ssid_hint)

		val textToSet = existingSsid ?: suggestedSsid
		if (textToSet != null) {
			inputTextField.setText(textToSet)
			inputTextField.setSelection(textToSet.length)
		}

		val dialog = MaterialAlertDialogBuilder(activity)
			.setTitle(
				if (existingSsid == null) activity.getString(R.string.add_ssid) else activity.getString(
					R.string.edit_ssid,
				)
			)
			.setView(dialogView)
			.setPositiveButton(activity.getString(R.string.ok)) { _, _ ->
				val newSsidName =
					inputTextField.text.toString().trim().removePrefix("\"").removeSuffix("\"")
				if (newSsidName.isNotEmpty()) onSave(newSsidName)
			}
			.setNegativeButton(activity.getString(R.string.cancel), null)
			.create()

		dialog.setOnShowListener {
			inputTextField.requestFocus()
			dialog.window?.let { window ->
				WindowCompat.getInsetsController(window, inputTextField)
					.show(WindowInsetsCompat.Type.ime())
			}
		}

		dialog.show()
	}

	fun showRenameAppDialog(
		activity: Activity,
		currentAppName: String?,
		onSave: (String) -> Unit
	) {
		val dialogView =
			LayoutInflater.from(activity).inflate(R.layout.dialog_text_input, null, false)
		val textInputLayout = dialogView.findViewById<TextInputLayout>(R.id.textInputLayout)
		val inputTextField = dialogView.findViewById<TextInputEditText>(R.id.etInput)

		textInputLayout.hint = activity.getString(R.string.app_name)

		if (currentAppName != null) {
			inputTextField.setText(currentAppName)
			inputTextField.setSelection(currentAppName.length)
		}

		val dialog = MaterialAlertDialogBuilder(activity)
			.setTitle(activity.getString(R.string.rename_app))
			.setMessage(activity.getString(R.string.rename_app_message))
			.setView(dialogView)
			.setPositiveButton(activity.getString(R.string.ok)) { d, _ ->
				val newAppName = inputTextField.text.toString().trim()
				if (newAppName.isNotEmpty()) onSave(newAppName)
				d.dismiss()
			}
			.setNegativeButton(activity.getString(R.string.cancel)) { d, _ -> d.cancel() }
			.create()

		dialog.setOnShowListener {
			inputTextField.requestFocus()
			dialog.window?.let { window ->
				WindowCompat.getInsetsController(window, inputTextField)
					.show(WindowInsetsCompat.Type.ime())
			}
		}

		dialog.show()
	}

	fun showWifiMonitoringInfo(
		context: Context,
		isIgnoringBattery: Boolean,
		onRequestIgnoreBattery: () -> Unit
	) {
		val message = StringBuilder(context.getString(R.string.wifi_monitoring_info_text))
		message.append(context.getString(R.string.vpn_disclaimer_explanation))

		if (!isIgnoringBattery) {
			message.append("\n\n")
				.append(context.getString(R.string.battery_optimization_explanation))
		}

		val builder = MaterialAlertDialogBuilder(context)
			.setTitle(context.getString(R.string.wifi_monitoring_info_title))
			.setMessage(message.toString())
			.setPositiveButton(context.getString(R.string.ok), null)

		if (!isIgnoringBattery) {
			builder.setNeutralButton(context.getString(R.string.ignore_battery_optimizations)) { _, _ ->
				onRequestIgnoreBattery()
			}
		}
		builder.show()
	}

	fun showKeyInvalidatedDialog(context: Context, onDismiss: () -> Unit) {
		MaterialAlertDialogBuilder(context)
			.setTitle(R.string.keystore_error_title)
			.setMessage(R.string.keystore_error_message)
			.setPositiveButton(R.string.ok) { _, _ -> onDismiss() }
			.setCancelable(false)
			.show()
	}

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
		onAttemptElevatedGrant: () -> Unit
	): AlertDialog {
		val adbCommand = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"

		val dialogView =
			LayoutInflater.from(context).inflate(R.layout.dialog_permission_secure_settings, null)
		val tvAdbCommand = dialogView.findViewById<TextView>(R.id.tvAdbCommand)
		val btnCopy = dialogView.findViewById<ImageButton>(R.id.btnCopyAdbCommand)

		tvAdbCommand.text = adbCommand
		tvAdbCommand.contentDescription =
			context.getString(R.string.adb_command_content_description, adbCommand)
		btnCopy.setOnClickListener { onCopyCommand(adbCommand) }

		val builder = MaterialAlertDialogBuilder(context)
			.setTitle(context.getString(R.string.permission_required))
			.setView(dialogView)
			.setNegativeButton(context.getString(R.string.ok), null)

		val shizukuAvailable = ShizukuUtils.isAvailable()
		val rootAvailable = RootUtils.isAvailable()

		val btnTextRes = when {
			shizukuAvailable -> R.string.grant_via_shizuku
			rootAvailable -> R.string.grant_via_root
			else -> R.string.grant_auto_fallback
		}
		builder.setPositiveButton(btnTextRes) { _, _ -> onAttemptElevatedGrant() }

		return builder.show()
	}

	fun showPasswordDialog(
		activity: Activity,
		titleResId: Int,
		actionResId: Int,
		messageResId: Int? = null,
		onPasswordEntered: (CharArray) -> Unit,
	) {
		val dialogView =
			LayoutInflater.from(activity).inflate(R.layout.dialog_text_input, null, false)
		val textInputLayout = dialogView.findViewById<TextInputLayout>(R.id.textInputLayout)
		val inputTextField = dialogView.findViewById<TextInputEditText>(R.id.etInput)

		textInputLayout.hint = activity.getString(R.string.password)
		inputTextField.inputType =
			InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

		val builder = MaterialAlertDialogBuilder(activity)
			.setTitle(titleResId)
			.setView(dialogView)
			.setPositiveButton(actionResId) { _, _ ->
				val password = inputTextField.text?.toString()?.toCharArray() ?: CharArray(0)
				onPasswordEntered(password)
			}
			.setNegativeButton(R.string.cancel, null)

		messageResId?.let { builder.setMessage(it) }

		val dialog = builder.create()

		dialog.setOnShowListener {
			val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
			positiveButton.isEnabled = !inputTextField.text.isNullOrEmpty()

			inputTextField.addTextChangedListener(
				object : TextWatcher {
					override fun beforeTextChanged(
						s: CharSequence?,
						start: Int,
						count: Int,
						after: Int,
					) {
					}

					override fun onTextChanged(
						s: CharSequence?,
						start: Int,
						before: Int,
						count: Int
					) {
						positiveButton.isEnabled = !s.isNullOrEmpty()
					}

					override fun afterTextChanged(s: Editable?) {}
				},
			)

			inputTextField.requestFocus()
			dialog.window?.let { window ->
				WindowCompat.getInsetsController(window, inputTextField)
					.show(WindowInsetsCompat.Type.ime())
			}
		}

		dialog.show()
	}
}
