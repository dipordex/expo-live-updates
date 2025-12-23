package expo.modules.liveupdates

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import com.google.android.datatransport.runtime.scheduling.persistence.EventStoreModule_PackageNameFactory.packageName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.net.URL

private const val TAG = "LiveUpdatesManager"
private const val DEFAULT_MAX_PROGRESS = 100

object NotificationActionExtra {
    const val NOTIFICATION_ACTION = "notificationAction"
    const val NOTIFICATION_ID = "notificationId"
}

class LiveUpdatesManager(private val context: Context) {
    private val channelId = getChannelId(context)
    private val notificationManager = NotificationManagerCompat.from(context)
    private val idGenerator = IdGenerator(context)

    companion object {
        const val CHANNEL_ID = "live_timer_channel"
        const val NOTIFICATION_ID = 2001

        const val ACTION_START = "ACTION_START"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_LAP = "ACTION_LAP"
        const val ACTION_RESTART = "ACTION_RESTART"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var seconds = 0
    private var isRunning = false
    private var lapCount = 0

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                seconds++
//                updateNotification()
                handler.postDelayed(this, 1000)
            }
        }
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun startLiveUpdateNotification(
        state: LiveUpdateState,
        config: LiveUpdateConfig? = null
    ): Int? {
        val notificationId = idGenerator.generateNextId()

        if (notificationExists(notificationId)) {
            Log.w(
                TAG,
                "failed to start notification - notification with id $notificationId already exists",
            )
            return null
        }

        val notification = createNotification(state, notificationId, config)
        notificationManager.notify(notificationId, notification)
        NotificationStateEventEmitter.emit(notificationId, NotificationAction.STARTED)
        return notificationId
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun updateLiveUpdateNotification(
        notificationId: Int,
        state: LiveUpdateState,
        config: LiveUpdateConfig?,
    ) {
        if (!notificationExists(notificationId)) {
            Log.w(
                TAG,
                "failed to update notification - notification with id $notificationId does not exists",
            )
            return
        }

        val notification = createNotification(state, notificationId, config)
        notificationManager.notify(notificationId, notification)
        NotificationStateEventEmitter.emit(notificationId, NotificationAction.UPDATED)
    }

    fun stopNotification(notificationId: Int) {
        if (!notificationExists(notificationId)) {
            Log.w(
                TAG,
                "failed to stop notification - notification with id $notificationId does not exists",
            )
            return
        }

        notificationManager.cancel(notificationId)
        NotificationStateEventEmitter.emit(notificationId, NotificationAction.STOPPED)
    }

    private fun notificationExists(notificationId: Int): Boolean {
        return notificationManager.activeNotifications.any { it.id == notificationId }
    }

    private fun formatTime(seconds: Int): String {
        val min = seconds / 60
        val sec = seconds % 60
        return "%02d:%02d".format(min, sec)
    }

    private fun createNotification(
        state: LiveUpdateState,
        notificationId: Int,
        config: LiveUpdateConfig? = null,
    ): Notification {
        val remoteView = RemoteViews(context.packageName, R.layout.notification_stopwatch_timer)
        remoteView.setTextViewText(
            R.id.tvTimer,
            formatTime(seconds)
        )
        remoteView.setImageViewResource(
            R.id.ivPlay,
            if (isRunning) R.drawable.ic_pause else R.drawable.ic_play
        )
        remoteView.setViewVisibility(
            R.id.ivRestart,
            if (isRunning) View.GONE else View.VISIBLE
        )
        remoteView.setViewVisibility(
            R.id.ivFlag,
            if (isRunning) View.VISIBLE else View.GONE
        )

        remoteView.setViewVisibility(
            R.id.tvPause,
            if (isRunning) View.GONE else View.VISIBLE
        )

        if (lapCount > 0) {
            remoteView.setViewVisibility(R.id.tvLap, View.VISIBLE)
            remoteView.setTextViewText(R.id.tvLap, "Lap: $lapCount")
        }

        val notificationBuilder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(state.title)
            .setSmallIcon(android.R.drawable.star_on)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(remoteView)
            .setCustomBigContentView(remoteView)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setColorized(true)
            .setColor("#1B4332".toColorInt())


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
//      notificationBuilder.setShortCriticalText(state.shortCriticalText)
            notificationBuilder.setOngoing(true)
            notificationBuilder.setRequestPromotedOngoing(true)
        }


        setNotificationDeleteIntent(notificationId, notificationBuilder)
        setNotificationClickIntent(notificationId, config, notificationBuilder)

        return notificationBuilder.build()
    }

    private fun createProgressStyle(progress: LiveUpdateProgress): NotificationCompat.ProgressStyle {
        val points =
            progress.points?.map {
                val (position, color) = it
                val point = NotificationCompat.ProgressStyle.Point(position)
                color?.let { color ->
                    try {
                        point.setColor(color.toColorInt())
                    } catch (e: IllegalArgumentException) {
                        Log.e(TAG, getInvalidColorFormatErrorMessage(color), e)
                    }
                }
                point
            }

        val segments =
            progress.segments?.map {
                val (length, color) = it
                val segment = NotificationCompat.ProgressStyle.Segment(length)
                color?.let { color ->
                    try {
                        segment.setColor(color.toColorInt())
                    } catch (e: IllegalArgumentException) {
                        Log.e(TAG, getInvalidColorFormatErrorMessage(color), e)
                    }
                }
                segment
            } ?: listOf(
                NotificationCompat.ProgressStyle.Segment(
                    progress.max ?: DEFAULT_MAX_PROGRESS
                )
            )

        val style = NotificationCompat.ProgressStyle().setProgressSegments(segments)

        points?.let { style.setProgressPoints(it) }
        progress.progress?.let { style.setProgress(it) }

        return style
    }

    private fun getInvalidColorFormatErrorMessage(color: String): String {
        return "Invalid color format: $color"
    }

    private fun getBitmapFromImage(image: LiveUpdateImage): Bitmap? {
        try {
            val (url, isRemote) = image
            return if (isRemote) getBitmapFromRemoteUrl(url) else getBitmapFromLocalUrl(url)
        } catch (e: Exception) {
            Log.w(TAG, "Creating bitmap from url failed.", e)
            return null
        }
    }

    private fun getBitmapFromLocalUrl(url: String): Bitmap {
        val fileUrl = url.replace("file://", "")
        val file = File(fileUrl)

        if (file.exists()) {
            return BitmapFactory.decodeFile(file.absolutePath)
        } else {
            throw Exception("FileCheck could not find file at $fileUrl.")
        }
    }

    private fun getBitmapFromRemoteUrl(url: String): Bitmap {
        val parsedUrl = URL(url)
        return BitmapFactory.decodeStream(parsedUrl.openConnection().getInputStream())
    }

    private fun setNotificationDeleteIntent(
        notificationId: Int,
        notificationBuilder: NotificationCompat.Builder,
    ) {
        val deleteIntent = Intent(context, NotificationDismissedReceiver::class.java)
        deleteIntent.putExtra(NotificationActionExtra.NOTIFICATION_ID, notificationId)
        val deletePendingIntent =
            PendingIntent.getBroadcast(
                context,
                notificationId,
                deleteIntent,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        notificationBuilder.setDeleteIntent(deletePendingIntent)
    }

    private fun setNotificationClickIntent(
        notificationId: Int,
        config: LiveUpdateConfig?,
        notificationBuilder: NotificationCompat.Builder,
    ) {
        val clickIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)

        clickIntent?.apply {
            action = Intent.ACTION_VIEW

            val scheme = getScheme(context)

            config?.deepLinkUrl?.let { deepLink ->
                scheme?.let { data = "$scheme://${deepLink.removePrefix("/")}".toUri() }
                    ?: run {
                        Log.w(
                            TAG,
                            "deepLinkUrl property ignored. Please configure withChannelConfig plugin with scheme in app.config.ts to enable managing Live Update deeplinks.",
                        )
                    }
            }

            putExtra(NotificationActionExtra.NOTIFICATION_ACTION, NotificationAction.CLICKED)
            putExtra(NotificationActionExtra.NOTIFICATION_ID, notificationId)
        }

        val clickPendingIntent =
            PendingIntent.getActivity(
                context,
                notificationId,
                clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        notificationBuilder.setContentIntent(clickPendingIntent)
    }
}
