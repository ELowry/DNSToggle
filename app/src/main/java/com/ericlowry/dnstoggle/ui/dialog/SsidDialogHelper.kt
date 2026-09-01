package com.ericlowry.dnstoggle.ui.dialog

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.ericlowry.dnstoggle.R
import com.ericlowry.dnstoggle.data.DnsHostname
import com.ericlowry.dnstoggle.data.NetworkProfile
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.listitem.ListItemCardView
import com.google.android.material.listitem.ListItemLayout
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Static helper for building SSID (Network Profile) related Material dialogs.
 */
object SsidDialogHelper {

	private data class ProfileOption(
		val isEnabled: Boolean,
		val hostname: String?,
		val mode: String? = null
	)

	/**
	 * Shows a dialog to add or edit a network-specific DNS profile.
	 */
	fun showAddSsidDialog(
		activity: Activity,
		existingProfile: NetworkProfile?,
		suggestedSsid: String?,
		globalDefaultHostname: String?,
		hostnames: List<DnsHostname>,
		enableStrictOff: Boolean,
		defaultOffMode: String,
		onSave: (ssid: String, isEnabled: Boolean, targetHostname: String?, targetMode: String?) -> Unit,
	) {
		val dialogView =
			LayoutInflater.from(activity).inflate(
				R.layout.dialog_text_input,
				activity.findViewById(android.R.id.content),
				false
			)
		val textInputLayout = dialogView.findViewById<TextInputLayout>(R.id.textInputLayout)
		val inputTextField = dialogView.findViewById<TextInputEditText>(R.id.etInput)
		val mainContainer = textInputLayout.parent as LinearLayout

		val radioContainer = LinearLayout(activity).apply {
			layoutParams = LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT
			)
			orientation = LinearLayout.VERTICAL
			setPadding(0, 16, 0, 0)
		}

		var selectedEnabled = existingProfile?.isEnabled ?: true
		var selectedHostname = existingProfile?.targetHostname
		var selectedMode = existingProfile?.targetMode

		var isUpdatingProgrammatically = false

		fun updateSelectionVisuals() {
			for (i in 0 until radioContainer.childCount) {
				val itemView = radioContainer.getChildAt(i)
				val card = itemView.findViewById<ListItemCardView>(R.id.listItemCard)
				val radio = itemView.findViewById<MaterialRadioButton>(R.id.radioDns)

				val option = itemView.tag as? ProfileOption
				val isSelected = if (option?.mode != null) {
					option.mode == selectedMode
				} else {
					selectedMode == null && option?.hostname == selectedHostname
				}

				val bgColorAttr = if (isSelected) {
					com.google.android.material.R.attr.colorSecondaryContainer
				} else {
					com.google.android.material.R.attr.colorSurfaceContainer
				}
				card.setCardBackgroundColor(MaterialColors.getColor(activity, bgColorAttr, 0))

				card.isChecked = isSelected
				radio.isChecked = isSelected
			}
		}

		val switchRow = LinearLayout(activity).apply {
			layoutParams = LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT
			)
			orientation = LinearLayout.HORIZONTAL
			gravity = android.view.Gravity.CENTER_VERTICAL
			setPadding(0, 16, 0, 8)

			val textView = TextView(activity).apply {
				layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
				text = activity.getString(R.string.enable_private_dns)
				setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
			}
			val materialSwitch = MaterialSwitch(activity).apply {
				id = View.generateViewId()
				isChecked = selectedEnabled
			}

			addView(textView)
			addView(materialSwitch)

