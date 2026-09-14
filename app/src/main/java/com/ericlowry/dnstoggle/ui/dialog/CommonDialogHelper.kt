package com.ericlowry.dnstoggle.ui.dialog

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.ericlowry.dnstoggle.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.listitem.ListItemCardView
import com.google.android.material.listitem.ListItemLayout
import com.google.android.material.radiobutton.MaterialRadioButton
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

	/**
	 * Shows an expressive selection dialog with card-style items.
	 */
	fun showExpressiveSelectionDialog(
		activity: Activity,
		title: String,
		options: List<String>,
		selectedIndices: Set<Int>,
		onItemSelected: (Int) -> Unit
	) {
		val dialogView = LayoutInflater.from(activity).inflate(
			R.layout.dialog_dns_selection,
			activity.findViewById(android.R.id.content),
			false
		)

		val dialog = Dialog(activity)
		dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
		dialog.setContentView(dialogView)

		dialogView.findViewById<TextView>(R.id.tvPopupTitle).text = title
		dialogView.findViewById<TextView>(R.id.tvSsidContext).visibility = View.GONE

		val listContainer = dialogView.findViewById<LinearLayout>(R.id.dnsListContainer)
		val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnSettings)
		btnCancel.text = activity.getString(R.string.cancel)
		btnCancel.setOnClickListener { dialog.dismiss() }

		options.forEachIndexed { index, optionText ->
			val itemView = LayoutInflater.from(activity).inflate(
				R.layout.item_dns_selection,
				listContainer,
				false
			)
			val listItemLayout = itemView as ListItemLayout
			val cardView = itemView.findViewById<ListItemCardView>(R.id.listItemCard)
			val radio = itemView.findViewById<MaterialRadioButton>(R.id.radioDns)

			itemView.findViewById<TextView>(R.id.tvHostname).text = optionText
			itemView.findViewById<TextView>(R.id.tvSecondaryHostname).visibility = View.GONE
			itemView.findViewById<TextView>(R.id.tvOverrideBadge).visibility = View.GONE

			val isSelected = selectedIndices.contains(index)
			cardView.isChecked = isSelected
			radio.isChecked = isSelected

			listItemLayout.updateAppearance(index, options.size)
			cardView.setOnClickListener {
				onItemSelected(index)
				dialog.dismiss()
			}
			listContainer.addView(itemView)
		}

		dialog.show()
	}
}
