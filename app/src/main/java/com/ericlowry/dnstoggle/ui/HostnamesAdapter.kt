package com.ericlowry.dnstoggle.ui

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
import com.google.android.material.color.MaterialColors
import java.util.Collections

class HostnamesAdapter(
	private val onEditClick: (String) -> Unit,
	private val onDeleteClick: (String) -> Unit,
	private val onItemClick: (String) -> Unit,
	private val onAddInPlaceClick: (String) -> Unit,
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

		// Background color pulse
		val pulseColor = MaterialColors.getColor(
			card,
			com.google.android.material.R.attr.colorSecondaryContainer
		)
		val surfaceColor = MaterialColors.getColor(
			card,
			com.google.android.material.R.attr.colorSurfaceContainer
		)

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
		val label = dnsEntry.label
		val context = holder.itemView.context

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

				holder.card.setCardBackgroundColor(
					MaterialColors.getColor(
						holder.itemView,
						com.google.android.material.R.attr.colorSurface
					)
				)
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

				holder.card.setCardBackgroundColor(
					MaterialColors.getColor(
						holder.itemView,
						com.google.android.material.R.attr.colorSurfaceContainer
					)
				)

				if (isActive) {
					holder.card.strokeColor = MaterialColors.getColor(
						holder.itemView,
						android.R.attr.colorPrimary
					)
					holder.card.strokeWidth = (2 * context.resources.displayMetrics.density).toInt()
				} else {
					holder.card.strokeColor = MaterialColors.getColor(
						holder.itemView,
						com.google.android.material.R.attr.colorOutlineVariant
					)
					holder.card.strokeWidth = (1 * context.resources.displayMetrics.density).toInt()
				}
			}

			if (isActive) {
				holder.tvStatus.visibility = View.VISIBLE

				when (reachability) {
					DnsViewModel.ReachabilityState.TESTING -> {
						holder.tvStatus.text = context.getString(R.string.status_testing_dns)
						holder.tvStatus.setTextColor(
							MaterialColors.getColor(
								holder.itemView,
								android.R.attr.textColorSecondary,
							),
						)
					}

					DnsViewModel.ReachabilityState.REACHABLE -> {
						holder.tvStatus.text = context.getString(R.string.status_active_reachable)
						holder.tvStatus.setTextColor(
							MaterialColors.getColor(
								holder.itemView,
								android.R.attr.colorPrimary,
							),
						)
					}

					DnsViewModel.ReachabilityState.UNREACHABLE -> {
						holder.tvStatus.text = context.getString(R.string.warning_unreachable_dns)
						holder.tvStatus.setTextColor(
							MaterialColors.getColor(
								holder.itemView,
								R.attr.warning_color,
							),
						)
					}

					else -> holder.tvStatus.visibility = View.GONE
				}
			} else {
				when (reachability) {
					DnsViewModel.ReachabilityState.TESTING -> {
						holder.tvStatus.visibility = View.VISIBLE
						holder.tvStatus.text = context.getString(R.string.status_testing_dns)
						holder.tvStatus.setTextColor(
							MaterialColors.getColor(
								holder.itemView,
								android.R.attr.textColorSecondary,
							),
						)
					}

					DnsViewModel.ReachabilityState.UNREACHABLE -> {
						holder.tvStatus.visibility = View.VISIBLE
						holder.tvStatus.text = context.getString(R.string.warning_unreachable_dns)
						holder.tvStatus.setTextColor(
							MaterialColors.getColor(
								holder.itemView,
								R.attr.warning_color,
							),
						)
					}

					else -> holder.tvStatus.visibility = View.GONE
				}
			}

			holder.btnEdit.setOnClickListener { editCallback(hostname) }
			holder.btnDelete.setOnClickListener { deleteCallback(hostname) }
			holder.btnAdd.setOnClickListener { addInPlaceCallback(hostname) }
			holder.itemView.setOnClickListener {
				if (!isActive) clickCallback(hostname)
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