package app.songyan.ime.deploy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.songyan.ime.R
import app.songyan.ime.SongyanSettingsActivity

internal object DeployNotifications {
    const val CHANNEL_ID = "deploy"
    private const val NOTIFICATION_ID = 1

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.deploy_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.deploy_channel_description)
                setShowBadge(false)
            }
        manager.createNotificationChannel(channel)
    }

    fun progress(
        context: Context,
        titleRes: Int,
        textRes: Int,
    ): Notification {
        ensureChannel(context)
        return base(context)
            .setContentTitle(context.getString(titleRes))
            .setContentText(context.getString(textRes))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .build()
    }

    fun finished(
        context: Context,
        success: Boolean,
    ): Notification {
        ensureChannel(context)
        val title = context.getString(R.string.deploy_notification_title)
        val text =
            context.getString(
                if (success) R.string.deploy_notification_done else R.string.deploy_notification_failed,
            )
        val intent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, SongyanSettingsActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return base(context)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
    }

    private fun base(context: Context): NotificationCompat.Builder =
        NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)

    fun notifyFinished(
        context: Context,
        success: Boolean,
    ) {
        context
            .getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, finished(context, success))
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    const val SERVICE_NOTIFICATION_ID = 1
}
