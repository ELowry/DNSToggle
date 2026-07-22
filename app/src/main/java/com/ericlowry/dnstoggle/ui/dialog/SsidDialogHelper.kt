package com.ericlowry.dnstoggle.ui.dialog

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.ericlowry.dnstoggle.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

object SsidDialogHelper {

	fun showAddSsidDialog(
		activity: Activity,
		existingSsid: String?,
		suggestedSsid: String?,
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

	fun showConnectivityWatchdogDebounceDialog(
		activity: Activity,
		currentValue: Int,
		presets: List<Int>,
		onValueSelected: (Int) -> Unit
	) {
		val options = presets.map { "${it}s" }.toMutableList()
		options.add(activity.getString(R.string.connectivity_watchdog_debounce_custom))

		val checkedItem =
			if (presets.contains(currentValue)) presets.indexOf(currentValue) else options.lastIndex

		MaterialAlertDialogBuilder(activity)
			.setTitle(R.string.connectivity_watchdog_debounce_label)
			.setSingleChoiceItems(options.toTypedArray(), checkedItem) { dialog, which ->
				if (which < presets.size) {
					onValueSelected(presets[which])
					dialog.dismiss()
				} else {
					dialog.dismiss()
					CommonDialogHelper.showNumberInputDialog(
						activity,
						R.string.connectivity_watchdog_debounce_label,
						R.string.connectivity_watchdog_debounce_label,
						currentValue,
						5,
						300,
						R.string.connectivity_watchdog_debounce_invalid,
						onValueSelected
					)
				}
			}
			.setNegativeButton(R.string.cancel, null)
			.show()
	}
}
