package io.github.backtraxe.anysave.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import io.github.backtraxe.anysave.MainActivity
import io.github.backtraxe.anysave.R
import io.github.backtraxe.anysave.model.MediaItem
import io.github.backtraxe.anysave.model.PostMedia
import io.github.backtraxe.anysave.model.SaveState
import io.github.backtraxe.anysave.telegram.TelegramClient
import io.github.backtraxe.anysave.telegram.TelegramConfig
import io.github.backtraxe.anysave.telegram.TelegramProtocol
import io.github.backtraxe.anysave.telegram.TelegramSettings
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Saves the user's selection to the gallery, then (when a bot is configured)
 * uploads the same files to Telegram as albums.
 *
 * Runs as a `dataSync` foreground service so the OS keeps the process alive
 * while a dozen images / a video are downloading. Progress is reported through
 * [SaveStateBus] and a single notification that switches from saving to
 * uploading. Downloads are [MediaSaver]'s job; uploads are [TelegramClient]'s.
 */
class SaveService : Service() {

    private data class SavedEntry(val item: MediaItem, val uri: Uri, val bytes: Long)

    private val handler = Handler(Looper.getMainLooper())
    private val doneCount = AtomicInteger()
    private val failedCount = AtomicInteger()
    private val savedEntries = Collections.synchronizedList(ArrayList<SavedEntry>())
    private val failedItems = Collections.synchronizedList(ArrayList<MediaItem>())

    @Volatile
    private var job: SaveJob? = null

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
        val decoded = SaveJobCodec.decode(intent?.getStringExtra(EXTRA_JOB))
        if (decoded == null || decoded.items.isEmpty()) {
            Log.w(TAG, "save start ignored: empty job")
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) {
            Log.i(TAG, "save already running, ignoring duplicate start")
            return START_NOT_STICKY
        }

        job = decoded
        running = true
        total = decoded.items.size
        doneCount.set(0)
        failedCount.set(0)
        savedEntries.clear()
        failedItems.clear()
        synchronized(lastFailedItems) { lastFailedItems.clear() }
        lastPost = decoded.post
        firstUri = null

        SaveStateBus.publish(SaveState.Running(total = total, done = 0, failed = 0))
        startForeground(NOTIFICATION_ID, buildProgressNotification(0))
        Log.i(TAG, "save start: $total item(s) to ${MediaNaming.ALBUM}")

