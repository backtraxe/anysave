package io.github.backtraxe.anysave.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import io.github.backtraxe.anysave.MainActivity
import io.github.backtraxe.anysave.R
import io.github.backtraxe.anysave.model.MediaItem
import io.github.backtraxe.anysave.model.PostMedia
import io.github.backtraxe.anysave.model.SaveState
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Saves the user's selection to the gallery in the background.
 *
 * Runs as a `dataSync` foreground service so the OS keeps the process alive
 * while a dozen images / a video are downloading, with a progress notification.
 * Downloads themselves are [MediaSaver]'s job; this class only schedules work,
 * aggregates progress and reports it through [SaveStateBus] + notifications.
 */
class SaveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val doneCount = AtomicInteger()
    private val failedCount = AtomicInteger()

    @Volatile
    private var running = false
    private var total = 0

    @Volatile
    private var firstUri: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val job = SaveJobCodec.decode(intent?.getStringExtra(EXTRA_JOB))
        if (job == null || job.items.isEmpty()) {
            Log.w(TAG, "save start ignored: empty job")
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) {
            Log.i(TAG, "save already running, ignoring duplicate start")
            return START_NOT_STICKY
        }

        running = true
        total = job.items.size
        doneCount.set(0)
        failedCount.set(0)
        firstUri = null

        SaveStateBus.publish(SaveState.Running(total = total, done = 0, failed = 0))
        startForeground(NOTIFICATION_ID, buildProgressNotification(0))
        Log.i(TAG, "save start: $total item(s) to ${MediaNaming.ALBUM}")

        val saver = MediaSaver(this)
        val executor = Executors.newFixedThreadPool(WORKERS)
        job.items.forEach { item ->
            executor.execute { saveOne(saver, job.post, item) }
        }
        executor.shutdown()
        return START_NOT_STICKY
    }

    private fun saveOne(saver: MediaSaver, post: PostMedia, item: MediaItem) {
        val uri = saver.save(post, item)
        if (uri != null) {
            synchronized(this) {
                if (firstUri == null) firstUri = uri.toString()
            }
            doneCount.incrementAndGet()
        } else {
            failedCount.incrementAndGet()
        }
        handler.post { onItemFinished() }
    }

    private fun onItemFinished() {
        if (!running) return
        val saved = doneCount.get()
        val failed = failedCount.get()
        if (saved + failed >= total) {
            finish(saved, failed)
            return
        }
        SaveStateBus.publish(SaveState.Running(total = total, done = saved, failed = failed))
        notificationManager()?.notify(
            NOTIFICATION_ID,
            buildProgressNotification(saved + failed),
        )
    }

    private fun finish(saved: Int, failed: Int) {
        running = false
        SaveStateBus.publish(
            SaveState.Finished(
                total = total,
                saved = saved,
                failed = failed,
                albumName = MediaNaming.ALBUM,
                firstUri = firstUri,
            ),
        )
        notificationManager()?.notify(NOTIFICATION_ID, buildFinishedNotification(saved, failed))
        // Detach (not remove): the finished summary stays in the shade.
        stopForeground(STOP_FOREGROUND_DETACH)
        Log.i(TAG, "save finished: $saved saved, $failed failed")
        stopSelf()
    }

    override fun onDestroy() {
        if (running) {
            running = false
            Log.w(TAG, "save service destroyed while running")
            SaveStateBus.publish(
                SaveState.Finished(
                    total = total,
                    saved = doneCount.get(),
                    failed = total - doneCount.get(),
                    albumName = MediaNaming.ALBUM,
                    firstUri = firstUri,
                ),
            )
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------ notification

    private fun createChannel() {
        val manager = notificationManager() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_saves),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_saves_desc)
                setShowBadge(false)
            },
        )
    }

    private fun buildProgressNotification(processed: Int): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_saving_title))
            .setContentText(getString(R.string.notification_saving_text, processed, total))
            .setProgress(total, processed, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun buildFinishedNotification(saved: Int, failed: Int): Notification {
        val text = if (failed > 0) {
            getString(R.string.notification_saved_partial, saved, failed)
        } else {
            getString(R.string.notification_saved_text, saved)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_saved_title))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notificationManager(): NotificationManager? =
        getSystemService(NotificationManager::class.java)

    companion object {
        private const val TAG = "AnySave"
        private const val CHANNEL_ID = "anysave_saves"
        private const val NOTIFICATION_ID = 0x51
        private const val EXTRA_JOB = "io.github.backtraxe.anysave.extra.SAVE_JOB"
        private const val WORKERS = 3

        /** Starts (or ignores, when already running) a save of [items] for [post]. */
        fun start(context: Context, post: PostMedia, items: List<MediaItem>) {
            if (items.isEmpty()) return
            val job = SaveJob(post, items)
            val intent = Intent(context, SaveService::class.java)
                .putExtra(EXTRA_JOB, SaveJobCodec.encode(job))
            context.startForegroundService(intent)
        }
    }
}
