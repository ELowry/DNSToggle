package com.ericlowry.dnstoggle.ui.dialog

import android.app.Activity
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.ericlowry.dnstoggle.R
import com.ericlowry.dnstoggle.data.Constants
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

object BackupDialogHelper {

	fun showPasswordDialog(
		activity: Activity,
		titleResId: Int,
		actionResId: Int,
		messageResId: Int? = null,
		onCancel: (() -> Unit)? = null,
		onPasswordEntered: (CharArray) -> Unit,
	) {
		val dialogView =
			LayoutInflater.from(activity).inflate(
				R.layout.dialog_text_input,
				activity.findViewById(android.R.id.content),
				false
			)
		val textInputLayout = dialogView.findViewById<TextInputLayout>(R.id.textInputLayout)
		val inputTextField = dialogView.findViewById<TextInputEditText>(R.id.etInput)

		textInputLayout.hint = activity.getString(R.string.password)
		inputTextField.inputType =
			InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

		val builder = MaterialAlertDialogBuilder(activity)
			.setTitle(titleResId)
			.setView(dialogView)
			.setPositiveButton(actionResId) { _, _ ->
				val editable = inputTextField.text
				val password = if (!editable.isNullOrEmpty()) {
					val chars = CharArray(editable.length)
					for (i in chars.indices) {
						chars[i] = editable[i]
					}
					chars
				} else {
					CharArray(0)
				}
				onPasswordEntered(password)
			}
			.setNegativeButton(R.string.cancel) { _, _ -> onCancel?.invoke() }

		messageResId?.let { builder.setMessage(it) }

		val dialog = builder.create()

		dialog.setOnCancelListener { onCancel?.invoke() }

		dialog.setOnShowListener {
			val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
			val currentLength = inputTextField.text?.length ?: 0
			positiveButton.isEnabled = currentLength >= Constants.PASSWORD_MIN_LENGTH

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
						val len = s?.length ?: 0
						if (len in 1 until Constants.PASSWORD_MIN_LENGTH) {
							textInputLayout.error =
								activity.getString(R.string.error_password_length)
							positiveButton.isEnabled = false
						} else {
							textInputLayout.error = null
							positiveButton.isEnabled = len >= Constants.PASSWORD_MIN_LENGTH
						}
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

	fun showImportConfirmationDialog(context: Context, onConfirm: () -> Unit) {
		MaterialAlertDialogBuilder(context)
			.setTitle(R.string.import_config)
			.setMessage(R.string.import_confirmation_message)
			.setPositiveButton(R.string.import_action) { _, _ -> onConfirm() }
			.setNegativeButton(R.string.cancel, null)
			.show()
	}

	fun showRenameAppDialog(
		activity: Activity,
		currentAppName: String?,
		onSave: (String) -> Unit
	) {
		val dialogView =
			LayoutInflater.from(activity).inflate(
				R.layout.dialog_text_input,
				activity.findViewById(android.R.id.content),
				false
			)
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
}
