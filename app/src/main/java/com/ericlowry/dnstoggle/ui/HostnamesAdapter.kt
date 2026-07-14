package com.ericlowry.dnstoggle.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
	private val onItemClick: (String) -> Unit
) : ListAdapter<DnsHostname, HostnamesAdapter.ViewHolder>(DnsHostnameDiffCallback()) {

	private var reachabilityMap: Map<String, DnsViewModel.ReachabilityState> = emptyMap()
	private var activeSpecifier: String? = null
	private var isToggleChecked: Boolean = false

	fun updateMetadata(
		reachability: Map<String, DnsViewModel.ReachabilityState>?,
		specifier: String?,
		isToggled: Boolean
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
		val card: MaterialCardView =
			view as MaterialCardView
		val tvHostname: TextView = view.findViewById(R.id.tvHostname)
		val tvSecondaryHostname: TextView = view.findViewById(R.id.tvSecondaryHostname)
		val tvStatus: TextView = view.findViewById(R.id.tvStatus)
		val btnEdit: View = view.findViewById(R.id.btnEditHostname)
		val btnDelete: View = view.findViewById(R.id.btnDeleteHostname)
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

		if (payloads.isEmpty() || payloads.contains("metadata")) {
			if (label != null) {
				holder.tvHostname.text = label
				holder.tvSecondaryHostname.text = hostname
				holder.tvSecondaryHostname.visibility = View.VISIBLE
			} else {
				holder.tvHostname.text = hostname
				holder.tvSecondaryHostname.visibility = View.GONE
			}

			val reachability = reachabilityMap[hostname] ?: DnsViewModel.ReachabilityState.IDLE
			val isActive = (hostname == activeSpecifier && isToggleChecked)

			if (isActive) {
				holder.card.strokeColor =
					MaterialColors.getColor(holder.itemView, android.R.attr.colorPrimary)
				holder.card.strokeWidth = (2 * context.resources.displayMetrics.density).toInt()
				holder.tvStatus.visibility = View.VISIBLE

				when (reachability) {
					DnsViewModel.ReachabilityState.TESTING -> {
						holder.tvStatus.text = context.getString(R.string.status_testing_dns)
						holder.tvStatus.setTextColor(
							MaterialColors.getColor(
								holder.itemView,
								android.R.attr.textColorSecondary
							)
						)
					}

					DnsViewModel.ReachabilityState.REACHABLE -> {
						holder.tvStatus.text = context.getString(R.string.status_active_reachable)
						holder.tvStatus.setTextColor(
							MaterialColors.getColor(
								holder.itemView,
								android.R.attr.colorPrimary
							)
						)
					}

					DnsViewModel.ReachabilityState.UNREACHABLE -> {
						holder.tvStatus.text = context.getString(R.string.warning_unreachable_dns)
						holder.tvStatus.setTextColor(
							MaterialColors.getColor(
								holder.itemView,
								R.attr.warning_color
							)
						)
					}

					else -> holder.tvStatus.visibility = View.GONE
				}
			} else {
				holder.card.strokeWidth = 0
				when (reachability) {
					DnsViewModel.ReachabilityState.TESTING -> {
						holder.tvStatus.visibility = View.VISIBLE
						holder.tvStatus.text = context.getString(R.string.status_testing_dns)
						holder.tvStatus.setTextColor(
							MaterialColors.getColor(
								holder.itemView,
								android.R.attr.textColorSecondary
							)
						)
					}

					DnsViewModel.ReachabilityState.UNREACHABLE -> {
						holder.tvStatus.visibility = View.VISIBLE
						holder.tvStatus.text = context.getString(R.string.warning_unreachable_dns)
						holder.tvStatus.setTextColor(
							MaterialColors.getColor(
								holder.itemView,
								R.attr.warning_color
							)
						)
					}

					else -> holder.tvStatus.visibility = View.GONE
				}
			}

			holder.btnEdit.setOnClickListener { onEditClick(hostname) }
			holder.btnDelete.isEnabled = currentList.size > 1
			holder.btnDelete.alpha = if (currentList.size > 1) 1.0f else 0.5f
			holder.btnDelete.setOnClickListener { onDeleteClick(hostname) }
			holder.itemView.setOnClickListener {
				if (!isActive) onItemClick(hostname)
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
}