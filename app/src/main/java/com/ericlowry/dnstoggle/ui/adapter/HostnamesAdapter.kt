package com.ericlowry.dnstoggle.ui.adapter

import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ericlowry.dnstoggle.R
import com.ericlowry.dnstoggle.data.DnsHostname
import com.ericlowry.dnstoggle.data.DnsViewModel
import com.google.android.material.card.MaterialCardView
import java.util.Collections

data class HostnameColors(
	val colorSurface: Int,
	val colorSurfaceContainer: Int,
	val colorSecondaryContainer: Int,
	val colorPrimary: Int,
	val colorOutlineVariant: Int,
	val textColorSecondary: Int,
	val warningColor: Int
)

class HostnamesAdapter(
	private val onEditClick: (String) -> Unit,
	private val onDeleteClick: (String) -> Unit,
	private val onItemClick: (String) -> Unit,
	private val onAddInPlaceClick: (String) -> Unit,
	private val colors: HostnameColors
) : ListAdapter<DnsHostname, HostnamesAdapter.ViewHolder>(DnsHostnameDiffCallback()) {

	private var reachabilityMap: Map<String, DnsViewModel.ReachabilityState> = emptyMap()
	private var activeSpecifier: String? = null
	private var isToggleChecked: Boolean = false

	fun updateMetadata(
		reachability: Map<String, DnsViewModel.ReachabilityState>?,
		specifier: String?,
		isToggled: Boolean,
	) {
		this.reachabilityMap = reachability ?: emptyMap()
		this.activeSpecifier = specifier
		this.isToggleChecked = isToggled
		notifyItemRangeChanged(0, itemCount, "metadata")
	}

	fun moveItem(fromPos: Int, toPos: Int) {
		val mutableList = currentList.toMutableList()
		Collections.swap(mutableList, fromPos, toPos)
		submitList(mutableList)
	}

	class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
		val card: MaterialCardView = view as MaterialCardView
		val tvHostname: TextView = view.findViewById(R.id.tvHostname)
		val tvSecondaryHostname: TextView = view.findViewById(R.id.tvSecondaryHostname)
		val tvStatus: TextView = view.findViewById(R.id.tvStatus)
		val btnEdit: View = view.findViewById(R.id.btnEditHostname)
		val btnDelete: View = view.findViewById(R.id.btnDeleteHostname)
		val btnAdd: View = view.findViewById(R.id.btnAddHostnameInPlace)
		val unsavedBorder: View = view.findViewById(R.id.unsavedBorder)
	}

	private fun triggerSaveAnimation(holder: ViewHolder) {
		val card = holder.card

		val pulseColor = colors.colorSecondaryContainer
		val surfaceColor = colors.colorSurfaceContainer

		val colorAnim = ValueAnimator.ofArgb(surfaceColor, pulseColor, surfaceColor)
		colorAnim.addUpdateListener { animator ->
			card.setCardBackgroundColor(animator.animatedValue as Int)
		}
		colorAnim.duration = 600
		colorAnim.interpolator = AccelerateDecelerateInterpolator()

		colorAnim.start()
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
		val view =
			LayoutInflater.from(parent.context).inflate(R.layout.item_hostname, parent, false)
		return ViewHolder(view)
	}

	override fun onBindViewHolder(holder: ViewHolder, position: Int) {
		onBindViewHolder(holder, position, emptyList())
	}

	override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: List<Any>) {
		val dnsEntry = getItem(position)
		val hostname = dnsEntry.hostname

		if (payloads.size == 1 && payloads.contains("metadata")) {
			val reachability = reachabilityMap[hostname] ?: DnsViewModel.ReachabilityState.IDLE
			val isActive = (hostname == activeSpecifier) && isToggleChecked
			updateStatusTextOnly(holder, reachability, isActive, dnsEntry.isUnsaved)
			return
		}

		val label = dnsEntry.label

		if (payloads.contains("saved")) {
			triggerSaveAnimation(holder)
		}

		if (payloads.isEmpty() || payloads.contains("metadata") || payloads.contains("saved")) {
			val editCallback = onEditClick
			val deleteCallback = onDeleteClick
			val clickCallback = onItemClick
			val addInPlaceCallback = onAddInPlaceClick

			if (label != null) {
				holder.tvHostname.text = label
				holder.tvSecondaryHostname.text = hostname
				holder.tvSecondaryHostname.visibility = View.VISIBLE
			} else {
				holder.tvHostname.text = hostname
				holder.tvSecondaryHostname.visibility = View.GONE
			}

			val reachability = reachabilityMap[hostname] ?: DnsViewModel.ReachabilityState.IDLE
			val isActive = (hostname == activeSpecifier) && isToggleChecked

			if (dnsEntry.isUnsaved) {
				holder.btnEdit.visibility = View.GONE
				holder.btnDelete.visibility = View.GONE
				holder.btnAdd.visibility = View.VISIBLE

				holder.tvHostname.alpha = 1.0f
				holder.tvSecondaryHostname.alpha = 1.0f

				holder.tvHostname.setTypeface(null, android.graphics.Typeface.ITALIC)

				holder.card.setCardBackgroundColor(colors.colorSurface)
				holder.unsavedBorder.visibility = View.VISIBLE
				holder.card.strokeWidth = 0
			} else {
				holder.btnEdit.visibility = View.VISIBLE
				holder.btnDelete.visibility = View.VISIBLE
				holder.btnAdd.visibility = View.GONE
				holder.unsavedBorder.visibility = View.GONE

				holder.tvHostname.alpha = 1.0f
				holder.tvSecondaryHostname.alpha = 1.0f
				holder.tvHostname.setTypeface(null, android.graphics.Typeface.NORMAL)

				holder.card.setCardBackgroundColor(colors.colorSurfaceContainer)

				if (isActive) {
					holder.card.strokeColor = colors.colorPrimary
					holder.card.strokeWidth =
						(2 * holder.itemView.context.resources.displayMetrics.density).toInt()
				} else {
					holder.card.strokeColor = colors.colorOutlineVariant
					holder.card.strokeWidth =
						(1 * holder.itemView.context.resources.displayMetrics.density).toInt()
				}
			}

			updateStatusTextOnly(holder, reachability, isActive, dnsEntry.isUnsaved)

			holder.btnDelete.isEnabled = currentList.size > 1
			holder.btnDelete.alpha = if (currentList.size > 1) 1.0f else 0.5f

			holder.btnEdit.setOnClickListener { editCallback(hostname) }
			holder.btnDelete.setOnClickListener { deleteCallback(hostname) }
			holder.btnAdd.setOnClickListener { addInPlaceCallback(hostname) }
			holder.itemView.setOnClickListener {
				if (!isActive) clickCallback(hostname)
			}
		}
	}

	private fun updateStatusTextOnly(
		holder: ViewHolder,
		reachability: DnsViewModel.ReachabilityState,
		isActive: Boolean,
		isUnsaved: Boolean
	) {
		val context = holder.itemView.context

		if (!isUnsaved) {
			if (isActive) {
				holder.card.strokeColor = colors.colorPrimary
				holder.card.strokeWidth = (2 * context.resources.displayMetrics.density).toInt()
			} else {
				holder.card.strokeColor = colors.colorOutlineVariant
				holder.card.strokeWidth = (1 * context.resources.displayMetrics.density).toInt()
			}
		}

		if (isActive) {
			holder.tvStatus.visibility = View.VISIBLE

			when (reachability) {
				DnsViewModel.ReachabilityState.TESTING -> {
					holder.tvStatus.text = context.getString(R.string.status_testing_dns)
					holder.tvStatus.setTextColor(colors.textColorSecondary)
				}

				DnsViewModel.ReachabilityState.REACHABLE -> {
					holder.tvStatus.text = context.getString(R.string.status_active_reachable)
					holder.tvStatus.setTextColor(colors.colorPrimary)
				}

				DnsViewModel.ReachabilityState.UNREACHABLE -> {
					holder.tvStatus.text = context.getString(R.string.warning_unreachable_dns)
					holder.tvStatus.setTextColor(colors.warningColor)
				}

				else -> holder.tvStatus.visibility = View.GONE
			}
		} else {
			when (reachability) {
				DnsViewModel.ReachabilityState.TESTING -> {
					holder.tvStatus.visibility = View.VISIBLE
					holder.tvStatus.text = context.getString(R.string.status_testing_dns)
					holder.tvStatus.setTextColor(colors.textColorSecondary)
				}

				DnsViewModel.ReachabilityState.UNREACHABLE -> {
					holder.tvStatus.visibility = View.VISIBLE
					holder.tvStatus.text = context.getString(R.string.warning_unreachable_dns)
					holder.tvStatus.setTextColor(colors.warningColor)
				}

				else -> holder.tvStatus.visibility = View.GONE
			}
		}
	}
}

class DnsHostnameDiffCallback : DiffUtil.ItemCallback<DnsHostname>() {
	override fun areItemsTheSame(oldItem: DnsHostname, newItem: DnsHostname): Boolean {
		return oldItem.hostname == newItem.hostname
	}

	override fun areContentsTheSame(oldItem: DnsHostname, newItem: DnsHostname): Boolean {
		return oldItem == newItem
	}

	override fun getChangePayload(oldItem: DnsHostname, newItem: DnsHostname): Any? {
		return if (oldItem.isUnsaved && !newItem.isUnsaved) {
			"saved"
		} else {
			super.getChangePayload(oldItem, newItem)
		}
	}
}
