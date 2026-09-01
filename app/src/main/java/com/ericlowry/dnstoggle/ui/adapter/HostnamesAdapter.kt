package com.ericlowry.dnstoggle.ui.adapter

import android.animation.ValueAnimator
import android.view.KeyEvent
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
import com.ericlowry.dnstoggle.data.ReachabilityManager
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

	private var reachabilityMap: Map<String, ReachabilityManager.ReachabilityState> = emptyMap()
	private var activeSpecifier: String? = null
	private var isToggleChecked: Boolean = false
	private var globalDefaultHostname: String? = null

	fun updateMetadata(
		reachability: Map<String, ReachabilityManager.ReachabilityState>?,
		specifier: String?,
		isToggled: Boolean,
		globalDefaultHostname: String? = null
	) {
		this.reachabilityMap = reachability ?: emptyMap()
		this.activeSpecifier = specifier
		this.isToggleChecked = isToggled
		this.globalDefaultHostname = globalDefaultHostname
		notifyItemRangeChanged(0, itemCount, "metadata")
	}

	fun moveItem(fromPos: Int, toPos: Int) {
		val mutableList = currentList.toMutableList()
		Collections.swap(mutableList, fromPos, toPos)
		submitList(mutableList)
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
			val reachability =
				reachabilityMap[hostname] ?: ReachabilityManager.ReachabilityState.IDLE
			val isActive = (hostname == activeSpecifier) && isToggleChecked
			updateStatusTextOnly(
				holder,
				reachability,
				isActive,
				dnsEntry.isUnsaved,
				holder.hostnameInfoContainer.hasFocus()
			)
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

			val reachability =
				reachabilityMap[hostname] ?: ReachabilityManager.ReachabilityState.IDLE
			val isActive = (hostname == activeSpecifier) && isToggleChecked

			if (dnsEntry.isUnsaved) {
				holder.btnEdit.visibility = View.GONE
				holder.btnDelete.visibility = View.GONE
				holder.btnAdd.visibility = View.VISIBLE

				holder.tvHostname.setTypeface(null, android.graphics.Typeface.ITALIC)

				holder.card.setCardBackgroundColor(colors.colorSurface)
				holder.unsavedBorder.visibility = View.VISIBLE
				holder.card.strokeWidth = 0
			} else {
				holder.btnEdit.visibility = View.VISIBLE
				holder.btnDelete.visibility = View.VISIBLE
				holder.btnAdd.visibility = View.GONE
				holder.unsavedBorder.visibility = View.GONE

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

			holder.tvDefaultBadge.visibility = if (hostname == globalDefaultHostname) {
				View.VISIBLE
			} else {
				View.GONE
			}

			updateStatusTextOnly(
				holder,
				reachability,
				isActive,
				dnsEntry.isUnsaved,
				holder.hostnameInfoContainer.hasFocus()
			)

			holder.btnDelete.isEnabled = currentList.size > 1
			holder.btnDelete.alpha = if (currentList.size > 1) {
				1.0f
			} else {
				0.5f
			}

			holder.btnEdit.setOnClickListener { editCallback(hostname) }
			holder.btnDelete.setOnClickListener { deleteCallback(hostname) }
			holder.btnAdd.setOnClickListener { addInPlaceCallback(hostname) }

			holder.hostnameInfoContainer.isClickable = false
			holder.hostnameInfoContainer.isFocusable = true
			holder.hostnameInfoContainer.isFocusableInTouchMode = false

			val mainClickListener = View.OnClickListener {
				if (!isActive) {
					clickCallback(hostname)
				}
			}
			holder.itemView.setOnClickListener(mainClickListener)

			holder.hostnameInfoContainer.setOnFocusChangeListener { view, hasFocus ->
				updateStatusTextOnly(holder, reachability, isActive, dnsEntry.isUnsaved, hasFocus)
				com.ericlowry.dnstoggle.util.MotionUtils.animateFocusEffect(view, hasFocus)
			}

			holder.hostnameInfoContainer.setOnKeyListener { v, keyCode, event ->
				if (event.action == KeyEvent.ACTION_UP &&
					(keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
				) {
					mainClickListener.onClick(v)
					true
				} else {
					false
				}
			}
		}
	}

	class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
		val card: MaterialCardView = view as MaterialCardView
		val hostnameInfoContainer: View = view.findViewById(R.id.hostnameInfoContainer)
		val tvHostname: TextView = view.findViewById(R.id.tvHostname)
		val tvDefaultBadge: TextView = view.findViewById(R.id.tvDefaultBadge)
		val tvSecondaryHostname: TextView = view.findViewById(R.id.tvSecondaryHostname)
		val tvStatus: TextView = view.findViewById(R.id.tvStatus)
		val btnEdit: View = view.findViewById(R.id.btnEditHostname)
		val btnDelete: View = view.findViewById(R.id.btnDeleteHostname)
		val btnAdd: View = view.findViewById(R.id.btnAddHostnameInPlace)
		val unsavedBorder: View = view.findViewById(R.id.unsavedBorder)
	}

	private fun updateStatusTextOnly(
		holder: ViewHolder,
		reachability: ReachabilityManager.ReachabilityState,
		isActive: Boolean,
		isUnsaved: Boolean,
		hasFocus: Boolean
	) {
		val context = holder.itemView.context

		if (isActive || hasFocus) {
			holder.card.strokeColor = colors.colorPrimary
			holder.card.strokeWidth = (2 * context.resources.displayMetrics.density).toInt()
		} else {
			if (isUnsaved) {
				holder.card.strokeWidth = 0
			} else {
				holder.card.strokeColor = colors.colorOutlineVariant
				holder.card.strokeWidth = (1 * context.resources.displayMetrics.density).toInt()
			}
		}

		if (isActive) {
			holder.tvStatus.visibility = View.VISIBLE

			when (reachability) {
				ReachabilityManager.ReachabilityState.TESTING -> {
					holder.tvStatus.text = context.getString(R.string.status_testing_dns)
					holder.tvStatus.setTextColor(colors.textColorSecondary)
				}

				ReachabilityManager.ReachabilityState.REACHABLE -> {
					holder.tvStatus.text = context.getString(R.string.status_active_reachable)
					holder.tvStatus.setTextColor(colors.colorPrimary)
				}

				ReachabilityManager.ReachabilityState.UNREACHABLE -> {
					holder.tvStatus.text = context.getString(R.string.warning_unreachable_dns)
					holder.tvStatus.setTextColor(colors.warningColor)
				}

				else -> {
					holder.tvStatus.visibility = View.GONE
				}
			}
		} else {
			when (reachability) {
				ReachabilityManager.ReachabilityState.TESTING -> {
					holder.tvStatus.visibility = View.VISIBLE
					holder.tvStatus.text = context.getString(R.string.status_testing_dns)
					holder.tvStatus.setTextColor(colors.textColorSecondary)
				}

				ReachabilityManager.ReachabilityState.UNREACHABLE -> {
					holder.tvStatus.visibility = View.VISIBLE
					holder.tvStatus.text = context.getString(R.string.warning_unreachable_dns)
					holder.tvStatus.setTextColor(colors.warningColor)
				}

				else -> {
					holder.tvStatus.visibility = View.GONE
				}
			}
		}
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
