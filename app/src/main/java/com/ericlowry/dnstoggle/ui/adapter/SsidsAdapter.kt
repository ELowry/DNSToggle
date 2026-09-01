package com.ericlowry.dnstoggle.ui.adapter

import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ericlowry.dnstoggle.R
import com.ericlowry.dnstoggle.data.DnsHostname
import com.ericlowry.dnstoggle.data.NetworkProfile
import com.google.android.material.card.MaterialCardView
import java.util.Collections

data class SsidColors(
	val colorSurface: Int,
	val colorSurfaceContainer: Int,
	val colorPrimary: Int,
	val colorOutlineVariant: Int
)

class SsidsAdapter(
	private val onToggleClick: (NetworkProfile) -> Unit,
	private val onEditClick: (NetworkProfile) -> Unit,
	private val onDeleteClick: (NetworkProfile) -> Unit,
	private val onConfirmClick: (NetworkProfile) -> Unit,
	private val colors: SsidColors
) : ListAdapter<NetworkProfile, SsidsAdapter.ViewHolder>(NetworkProfileDiffCallback()) {

	private var activeSsid: String? = null
	private var hostnames: List<DnsHostname> = emptyList()

	fun updateActiveSsid(ssid: String?) {
		this.activeSsid = ssid
		notifyItemRangeChanged(0, itemCount, "active_state")
	}

	fun updateHostnames(hostnames: List<DnsHostname>) {
		this.hostnames = hostnames
		notifyItemRangeChanged(0, itemCount, "hostname_labels")
	}

	fun moveItem(fromPos: Int, toPos: Int) {
		val mutableList = currentList.toMutableList()
		Collections.swap(mutableList, fromPos, toPos)
		submitList(mutableList)
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

		if (payloads.isEmpty() || payloads.contains("active_state") || payloads.contains("hostname_labels")) {
			val isTransient = item.isAutoDetected || item.isUnsaved

			holder.tvSsidName.text = ssid
			if (isTransient) {
				holder.tvSsidName.setTypeface(null, android.graphics.Typeface.ITALIC)
			} else {
				holder.tvSsidName.setTypeface(null, android.graphics.Typeface.NORMAL)
			}

			val hostnameLabel =
				hostnames.find {
					it.hostname == item.targetHostname
				}?.getDisplayName()
			val baseHostString = hostnameLabel ?: item.targetHostname
			?: context.getString(R.string.default_dns_label)

			val labelBase = if (item.isEnabled) {
				baseHostString
			} else {
				context.getString(R.string.off_profile_format, baseHostString)
			}

			if (item.isAutoDetected) {
				val prefix = context.getString(R.string.auto_blocked_prefix)
				val fullText = "$prefix $labelBase"
				val spannable = SpannableString(fullText)
				spannable.setSpan(
					StyleSpan(android.graphics.Typeface.ITALIC),
					0,
					prefix.length,
					SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
				)
				holder.tvSsidLabel.text = spannable
			} else {
				holder.tvSsidLabel.text = labelBase
			}

			if (item.isEnabled || isActive) {
				holder.card.setCardBackgroundColor(colors.colorSurfaceContainer)
				holder.card.cardElevation = if (isActive && !isTransient) {
					2 * context.resources.displayMetrics.density
				} else {
					0f
				}
			} else {
				holder.card.setCardBackgroundColor(colors.colorSurface)
				holder.card.cardElevation = 0f
			}

			updateCardStroke(holder, item, isActive, holder.ssidInfoContainer.hasFocus())

			holder.btnEdit.visibility = View.VISIBLE
			holder.btnConfirm.visibility = if (isTransient) {
				View.VISIBLE
			} else {
				View.GONE
			}
			holder.unsavedBorder.visibility = if (isTransient) {
				View.VISIBLE
			} else {
				View.GONE
			}

			holder.ssidInfoContainer.isClickable = false
			holder.ssidInfoContainer.isFocusable = true
			holder.ssidInfoContainer.isFocusableInTouchMode = false

			val toggleListener = View.OnClickListener {
				onToggleClick(item)
			}

			holder.card.setOnClickListener(toggleListener)

			holder.ssidInfoContainer.setOnFocusChangeListener { view, hasFocus ->
				updateCardStroke(holder, item, isActive, hasFocus)
				com.ericlowry.dnstoggle.util.MotionUtils.animateFocusEffect(view, hasFocus)
			}

			holder.ssidInfoContainer.setOnKeyListener { _, keyCode, event ->
				if (event.action == android.view.KeyEvent.ACTION_UP &&
					(keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER)
				) {
					toggleListener.onClick(holder.card)
					true
				} else {
					false
				}
			}

			holder.btnEdit.setOnClickListener {
				onEditClick(item)
			}
			holder.btnDelete.setOnClickListener {
				onDeleteClick(item)
			}
			holder.btnConfirm.setOnClickListener {
				onConfirmClick(item)
			}
		}
	}

	class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
		val card: MaterialCardView = view as MaterialCardView
		val ssidInfoContainer: View = view.findViewById(R.id.ssidInfoContainer)
		val tvSsidName: TextView = view.findViewById(R.id.tvSsidName)
		val tvSsidLabel: TextView = view.findViewById(R.id.tvSsidLabel)
		val btnEdit: View = view.findViewById(R.id.btnEditSsid)
		val btnDelete: View = view.findViewById(R.id.btnDeleteSsid)
		val btnConfirm: View = view.findViewById(R.id.btnSaveSsid)
		val unsavedBorder: View = view.findViewById(R.id.unsavedBorder)
	}

	private fun updateCardStroke(
		holder: ViewHolder,
		item: NetworkProfile,
		isActive: Boolean,
		hasFocus: Boolean
	) {
		val context = holder.itemView.context
		val isTransient = item.isAutoDetected || item.isUnsaved

		if (isActive || hasFocus) {
			holder.card.strokeColor = colors.colorPrimary
			holder.card.strokeWidth = (2 * context.resources.displayMetrics.density).toInt()
		} else {
			if (isTransient) {
				holder.card.strokeWidth = 0
			} else if (item.isEnabled) {
				holder.card.strokeColor = colors.colorOutlineVariant
				holder.card.strokeWidth = (1 * context.resources.displayMetrics.density).toInt()
			} else {
				holder.card.strokeWidth = 0
			}
		}
	}
}

class NetworkProfileDiffCallback : DiffUtil.ItemCallback<NetworkProfile>() {
	override fun areItemsTheSame(oldItem: NetworkProfile, newItem: NetworkProfile): Boolean {
		return oldItem.ssid == newItem.ssid
	}

	override fun areContentsTheSame(oldItem: NetworkProfile, newItem: NetworkProfile): Boolean {
		return oldItem == newItem
	}
}
