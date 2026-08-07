package com.ericlowry.dnstoggle.ui.dialog

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.edit
import androidx.core.net.toUri
import com.ericlowry.dnstoggle.R
import com.google.android.material.button.MaterialButton

object _InfoNoticeHelper {
	private const val TAG = "FeedbackHelper"
	private const val PREF_FEEDBACK_SHOWN = "pref_feedback_shown_v2_pre"

	fun showOnceOnStartup(activity: AppCompatActivity) {
		val prefs = activity.getSharedPreferences("app_prefs_v2", Context.MODE_PRIVATE)
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

		view.findViewById<MaterialButton>(R.id.btnGithub)?.setOnClickListener {
			openUrl(
				context,
				"https://github.com/ELowry/DNSToggle/discussions/new?category=chat&title=%5Bv2%3A%5D%20"
			)
			dialog.dismiss()
		}

		view.findViewById<MaterialButton>(R.id.btnSupport)?.setOnClickListener {
			openUrl(
				context,
				"https://github.com/ELowry/DNSToggle/issues/new?template=bug-report.yml&title=%5BBug%2Fv2%3A%5D%20"
			)
			dialog.dismiss()
		}

		dialog.show()
	}

	fun injectBetaFeedbackButton(
		activity: AppCompatActivity,
		parent: ConstraintLayout,
		belowOfId: Int = -1
	) {
		val button = MaterialButton(activity).apply {
			id = View.generateViewId()
			text = "V2 Feedback"
			setIconResource(R.drawable.ic_warning)
			setOnClickListener { showDialog(activity) }

			isFocusable = true
			isFocusableInTouchMode = false

			val padding = (4 * activity.resources.displayMetrics.density).toInt()
			setPadding(padding * 2, padding, padding * 2, padding)

			setOnFocusChangeListener { view, hasFocus ->
				val scale = if (hasFocus) 1.05f else 1.0f
				val elevation = if (hasFocus) 8f * activity.resources.displayMetrics.density else 0f

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

	private fun openUrl(context: Context, url: String) {
		try {
			context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
		} catch (e: Exception) {
			Log.e(TAG, "Failed to open URL: $url", e)
		}
	}
}