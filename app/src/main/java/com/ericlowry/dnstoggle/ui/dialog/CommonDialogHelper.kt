package com.ericlowry.dnstoggle.ui.dialog

import android.app.Activity
import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.ericlowry.dnstoggle.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Static helper for building generic Material dialogs used across the app.
 */
object CommonDialogHelper {

	/**
	 * Shows a standard deletion confirmation dialog.
	 */
	fun showDeleteConfirmation(context: Context, messageResId: Int, onConfirm: () -> Unit) {
		MaterialAlertDialogBuilder(context)
			.setMessage(context.getString(messageResId))
			.setPositiveButton(context.getString(R.string.ok)) { _, _ ->
				onConfirm()
			}
			.setNegativeButton(context.getString(R.string.cancel), null)
			.show()
	}

	/**
	 * Shows a dialog with a single text input field.
	 */
	fun showTextInputDialog(
		activity: Activity,
		titleResId: Int,
		hintResId: Int,
		initialValue: String,
		onSave: (String) -> Unit,
	) {
		val dialogView =
			LayoutInflater.from(activity).inflate(
				R.layout.dialog_text_input,
				activity.findViewById(android.R.id.content),
				false
			)
		val textInputLayout = dialogView.findViewById<TextInputLayout>(R.id.textInputLayout)
		val inputTextField = dialogView.findViewById<TextInputEditText>(R.id.etInput)

		textInputLayout.hint = activity.getString(hintResId)
		inputTextField.setText(initialValue)
		inputTextField.setSelection(initialValue.length)

		val dialog = MaterialAlertDialogBuilder(activity)
			.setTitle(activity.getString(titleResId))
			.setView(dialogView)
			.setPositiveButton(activity.getString(R.string.ok)) { _, _ ->
				onSave(inputTextField.text.toString().trim())
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

	/**
	 * Shows a dialog with a numeric input field and range validation.
	 */
	fun showNumberInputDialog(
		activity: Activity,
		titleResId: Int,
		hintResId: Int,
		initialValue: Int,
		minValue: Int,
		maxValue: Int,
		invalidRangeMessageResId: Int,
		onSave: (Int) -> Unit,
	) {
		val dialogView =
			LayoutInflater.from(activity).inflate(
				R.layout.dialog_text_input,
				activity.findViewById(android.R.id.content),
				false
			)
		val textInputLayout = dialogView.findViewById<TextInputLayout>(R.id.textInputLayout)
		val inputTextField = dialogView.findViewById<TextInputEditText>(R.id.etInput)

		textInputLayout.hint = activity.getString(hintResId)
		inputTextField.inputType = InputType.TYPE_CLASS_NUMBER
		inputTextField.setText(initialValue.toString())
		inputTextField.setSelection(inputTextField.text?.length ?: 0)

		val dialog = MaterialAlertDialogBuilder(activity)
			.setTitle(titleResId)
			.setView(dialogView)
			.setPositiveButton(activity.getString(R.string.ok), null)
			.setNegativeButton(activity.getString(R.string.cancel), null)
			.create()

		dialog.setOnShowListener {
			dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
				val value = inputTextField.text.toString().trim().toIntOrNull()
				if (value == null || value < minValue || value > maxValue) {
					android.widget.Toast.makeText(
						activity,
						invalidRangeMessageResId,
						android.widget.Toast.LENGTH_SHORT
					).show()
				} else {
					onSave(value)
					dialog.dismiss()
				}
			}
			inputTextField.requestFocus()
			dialog.window?.let { window ->
				WindowCompat.getInsetsController(window, inputTextField)
					.show(WindowInsetsCompat.Type.ime())
			}
		}
		dialog.show()
	}

	/**
	 * Shows an alert informing the user that encryption keys have been invalidated (e.g. by new biometric entry).
	 */
	fun showKeyInvalidatedDialog(context: Context, onDismiss: () -> Unit) {
		MaterialAlertDialogBuilder(context)
			.setTitle(R.string.keystore_error_title)
			.setMessage(R.string.keystore_error_message)
			.setPositiveButton(R.string.ok) { _, _ ->
				onDismiss()
			}
			.setCancelable(false)
			.show()
	}
}
