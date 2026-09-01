package com.ericlowry.dnstoggle.util

import android.content.Context
import androidx.recyclerview.widget.RecyclerView

/**
 * Utility for expressive motion and physics-based UI calculations.
 * Aligned with Material 3 expressive motion principles.
 */
object MotionUtils {

	/**
	 * Material 3 standard duration tokens.
	 */
	const val DURATION_SHORT1 = 100L
	const val DURATION_SHORT2 = 150L
	const val DURATION_SHORT3 = 200L
	const val DURATION_MEDIUM1 = 250L
	const val DURATION_MEDIUM2 = 300L
	const val DURATION_MEDIUM3 = 350L
	const val DURATION_LONG1 = 400L
	const val DURATION_LONG2 = 450L
	const val DURATION_LONG3 = 500L

	/**
	 * Maximum springy stretch allowed past the edge in density-independent pixels.
	 */
	private const val MAX_STRETCH_DP = 24f

	/**
	 * Resistance constant for the rubber-band function.
	 */
	private const val RUBBER_BAND_CONSTANT = 0.55f

	/**
	 * Clamps a vertical displacement using a springy rubber-band effect relative to RecyclerView bounds.
	 *
	 * @param dY The original vertical displacement from ItemTouchHelper.
	 * @param recyclerView The parent RecyclerView.
	 * @param viewHolder The ViewHolder of the item being dragged.
	 * @return The adjusted displacement with a springy resistance effect.
	 */
	fun applySpringyDragConstraints(
		dY: Float,
		recyclerView: RecyclerView,
		viewHolder: RecyclerView.ViewHolder
	): Float {
		val topLimit = 0f - viewHolder.itemView.top
		val bottomLimit = (recyclerView.height - viewHolder.itemView.bottom).toFloat()
		return calculateRubberBandedDy(dY, topLimit, bottomLimit, recyclerView.context)
	}

	/**
	 * Calculates a rubber-banded vertical displacement for an item being dragged past its bounds.
	 *
	 * @param dY The original vertical displacement.
	 * @param topLimit The upper boundary (typically a negative value relative to item start).
	 * @param bottomLimit The lower boundary (typically positive).
	 * @param context Context to resolve density-independent pixels.
	 * @return The adjusted displacement with a springy resistance effect.
	 */
	fun calculateRubberBandedDy(
		dY: Float,
		topLimit: Float,
		bottomLimit: Float,
		context: Context
	): Float {
		val maxStretch = MAX_STRETCH_DP * context.resources.displayMetrics.density

		return when {
			dY < topLimit -> {
				val excess = topLimit - dY
				topLimit - rubberBand(excess, maxStretch)
			}

			dY > bottomLimit -> {
				val excess = dY - bottomLimit
				bottomLimit + rubberBand(excess, maxStretch)
			}

			else -> {
				dY
			}
		}
	}

	private fun rubberBand(offset: Float, maxStretch: Float): Float {
		// Hyperbolic scaling: f(x) = maxStretch * (1 - 1 / ((x * resistance / maxStretch) + 1))
		return maxStretch * (1f - (1f / ((offset * RUBBER_BAND_CONSTANT / maxStretch) + 1f)))
	}

	/**
	 * Animates a view's scale to indicate focus state.
	 */
	fun animateFocusEffect(view: android.view.View, hasFocus: Boolean) {
		val scale = if (hasFocus) {
			1.02f
		} else {
			1.0f
		}
		view.animate()
			.scaleX(scale)
			.scaleY(scale)
			.setDuration(DURATION_SHORT2)
			.setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
			.start()
	}
}
