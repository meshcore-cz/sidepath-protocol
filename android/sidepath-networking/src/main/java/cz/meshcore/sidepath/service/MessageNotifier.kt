package cz.meshcore.sidepath.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object MessageNotifier {
    private const val CHANNEL_ID = "meshward_messages"
    @Volatile
    private var activeConversationPeerHex: String? = null

    fun setActiveConversation(peerHex: String?) {
        activeConversationPeerHex = peerHex?.lowercase()
    }

    fun isConversationActive(peerHex: String): Boolean =
        activeConversationPeerHex == peerHex.lowercase()

    fun show(
        context: Context,
        sender: String,
        text: String,
        notificationId: Int,
    ) {
        createChannel(context)

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            ?: return

        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(sender)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notify(context, notificationId, notification)
    }

    @SuppressLint("MissingPermission")
    private fun notify(
        context: Context,
        notificationId: Int,
        notification: Notification,
    ) {
        NotificationManagerCompat
            .from(context)
            .notify(notificationId, notification)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Chat messages",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Notifications for received Meshward messages"
        }
        manager.createNotificationChannel(channel)
    }
}
