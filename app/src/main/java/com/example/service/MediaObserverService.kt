package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AppPreferences
import com.example.engine.MediaMoverEngine
import com.example.model.MoveLogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class MediaObserverService : Service() {

    companion object {
        private const val TAG = "MediaObserverService"
        const val ACTION_START = "com.example.photovault.START"
        const val ACTION_STOP = "com.example.photovault.STOP"
        const val ACTION_MANUAL_SWEEP = "com.example.photovault.MANUAL_SWEEP"

        private const val NOTIFICATION_ID = 9001
        private const val MOVE_ALERT_NOTIFICATION_ID = 9002
        private const val CHANNEL_ID = "photo_vault_protection"
        private const val CHANNEL_ALERTS_ID = "photo_vault_alerts"

        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        fun startService(context: Context) {
            val intent = Intent(context, MediaObserverService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, MediaObserverService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun triggerManualSweep(context: Context) {
            val intent = Intent(context, MediaObserverService::class.java).apply {
                action = ACTION_MANUAL_SWEEP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var appPreferences: AppPreferences
    private val handler = Handler(Looper.getMainLooper())

    private var imageObserver: ContentObserver? = null
    private var videoObserver: ContentObserver? = null
    private val activeFileObservers = mutableListOf<FileObserver>()
    private var debounceJob: Job? = null

    // Track recently processed files to prevent duplicate operations
    private val recentlyProcessedFiles = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        appPreferences = AppPreferences(applicationContext)
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_STOP -> {
                Log.d(TAG, "Stop action received")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_MANUAL_SWEEP -> {
                runManualSweep()
            }
            ACTION_START -> {
                startForegroundSafely()
                _isRunning.value = true
                setupObservers()
            }
        }

        return START_STICKY
    }

    private fun startForegroundSafely() {
        val notification = buildForegroundNotification("Shielding Camera & Screenshots in real time")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // Ongoing Protection Channel
            val ongoingChannel = NotificationChannel(
                CHANNEL_ID,
                "Protection Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active background camera & screenshot protection"
                setShowBadge(false)
            }
            manager?.createNotificationChannel(ongoingChannel)

            // Alert Notification Channel
            val alertChannel = NotificationChannel(
                CHANNEL_ALERTS_ID,
                "File Move Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when a media capture is safely moved to vault"
                setShowBadge(true)
            }
            manager?.createNotificationChannel(alertChannel)
        }
    }

    private fun buildForegroundNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MediaObserverService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PhotoVault: Cloud Auto-Sync Shield")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pendingOpenApp)
            .addAction(0, "Pause Shield", pendingStop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showMoveAlert(entry: MoveLogEntry) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val alert = NotificationCompat.Builder(this, CHANNEL_ALERTS_ID)
            .setContentTitle("Photo Protected from Cloud Sync")
            .setContentText("Moved ${entry.fileName} to private vault")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager?.notify(MOVE_ALERT_NOTIFICATION_ID, alert)
    }

    private fun setupObservers() {
        tearDownObservers()

        serviceScope.launch {
            val monitored = appPreferences.monitoredFoldersFlow.first()
            val targetDirStr = appPreferences.targetDirectoryFlow.first()
            val targetDir = File(targetDirStr)
            val hideNoMedia = appPreferences.hideFromGalleryFlow.first()

            // Prepare target folder & .nomedia upfront
            MediaMoverEngine.prepareTargetDirectory(targetDir, hideNoMedia)

            // Setup FileObservers on each watched folder
            monitored.forEach { folderRelPath ->
                val folder = MediaMoverEngine.resolveFolderPath(folderRelPath)
                if (!folder.exists()) {
                    folder.mkdirs()
                }
                attachFileObserver(folder)
            }
        }

        // Setup ContentObserver on MediaStore
        registerContentObservers()
    }

    private fun registerContentObservers() {
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                triggerDebouncedScan()
            }
        }

        imageObserver = observer
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer
        )

        val vObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                triggerDebouncedScan()
            }
        }
        videoObserver = vObserver
        contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            vObserver
        )
    }

    private fun attachFileObserver(folder: File) {
        try {
            val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
            val observer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                object : FileObserver(folder, mask) {
                    override fun onEvent(event: Int, path: String?) {
                        if (path != null) {
                            handleFileEvent(File(folder, path))
                        }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                object : FileObserver(folder.absolutePath, mask) {
                    override fun onEvent(event: Int, path: String?) {
                        if (path != null) {
                            handleFileEvent(File(folder, path))
                        }
                    }
                }
            }
            observer.startWatching()
            activeFileObservers.add(observer)
            Log.d(TAG, "Attached FileObserver to ${folder.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not start FileObserver for ${folder.absolutePath}: ${e.message}")
        }
    }

    private fun handleFileEvent(file: File) {
        if (!MediaMoverEngine.isMediaFile(file)) return

        serviceScope.launch {
            processSingleFile(file)
        }
    }

    private fun triggerDebouncedScan() {
        debounceJob?.cancel()
        debounceJob = serviceScope.launch {
            delay(400) // Brief delay to let camera finish writing file
            scanAndProcessRecentFiles()
        }
    }

    private suspend fun scanAndProcessRecentFiles() {
        val monitoredFolders = appPreferences.monitoredFoldersFlow.first()
        val targetDir = File(appPreferences.targetDirectoryFlow.first())
        val hideNoMedia = appPreferences.hideFromGalleryFlow.first()
        val notifyOnMove = appPreferences.notifyOnMoveFlow.first()

        monitoredFolders.forEach { relPath ->
            val folder = MediaMoverEngine.resolveFolderPath(relPath)
            if (folder.exists() && folder.isDirectory) {
                val files = folder.listFiles() ?: emptyArray()
                for (file in files) {
                    if (MediaMoverEngine.isMediaFile(file)) {
                        processSingleFile(file, targetDir, hideNoMedia, notifyOnMove)
                    }
                }
            }
        }
    }

    private suspend fun processSingleFile(
        file: File,
        targetDirOverride: File? = null,
        hideNoMediaOverride: Boolean? = null,
        notifyOnMoveOverride: Boolean? = null
    ) {
        val absPath = file.absolutePath
        synchronized(recentlyProcessedFiles) {
            if (recentlyProcessedFiles.contains(absPath)) return
            recentlyProcessedFiles.add(absPath)
        }

        val targetDir = targetDirOverride ?: File(appPreferences.targetDirectoryFlow.first())
        val hideNoMedia = hideNoMediaOverride ?: appPreferences.hideFromGalleryFlow.first()
        val notifyOnMove = notifyOnMoveOverride ?: appPreferences.notifyOnMoveFlow.first()

        val log = MediaMoverEngine.moveMediaFile(
            context = applicationContext,
            sourceFile = file,
            baseTargetDir = targetDir,
            hideWithNoMedia = hideNoMedia,
            waitIfWriting = true
        )

        appPreferences.addLog(log)

        if (log.success && notifyOnMove) {
            showMoveAlert(log)
        }

        // Cleanup processed files cache after 15 seconds
        serviceScope.launch {
            delay(15000)
            synchronized(recentlyProcessedFiles) {
                recentlyProcessedFiles.remove(absPath)
            }
        }
    }

    private fun runManualSweep() {
        serviceScope.launch {
            val monitored = appPreferences.monitoredFoldersFlow.first()
            val targetDir = File(appPreferences.targetDirectoryFlow.first())
            val hideNoMedia = appPreferences.hideFromGalleryFlow.first()
            val notifyOnMove = appPreferences.notifyOnMoveFlow.first()

            val sourceFolders = monitored.map { MediaMoverEngine.resolveFolderPath(it) }
            val results = MediaMoverEngine.runManualScanAndMove(
                context = applicationContext,
                sourceFolders = sourceFolders,
                baseTargetDir = targetDir,
                hideWithNoMedia = hideNoMedia
            )

            results.forEach { log ->
                appPreferences.addLog(log)
            }

            if (notifyOnMove && results.isNotEmpty()) {
                val firstSuccess = results.firstOrNull { it.success }
                if (firstSuccess != null) {
                    showMoveAlert(firstSuccess)
                }
            }
        }
    }

    private fun tearDownObservers() {
        imageObserver?.let {
            contentResolver.unregisterContentObserver(it)
            imageObserver = null
        }
        videoObserver?.let {
            contentResolver.unregisterContentObserver(it)
            videoObserver = null
        }
        activeFileObservers.forEach {
            try {
                it.stopWatching()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping file observer: ${e.message}")
            }
        }
        activeFileObservers.clear()
    }

    override fun onDestroy() {
        _isRunning.value = false
        tearDownObservers()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