			setOnClickListener {
				materialSwitch.toggle()
			}
			materialSwitch.setOnCheckedChangeListener { _, isChecked ->
				if (isUpdatingProgrammatically) {
					return@setOnCheckedChangeListener
				}
				selectedEnabled = isChecked
				selectedMode = if (isChecked) {
					null
				} else {
					defaultOffMode
				}
				updateSelectionVisuals()
			}
		}

		val materialSwitch = switchRow.getChildAt(1) as MaterialSwitch

		mainContainer.addView(switchRow)

		val headerView = TextView(activity).apply {
			layoutParams = LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT
			).apply {
				setMargins(
					0,
					(16 * activity.resources.displayMetrics.density).toInt(),
					0,
					(8 * activity.resources.displayMetrics.density).toInt()
				)
			}
			text = activity.getString(R.string.profile_dns_section_header)
			setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
			setTextColor(
				MaterialColors.getColor(
					this,
					com.google.android.material.R.attr.colorOnSurfaceVariant
				)
			)
		}
		mainContainer.addView(headerView)

		mainContainer.addView(radioContainer)

		textInputLayout.hint = activity.getString(R.string.ssid_hint)

		val textToSet = existingProfile?.ssid ?: suggestedSsid
		if (textToSet != null) {
			inputTextField.setText(textToSet)
			inputTextField.setSelection(textToSet.length)
		}

		fun createRadioItem(
			text: String,
			hostname: String?,
			position: Int,
			total: Int,
			mode: String? = null
		) {
			val itemView = LayoutInflater.from(activity)
				.inflate(R.layout.item_dns_selection, radioContainer, false)
			val listItemLayout = itemView as ListItemLayout
			val card = itemView.findViewById<ListItemCardView>(R.id.listItemCard)
			val tvMain = itemView.findViewById<TextView>(R.id.tvHostname)
			val tvSub = itemView.findViewById<TextView>(R.id.tvSecondaryHostname)

			tvMain.text = text
			if (hostname != null && hostname != text) {
				tvSub.text = hostname
				tvSub.visibility = View.VISIBLE
			} else {
				tvSub.visibility = View.GONE
			}

			itemView.tag = ProfileOption(true, hostname, mode)
			listItemLayout.updateAppearance(position, total)

			card.setCardBackgroundColor(
				MaterialColors.getColor(
					activity,
					com.google.android.material.R.attr.colorSurfaceContainer,
					0
				)
			)
			card.strokeColor = MaterialColors.getColor(
				activity,
				com.google.android.material.R.attr.colorOutlineVariant,
				0
			)
			card.isFocusableInTouchMode = false
			card.setOnClickListener {
				selectedHostname = hostname
				selectedMode = mode
				selectedEnabled = (mode == null)

				isUpdatingProgrammatically = true
				materialSwitch.isChecked = selectedEnabled
				isUpdatingProgrammatically = false

				updateSelectionVisuals()
			}

			radioContainer.addView(itemView)
		}

		val strictOffCount = if (enableStrictOff) {
			2
		} else {
			0
		}
		val totalItems = hostnames.size + 1 + strictOffCount
		var currentPos = 0

		val defaultLabel = if (globalDefaultHostname != null) {
			activity.getString(R.string.default_dns_with_host_format, globalDefaultHostname)
		} else {
			activity.getString(R.string.default_dns_label)
		}

		createRadioItem(
			defaultLabel,
			null,
			currentPos++,
			totalItems
		)

		hostnames.forEach { dns ->
			createRadioItem(dns.getDisplayName(), dns.hostname, currentPos++, totalItems)
		}

		if (enableStrictOff) {
			createRadioItem(
				activity.getString(R.string.mode_automatic),
				null,
				currentPos++,
				totalItems,
				com.ericlowry.dnstoggle.data.Constants.DNS_MODE_OPPORTUNISTIC
			)
			createRadioItem(
				activity.getString(R.string.mode_disabled),
				null,
				currentPos,
				totalItems,
				com.ericlowry.dnstoggle.data.Constants.DNS_MODE_OFF
			)
		}

		updateSelectionVisuals()

		val dialog = MaterialAlertDialogBuilder(activity)
			.setTitle(
				if (existingProfile == null) {
					activity.getString(R.string.add_ssid)
				} else {
					activity.getString(
						R.string.edit_ssid,
					)
				}
			)
			.setView(dialogView)
			.setPositiveButton(activity.getString(R.string.ok)) { _, _ ->
				val newSsidName =
					inputTextField.text.toString().trim().removePrefix("\"").removeSuffix("\"")
				if (newSsidName.isNotEmpty()) {
					onSave(
						newSsidName,
						selectedEnabled,
						selectedHostname,
						selectedMode
					)
				}
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
	 * Shows an information dialog explaining how Wi-Fi monitoring and VPN overrides work.
	 */
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

	/**
	 * Shows a dialog to select a debounce duration for the connectivity watchdog.
	 */
	fun showConnectivityWatchdogDebounceDialog(
		activity: Activity,
		currentValue: Int,
		presets: List<Int>,
		onValueSelected: (Int) -> Unit
	) {
		val options = presets.map {
			"${it}s"
		}.toMutableList()
		options.add(activity.getString(R.string.connectivity_watchdog_debounce_custom))

		val checkedItem = if (presets.contains(currentValue)) {
			presets.indexOf(currentValue)
		} else {
			options.lastIndex
		}

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
