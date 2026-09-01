package com.ericlowry.dnstoggle.ui.dialog

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.edit
import com.ericlowry.dnstoggle.R
import com.google.android.material.button.MaterialButton

@Suppress("unused", "ClassName", "SetTextI18n", "SpellCheckingInspection")
object _InfoNoticeHelper {
	private const val PREF_FEEDBACK_SHOWN = "pref_warning_shown_v2.1"

	fun showOnceOnStartup(activity: AppCompatActivity) {
		val prefs = activity.getSharedPreferences("app_prefs_v2.1", Context.MODE_PRIVATE)
		if (!prefs.getBoolean(PREF_FEEDBACK_SHOWN, false)) {
			showDialog(activity)
			prefs.edit { putBoolean(PREF_FEEDBACK_SHOWN, true) }
		}
	}

	fun showDialog(context: Context) {
		val view = LayoutInflater.from(context).inflate(R.layout._temp_dialog_info, null)

		val dialog =
			com.google.android.material.dialog.MaterialAlertDialogBuilder(context).setView(view)
				.create()

		dialog.show()
	}

	fun injectNoticeButton(
		activity: AppCompatActivity,
		parent: ConstraintLayout,
		belowOfId: Int = -1
	) {
		val button = MaterialButton(activity).apply {
			id = View.generateViewId()
			text = "Deprecation Warning"
			setIconResource(R.drawable.ic_warning)
			setOnClickListener { showDialog(activity) }

			isFocusable = true
			isFocusableInTouchMode = false

			val padding = (4 * activity.resources.displayMetrics.density).toInt()
			setPadding(padding * 2, padding, padding * 2, padding)

			setOnFocusChangeListener { view, hasFocus ->
				val scale = if (hasFocus) {
					1.05f
				} else {
					1.0f
				}
				val elevation = if (hasFocus) {
					8f * activity.resources.displayMetrics.density
				} else {
					0f
				}

				view.animate()
					.scaleX(scale)
					.scaleY(scale)
					.translationZ(elevation)
					.setDuration(150)
					.start()
			}
		}

		parent.addView(button)

		val margin = (16 * activity.resources.displayMetrics.density).toInt()

		val set = ConstraintSet()
		set.clone(parent)

		// Constrain button to top of parent
		set.connect(
			button.id,
			ConstraintSet.TOP,
			ConstraintSet.PARENT_ID,
			ConstraintSet.TOP,
			margin
		)
		set.connect(
			button.id,
			ConstraintSet.START,
			ConstraintSet.PARENT_ID,
			ConstraintSet.START,
			margin
		)
		set.connect(
			button.id,
			ConstraintSet.END,
			ConstraintSet.PARENT_ID,
			ConstraintSet.END,
			margin
		)
		set.constrainWidth(button.id, ConstraintSet.WRAP_CONTENT)
		set.constrainHeight(button.id, ConstraintSet.WRAP_CONTENT)

		// If there was a view at the top, push it down
		if (belowOfId != -1) {
			set.connect(belowOfId, ConstraintSet.TOP, button.id, ConstraintSet.BOTTOM, margin)
		}

		set.applyTo(parent)
	}
}