        val saver = MediaSaver(this)
        val executor = Executors.newFixedThreadPool(WORKERS)
        decoded.items.forEach { item ->
            executor.execute { saveOne(saver, decoded.post, item) }
        }
        executor.shutdown()
        return START_NOT_STICKY
    }

    private fun saveOne(saver: MediaSaver, post: PostMedia, item: MediaItem) {
        val saved = saver.save(post, item)
        if (saved != null) {
            synchronized(savedEntries) {
                savedEntries.add(SavedEntry(item = item, uri = saved.uri, bytes = saved.bytes))
                if (firstUri == null) firstUri = saved.uri.toString()
            }
            doneCount.incrementAndGet()
        } else {
            failedCount.incrementAndGet()
            synchronized(failedItems) { failedItems += item }
            synchronized(lastFailedItems) { lastFailedItems += item }
        }
        handler.post { onItemFinished() }
    }

    private fun onItemFinished() {
        if (!running) return
        val saved = doneCount.get()
        val failed = failedCount.get()
        if (saved + failed >= total) {
            complete(saved, failed)
            return
        }
        SaveStateBus.publish(SaveState.Running(total = total, done = saved, failed = failed))
        notificationManager()?.notify(
            NOTIFICATION_ID,
            buildProgressNotification(saved + failed),
        )
    }

    // ---------------------------------------------------------------- telegram

    private fun complete(saved: Int, failed: Int) {
        val post = job?.post
        val config = TelegramSettings.load(this)
        val entries = synchronized(savedEntries) { ArrayList(savedEntries) }
        if (post == null || !config.effectiveAutoUpload || entries.isEmpty()) {
            finishRun(saved = saved, failed = failed, uploaded = 0, uploadFailed = 0)
            return
        }
        // Network work must never run on the main thread (upload can take
        // seconds per album); the callback re-enters the main thread to finish.
        Thread(
            { uploadToTelegram(post, config, entries, saved, failed) },
            "anysave-telegram",
        ).start()
    }

    private fun uploadToTelegram(
        post: PostMedia,
        config: TelegramConfig,
        entries: List<SavedEntry>,
        saved: Int,
        failed: Int,
    ) {
        val uploadable = ArrayList<TelegramClient.UploadAttachment>(entries.size)
        for (entry in entries) {
            val attachment = readAttachment(post, entry)
            if (attachment != null) uploadable += attachment
        }
        if (uploadable.isEmpty()) {
            Log.w(TAG, "telegram: nothing readable to upload")
            handler.post {
                finishRun(saved = saved, failed = failed, uploaded = 0, uploadFailed = entries.size)
            }
            return
        }

        SaveStateBus.publish(
            SaveState.Uploading(total = uploadable.size, uploaded = 0, failed = 0),
        )
        notificationManager()?.notify(
            NOTIFICATION_ID,
            buildUploadNotification(0, uploadable.size),
        )
        Log.i(TAG, "telegram: start upload: ${uploadable.size} item(s)")

        val client = TelegramClient(TelegramSettings.apiBase(this))
        val caption = TelegramProtocol.buildCaption(post.desc, post.author, post.sourceUrl)
        val summary = client.uploadMedia(
            token = config.botToken,
            chatId = config.chatId,
            caption = caption,
            attachments = uploadable,
        ) { uploaded, uploadFailed ->
            SaveStateBus.publish(
                SaveState.Uploading(
                    total = uploadable.size,
                    uploaded = uploaded,
                    failed = uploadFailed,
                ),
            )
            notificationManager()?.notify(
                NOTIFICATION_ID,
                buildUploadNotification(uploaded + uploadFailed, uploadable.size),
            )
        }
        Log.i(TAG, "telegram: finished uploaded=${summary.uploaded} failed=${summary.failed}")
        handler.post {
            finishRun(
                saved = saved,
                failed = failed,
                uploaded = summary.uploaded,
                uploadFailed = summary.failed,
            )
        }
    }

    private fun readAttachment(
        post: PostMedia,
        entry: SavedEntry,
    ): TelegramClient.UploadAttachment? {
        return try {
            val target = MediaNaming.target(post, entry.item)
            val bytes = contentResolver.openInputStream(entry.uri)?.use { it.readBytes() }
                ?: return null
            TelegramClient.UploadAttachment(
                fileName = target.displayName,
                mimeType = target.mimeType,
                bytes = bytes,
                isVideo = target.isVideo,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "telegram: cannot read ${entry.uri}: ${t.message}")
            null
        }
    }

    // ----------------------------------------------------------------- finishing

    private fun finishRun(saved: Int, failed: Int, uploaded: Int, uploadFailed: Int) {
        running = false
        SaveStateBus.publish(
            SaveState.Finished(
                total = total,
                saved = saved,
                failed = failed,
                albumName = MediaNaming.ALBUM,
                firstUri = firstUri,
                uploaded = uploaded,
                uploadFailed = uploadFailed,
                retryable = failed > 0 && synchronized(failedItems) { failedItems.isNotEmpty() },
            ),
        )
        notificationManager()?.notify(
            NOTIFICATION_ID,
            buildFinishedNotification(saved, failed, uploaded, uploadFailed),
        )
        // Detach (not remove): the finished summary stays in the shade.
        stopForeground(STOP_FOREGROUND_DETACH)
        Log.i(
            TAG,
            "run finished: saved=$saved failed=$failed uploaded=$uploaded uploadFailed=$uploadFailed",
        )
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

    private fun buildUploadNotification(processed: Int, uploadTotal: Int): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_uploading_title))
            .setContentText(getString(R.string.notification_uploading_text, processed, uploadTotal))
            .setProgress(uploadTotal, processed, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun buildFinishedNotification(
        saved: Int,
        failed: Int,
        uploaded: Int,
        uploadFailed: Int,
    ): Notification {
        val text = when {
            uploadFailed > 0 ->
                getString(R.string.notification_saved_upload_failed, saved, uploadFailed)

            uploaded > 0 -> getString(R.string.notification_saved_uploaded, saved, uploaded)
            failed > 0 -> getString(R.string.notification_saved_partial, saved, failed)
            else -> getString(R.string.notification_saved_text, saved)
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

        @Volatile
        private var lastPost: PostMedia? = null

        /** Items from the last run that failed; used by the finished-panel retry. */
        private val lastFailedItems = Collections.synchronizedList(ArrayList<MediaItem>())

        /** Re-saves the items that failed in the last run; false when nothing to retry. */
        fun retryLastFailed(context: Context): Boolean {
            val post = lastPost ?: return false
            val items = synchronized(lastFailedItems) { ArrayList(lastFailedItems) }
            if (items.isEmpty()) return false
            lastFailedItems.clear()
            start(context, post, items)
            return true
        }

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
