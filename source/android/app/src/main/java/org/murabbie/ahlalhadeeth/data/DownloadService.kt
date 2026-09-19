package org.murabbie.ahlalhadeeth.data

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.murabbie.ahlalhadeeth.App
import org.murabbie.ahlalhadeeth.R
import org.murabbie.ahlalhadeeth.ui.MainActivity
import java.io.File

/** خدمة أمامية تنزّل الأشرطة واحدًا تلو الآخر مع الاستئناف. */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    @Volatile private var currentCode = 0
    @Volatile private var currentJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundCompat(buildNotification("بدء التنزيل…", 0, 0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (worker == null || worker?.isActive != true) {
            worker = scope.launch { loop() }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun isWifi(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private suspend fun loop() {
        val app = App.instance
        val userDb = app.userDb
        val dl = app.audioDownloads
        try {
            while (true) {
                val next = userDb.nextPendingDownload() ?: break
                if (app.settings.value.wifiOnlyDownloads && !isWifi()) {
                    userDb.setDownloadState(next.code, DownloadEntry.ERROR, "التنزيل عبر Wi-Fi فقط (غيّر ذلك من الإعدادات)")
                    continue
                }
                currentCode = next.code
                userDb.setDownloadState(next.code, DownloadEntry.RUNNING)
                val dest = File(next.dest)
                val job = scope.launch {
                    try {
                        var lastDbUpdate = 0L
                        NasHttp.download(next.url, dest, -1, attempts = 6) { bytes, total ->
                            dl.reportProgress(next.code, bytes, total)
                            val now = System.currentTimeMillis()
                            if (now - lastDbUpdate > 1500) {
                                lastDbUpdate = now
                                userDb.updateDownload(next.code, bytes, total, DownloadEntry.RUNNING)
                                updateNotification(next.title, bytes, total)
                            }
                        }
                        userDb.updateDownload(next.code, dest.length(), dest.length(), DownloadEntry.DONE)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        val d = userDb.download(next.code)
                        if (d != null && d.state == DownloadEntry.RUNNING) userDb.updateDownload(next.code, dest.length(), d.total, DownloadEntry.PAUSED)
                    } catch (e: Exception) {
                        userDb.updateDownload(next.code, dest.length(), -1, DownloadEntry.ERROR, e.message ?: "خطأ")
                    }
                }
                currentJob = job
                job.join()
                currentJob = null
                currentCode = 0
                dl.reportIdle()
            }
        } finally {
            dl.reportIdle()
            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun buildNotification(title: String, bytes: Long, total: Long): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).putExtra("open", "downloads"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, App.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("تنزيل الأشرطة")
            .setContentText(title)
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        if (total > 0) b.setProgress(100, ((bytes * 100) / total).toInt(), false) else b.setProgress(0, 0, true)
        return b.build()
    }

    private fun updateNotification(title: String, bytes: Long, total: Long) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        runCatching { nm.notify(NOTIF_ID, buildNotification(title, bytes, total)) }
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    /** أندرويد ١٥: حد ست ساعات لخدمات dataSync — عند بلوغه تُوقَف الخدمة الأمامية فورًا (وإلا أُغلق التطبيق بخطأ)؛ العمل يُستأنف عند فتح التطبيق */
    override fun onTimeout(startId: Int, fgsType: Int) {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    companion object {
        const val NOTIF_ID = 2001
        @Volatile private var instance: DownloadService? = null

        /** إلغاء التنزيل الجاري (code معيّن أو -1 للجاري أيًّا كان) */
        fun cancelCurrent(code: Int) {
            val s = instance ?: return
            if (code == -1 || s.currentCode == code) s.currentJob?.cancel()
        }
    }
}
