package com.ericlowry.dnstoggle.ui.adapter

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.ericlowry.dnstoggle.data.DnsViewModel

class DnsTouchHelperCallback(
	private val adapter: HostnamesAdapter,
	private val viewModel: DnsViewModel
) : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {

	override fun isLongPressDragEnabled(): Boolean {
		return adapter.itemCount > 1
	}

	override fun getDragDirs(
		recyclerView: RecyclerView,
		viewHolder: RecyclerView.ViewHolder
	): Int {
		val position = viewHolder.bindingAdapterPosition
		val item = adapter.currentList.getOrNull(position)
		if (item?.isUnsaved == true) {
			return 0
		}
		return super.getDragDirs(recyclerView, viewHolder)
	}

	override fun canDropOver(
		recyclerView: RecyclerView,
		current: RecyclerView.ViewHolder,
		target: RecyclerView.ViewHolder
	): Boolean {
		val targetItem = adapter.currentList.getOrNull(target.bindingAdapterPosition)
		if (targetItem?.isUnsaved == true) {
			return false
		}
		return super.canDropOver(recyclerView, current, target)
	}

	override fun onMove(
		recyclerView: RecyclerView,
		viewHolder: RecyclerView.ViewHolder,
		target: RecyclerView.ViewHolder
	): Boolean {
		val fromPos = viewHolder.bindingAdapterPosition
		val toPos = target.bindingAdapterPosition

		val targetItem = adapter.currentList.getOrNull(toPos)
		if (targetItem?.isUnsaved == true) {
			return false
		}

		adapter.moveItem(fromPos, toPos)
		return true
	}

	override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

	override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
		super.onSelectedChanged(viewHolder, actionState)
		if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
			val currentList = adapter.currentList
			viewModel.dnsHostnames.value?.let { original ->
				if (currentList != original.toList()) {
					viewModel.updateHostnameOrder(currentList)
				}
			}
		}
	}
}
