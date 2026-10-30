package com.cipherpath.work

import android.Manifest
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
import com.cipherpath.MainActivity
import com.cipherpath.R
import com.cipherpath.data.db.NotificationDao
import com.cipherpath.data.db.NotificationEntity

class Notifier(private val context: Context, private val dao: NotificationDao) {
    companion object {
        const val CH_NEW = "new_jobs"
        const val CH_MATCH = "high_match"
        const val CH_DIGEST = "digest"
        const val CH_REMIND = "reminders"
        const val EXTRA_ROUTE = "route"
    }

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        listOf(
            NotificationChannel(CH_NEW, context.getString(R.string.channel_new_jobs), NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CH_MATCH, context.getString(R.string.channel_high_match), NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CH_DIGEST, context.getString(R.string.channel_digest), NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CH_REMIND, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_HIGH),
        ).forEach { it.lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE; nm.createNotificationChannel(it) }
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Records in the in-app inbox and, if allowed, posts a system notification. Content is kept generic on the lock screen. */
    suspend fun notify(channel: String, kind: String, title: String, body: String, route: String, jobId: String? = null, appId: Long? = null, post: Boolean = true) {
        val id = dao.insert(NotificationEntity(at = System.currentTimeMillis(), kind = kind, title = title.take(200), body = body.take(1000), jobId = jobId, applicationId = appId))
        if (!post || !canPost()) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ROUTE, route)
        }
        val pi = PendingIntent.getActivity(context, id.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val public = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_monochrome).setContentTitle("CipherPath").setContentText(title).build()
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id.toInt(), n)
        } catch (_: SecurityException) { /* permission revoked between check and post */ }
    }
}
