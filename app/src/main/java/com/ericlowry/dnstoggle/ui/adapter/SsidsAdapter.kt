package com.ericlowry.dnstoggle.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ericlowry.dnstoggle.R
import com.ericlowry.dnstoggle.data.SsidItem
import com.google.android.material.card.MaterialCardView

data class SsidColors(
	val colorSurface: Int,
	val colorSurfaceContainer: Int,
	val colorPrimary: Int,
	val colorOutlineVariant: Int
)

class SsidsAdapter(
	private val onEditClick: (String) -> Unit,
	private val onDeleteClick: (String) -> Unit,
	private val onConfirmClick: (String) -> Unit,
	private val colors: SsidColors
) : ListAdapter<SsidItem, SsidsAdapter.ViewHolder>(SsidItemDiffCallback()) {

	private var activeSsid: String? = null

	fun updateActiveSsid(ssid: String?) {
		this.activeSsid = ssid
		notifyItemRangeChanged(0, itemCount, "active_state")
	}

	class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
		val card: MaterialCardView = view as MaterialCardView
		val tvSsidName: TextView = view.findViewById(R.id.tvSsidName)
		val tvSsidLabel: TextView = view.findViewById(R.id.tvSsidLabel)
		val btnEdit: View = view.findViewById(R.id.btnEditSsid)
		val btnDelete: View = view.findViewById(R.id.btnDeleteSsid)
		val btnConfirm: View = view.findViewById(R.id.btnSaveSsid)
		val unsavedBorder: View = view.findViewById(R.id.unsavedBorder)
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
		val view = LayoutInflater.from(parent.context).inflate(R.layout.item_ssid, parent, false)
		return ViewHolder(view)
	}

	override fun onBindViewHolder(holder: ViewHolder, position: Int) {
		onBindViewHolder(holder, position, emptyList())
	}

	override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: List<Any>) {
		val item = getItem(position)
		val ssid = item.ssid
		val context = holder.itemView.context
		val isActive = (ssid == activeSsid)

		if (payloads.isEmpty() || payloads.contains("active_state")) {
			holder.tvSsidName.text = ssid

			if (item.isAutoDetected) {
				holder.tvSsidName.setTypeface(null, android.graphics.Typeface.ITALIC)
				holder.tvSsidLabel.text = context.getString(R.string.ssid_auto_added_label)
				holder.tvSsidLabel.visibility = View.VISIBLE

				holder.btnEdit.visibility = View.GONE
				holder.btnConfirm.visibility = View.VISIBLE
				holder.unsavedBorder.visibility = View.VISIBLE

				holder.card.setCardBackgroundColor(colors.colorSurface)
				holder.card.alpha = 0.7f
			} else {
				holder.tvSsidName.setTypeface(null, android.graphics.Typeface.NORMAL)
				holder.tvSsidLabel.visibility = View.GONE

				holder.btnEdit.visibility = View.VISIBLE
				holder.btnConfirm.visibility = View.GONE
				holder.unsavedBorder.visibility = View.GONE

				holder.card.setCardBackgroundColor(colors.colorSurfaceContainer)
				holder.card.alpha = 1.0f
			}

			if (isActive) {
				holder.card.strokeColor = colors.colorPrimary
				holder.card.strokeWidth = (2 * context.resources.displayMetrics.density).toInt()
			} else {
				if (item.isAutoDetected) {
					holder.card.strokeWidth = 0
				} else {
					holder.card.strokeColor = colors.colorOutlineVariant
					holder.card.strokeWidth = (1 * context.resources.displayMetrics.density).toInt()
				}
			}

			holder.btnEdit.setOnClickListener { onEditClick(ssid) }
			holder.btnDelete.setOnClickListener { onDeleteClick(ssid) }
			holder.btnConfirm.setOnClickListener { onConfirmClick(ssid) }
		}
	}
}

class SsidItemDiffCallback : DiffUtil.ItemCallback<SsidItem>() {
	override fun areItemsTheSame(oldItem: SsidItem, newItem: SsidItem): Boolean {
		return oldItem.ssid == newItem.ssid
	}

	override fun areContentsTheSame(oldItem: SsidItem, newItem: SsidItem): Boolean {
		return oldItem == newItem
	}
}
