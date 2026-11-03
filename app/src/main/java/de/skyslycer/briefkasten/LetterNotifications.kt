package de.skyslycer.briefkasten

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class LetterNotifications(private val context: Context, private val cutouts: CutoutImages) {
    fun createChannel() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "New letters", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "New Briefankündigung images saved to your archive"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            })
    }
    fun cancelAll() = NotificationManagerCompat.from(context).cancelAll()
    fun enabled(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        if (context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        return true
    }
    fun post(letter: Letter, picture: Boolean, test: Boolean = false, groupName: String? = null): Boolean {
        if (!enabled()) return false
        val manager = NotificationManagerCompat.from(context)
        val intent = Intent(context, MainActivity::class.java).putExtra("letter", letter.id)
            .setAction("letter.${letter.id}").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_letter)
            .setContentTitle(if (test) "Test notification" else "New letter saved").setContentText("Open Briefkasten to view your archive.").build()
        val builder = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_letter)
            .setContentTitle(groupName ?: if (test) "Test notification" else "Your post is on its way")
            .setContentIntent(pending).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public).setOnlyAlertOnce(!test)
        val bitmap = if (picture) runCatching { cutouts.bytes(letter).let { previewBitmap(it, 1600) } }.getOrNull() else null
        bitmap?.let { builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(it)) }
        return try { manager.notify(if (test) "notification-test" else letter.id, 1, builder.build()); true } catch (_: SecurityException) { false }
    }
    companion object { const val CHANNEL = "letters" }
}

fun previewBitmap(bytes: ByteArray, maxDimension: Int = 1600): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDimension) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}
