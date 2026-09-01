package com.ericlowry.dnstoggle.util

import android.view.View
import android.view.ViewGroup
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager

/**
 * Hides a view if it should be disabled or and reveals it using animation when a container is provided when enabled.
 */
fun View.setConditionalVisibility(isVisible: Boolean, container: ViewGroup? = null) {
	val targetVisibility = if (isVisible) {
		View.VISIBLE
	} else {
		View.GONE
	}
	if (visibility == targetVisibility) {
		return
	}

	if (container != null) {
		val transition = AutoTransition().apply {
			duration = MotionUtils.DURATION_MEDIUM1
		}
		TransitionManager.beginDelayedTransition(container, transition)
	}

	visibility = targetVisibility
}

/**
 * Dims a view if it should be disabled.
 */
fun View.setDimmedEnabled(enabled: Boolean) {
	isEnabled = enabled
	alpha = if (enabled) {
		1.0f
	} else {
		0.5f
	}
}
