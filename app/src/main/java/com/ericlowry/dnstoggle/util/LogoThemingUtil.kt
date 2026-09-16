package com.ericlowry.dnstoggle.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
import com.ericlowry.dnstoggle.R
import java.util.Calendar

object LogoThemingUtil {

	data class ThemeDate(val month: Int, val day: Int)

	val specialDates = mapOf(
		ThemeDate(6, 28) to arrayOf(
			"#E40303",
			"#FF8C00",
			"#FFED00",
			"#008026",
			"#004DFF",
			"#750787"
		),
		ThemeDate(3, 31) to arrayOf("#5BCEFA", "#F5A9B8", "#FFFFFF", "#F5A9B8", "#5BCEFA"),
		ThemeDate(4, 6) to arrayOf("#000000", "#A3A3A3", "#FFFFFF", "#800080"),
		ThemeDate(4, 26) to arrayOf(
			"#D52D00",
			"#EF7627",
			"#FF9A56",
			"#FFFFFF",
			"#D162A4",
			"#B55690",
			"#A30262"
		),
		ThemeDate(5, 24) to arrayOf("#FF218C", "#FFD800", "#21B1FF"),
		ThemeDate(7, 14) to arrayOf("#FCF434", "#FFFFFF", "#9C59D1", "#2C2C2C"),
		ThemeDate(9, 23) to arrayOf("#D60270", "#D60270", "#9B4F96", "#0038A8", "#0038A8"),
	)

	/**
	 * Checks if today is a special date and returns the corresponding hex color array.
	 */
	fun getTodayColors(): Array<String>? {
		val calendar = Calendar.getInstance()
		val currentMonth = calendar[Calendar.MONTH] + 1 // 1-indexed
		val currentDay = calendar[Calendar.DAY_OF_MONTH]
		return specialDates[ThemeDate(currentMonth, currentDay)]
	}

	/**
	 * Generates a hard-banded gradient logo from the existing vector drawable.
	 */
	fun getThemedLogo(context: Context, hexColors: Array<String>): Drawable? {
		val vector = ContextCompat.getDrawable(context, R.drawable.ic_qs_dns) ?: return null

		val bitmap = createBitmap(
			vector.intrinsicWidth,
			vector.intrinsicHeight,
			Bitmap.Config.ARGB_8888,
		)
		val canvas = Canvas(bitmap)
		vector.setBounds(0, 0, canvas.width, canvas.height)
		vector.draw(canvas)

		val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
		}

		val colors = IntArray(hexColors.size * 2)
		val positions = FloatArray(hexColors.size * 2)
		val step = 1f / hexColors.size

		for (i in hexColors.indices) {
			val color = hexColors[i].toColorInt()
			colors[i * 2] = color
			colors[(i * 2) + 1] = color

			positions[i * 2] = i * step
			positions[(i * 2) + 1] = (i + 1) * step
		}

		paint.shader = LinearGradient(
			0f, 0f, 0f, bitmap.height.toFloat(),
			colors, positions, Shader.TileMode.CLAMP,
		)

		canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), paint)

		return bitmap.toDrawable(context.resources)
	}
}
