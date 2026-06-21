package com.ericlowry.dnstoggle

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object NotificationUtils {

    fun showStatusNotification(context: Context, message: String) {
        val notificationBuilder = NotificationCompat.Builder(context, Constants.CHANNEL_ID_ALERT)
            .setSmallIcon(R.drawable.ic_qs_dns)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(context)) {
            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

            if (hasPermission) {
                notify(Constants.NOTIFICATION_ID_STATUS, notificationBuilder.build())
            }
        }
    }

    fun cancelStatusNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(Constants.NOTIFICATION_ID_STATUS)
    }
}
