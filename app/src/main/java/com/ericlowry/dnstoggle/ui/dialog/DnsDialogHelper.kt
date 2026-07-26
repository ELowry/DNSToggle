package com.ericlowry.dnstoggle.ui.dialog

import android.app.Activity
import android.app.Dialog
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.listitem.ListItemCardView
import com.google.android.material.listitem.ListItemLayout
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText

object DnsDialogHelper {

	fun showAddHostnameDialog(
		activity: Activity,
		existingHostname: String?,
		existingLabel: String?,
		onSave: (hostname: String, label: String?) -> Unit,
	) {
		val dialogView =
			LayoutInflater.from(activity).inflate(
				R.layout.dialog_add_hostname,
				activity.findViewById(android.R.id.content),
				false
			)
		val etHostname = dialogView.findViewById<TextInputEditText>(R.id.etHostname)
		val etLabel = dialogView.findViewById<TextInputEditText>(R.id.etLabel)

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

	fun showVpnDnsSelectionDialog(
		activity: Activity,
		hostnames: List<DnsHostname>,
		currentVpnDns: String?,
		onDnsSelected: (String?) -> Unit
	) {
		val dialogView =
			LayoutInflater.from(activity).inflate(
				R.layout.dialog_dns_selection,
				activity.findViewById(android.R.id.content),
				false
			)

		val dialog = Dialog(activity)
		dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
		dialog.setContentView(dialogView)

		val tvPopupTitle = dialogView.findViewById<TextView>(R.id.tvPopupTitle)
		tvPopupTitle.text = activity.getString(R.string.vpn_dns_label)

		val listContainer = dialogView.findViewById<LinearLayout>(R.id.dnsListContainer)
		val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnSettings)
		btnCancel.text = activity.getString(R.string.cancel)
		btnCancel.setOnClickListener { dialog.dismiss() }

		val totalItems = hostnames.size + 1

		hostnames.forEachIndexed { index, dnsEntry ->
			val hostname = dnsEntry.hostname
			val isActive = (hostname == currentVpnDns)
			val itemView = createDnsListItem(
				activity,
				listContainer,
				dnsEntry.getDisplayName(),
				dnsEntry.label?.let { hostname },
				isActive,
				index,
				totalItems
			) {
				onDnsSelected(hostname)
				dialog.dismiss()
			}
			listContainer.addView(itemView)
		}

		val isAutoActive = (currentVpnDns == null)
		val autoItemView = createDnsListItem(
			activity,
			listContainer,
			activity.getString(R.string.automatic_off),
			null,
			isAutoActive,
			totalItems - 1,
			totalItems
		) {
			onDnsSelected(null)
			dialog.dismiss()
		}
		listContainer.addView(autoItemView)

		dialog.show()
	}

	private fun createDnsListItem(
		context: Context,
		parent: ViewGroup,
		text: String,
		secondaryText: String?,
		isActive: Boolean,
		position: Int,
		totalItems: Int,
		onClick: () -> Unit
	): View {
		val itemView =
			LayoutInflater.from(context).inflate(R.layout.item_dns_selection, parent, false)
		val listItemLayout = itemView as ListItemLayout
		val cardView = itemView.findViewById<ListItemCardView>(R.id.listItemCard)
		val textView = itemView.findViewById<TextView>(R.id.tvHostname)
		val secondaryTextView = itemView.findViewById<TextView>(R.id.tvSecondaryHostname)
		val radioButton = itemView.findViewById<MaterialRadioButton>(R.id.radioDns)

		textView.text = text
		if (secondaryText != null) {
			secondaryTextView.text = secondaryText
			secondaryTextView.visibility = View.VISIBLE
		} else {
			secondaryTextView.visibility = View.GONE
		}
		cardView.isChecked = isActive
		radioButton.isChecked = isActive

		listItemLayout.updateAppearance(position, totalItems)
		cardView.setOnClickListener { onClick() }

		return itemView
	}
}
