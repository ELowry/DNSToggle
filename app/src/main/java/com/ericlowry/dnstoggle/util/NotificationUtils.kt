package com.ericlowry.dnstoggle.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ericlowry.dnstoggle.R
import com.ericlowry.dnstoggle.data.Constants

object NotificationUtils {

	fun showStatusNotification(context: Context, message: String) {
		val appContext = context.applicationContext
		val notificationBuilder = NotificationCompat.Builder(appContext, Constants.CHANNEL_ID_ALERT)
			.setSmallIcon(R.drawable.ic_qs_dns)
			.setContentTitle(appContext.getString(R.string.app_name))
			.setContentText(message)
			.setPriority(NotificationCompat.PRIORITY_DEFAULT)
			.setAutoCancel(true)

		val manager = NotificationManagerCompat.from(appContext)
		val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			ContextCompat.checkSelfPermission(
				appContext,
				Manifest.permission.POST_NOTIFICATIONS,
			) == PackageManager.PERMISSION_GRANTED
		} else {
			true
		}

		val channel = manager.getNotificationChannel(Constants.CHANNEL_ID_ALERT)
		val isChannelBlocked =
			channel != null && channel.importance == android.app.NotificationManager.IMPORTANCE_NONE

		if (hasPermission && !isChannelBlocked) {
			manager.notify(Constants.NOTIFICATION_ID_STATUS, notificationBuilder.build())
		} else {
			android.os.Handler(android.os.Looper.getMainLooper()).post {
				android.widget.Toast.makeText(appContext, message, android.widget.Toast.LENGTH_LONG)
					.show()
			}
		}
	}
}