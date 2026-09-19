package org.murabbie.ahlalhadeeth.data

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.murabbie.ahlalhadeeth.App
import org.murabbie.ahlalhadeeth.R
import org.murabbie.ahlalhadeeth.ui.MainActivity

/**
 * خدمة أمامية تتولى تنزيل قاعدة البيانات تلقائيًا عند أول تشغيل:
 * تُبقي العملية حيّة ولو غادر المستخدم التطبيق، وتعيد المحاولة تلقائيًا عند فشل الشبكة،
 * وتُخبر المستخدم بإشعار عند الاكتمال.
 */
class DataDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var worker: Job? = null
    private var progressJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat(buildNotification("تجهيز البيانات لأول مرة…", -1f, false))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            App.instance.dataManager.pauseAuto()
            finish()
            return START_NOT_STICKY
        }
        if (worker?.isActive != true) {
            worker = scope.launch { run() }
        }
        if (progressJob?.isActive != true) {
            progressJob = scope.launch { trackProgress() }
        }
        return START_NOT_STICKY
    }

    private suspend fun run() {
        val dm = App.instance.dataManager
        var downloadedNow = false
        while (true) {
            if (dm.userPaused.value) break
            when (val st = dm.state.value) {
                is DataState.Ready -> {
                    if (downloadedNow) notifyDone()
                    break
                }
                is DataState.Checking -> {
                    delay(500)
                    continue
                }
                is DataState.NotInstalled, is DataState.Error -> {
                    if (st is DataState.Error && !st.retryable) break
                    downloadedNow = true
                    dm.startDownload()
                    // انتظار حالة نهائية
                    val terminal = dm.state.first { it is DataState.Ready || it is DataState.Error || it is DataState.NotInstalled }
                    if (terminal is DataState.Error) {
                        if (dm.userPaused.value) break
                        dm.failures.value += 1
                        val wait = minOf(120, 10 shl minOf(dm.failures.value - 1, 4))  // ١٠، ٢٠، ٤٠، ٨٠، ١٢٠ ثانية
                        for (sec in wait downTo 1) {
                            if (dm.userPaused.value) break
                            dm.setRetryCountdown(sec)
                            delay(1000)
                        }
                        dm.setRetryCountdown(0)
                        if (dm.userPaused.value) break
                        continue
                    }
                    if (terminal is DataState.NotInstalled) break // أُلغي
                }
                is DataState.Downloading, is DataState.Installing -> {
                    // تنزيل بدأه شيء آخر: ننتظر
                    dm.state.first { it !is DataState.Downloading && it !is DataState.Installing }
                }
            }
        }
        finish()
    }

    private suspend fun trackProgress() {
        val dm = App.instance.dataManager
        var lastText = ""
        while (true) {
            val st = dm.state.value
            val (text, progress) = when (st) {
                is DataState.Downloading -> {
                    val p = if (st.total > 0) st.bytes.toFloat() / st.total else -1f
                    val t = if (st.total > 0) "تنزيل البيانات ${ArabicText.formatSize(st.bytes)} من ${ArabicText.formatSize(st.total)}" else st.message
                    t to p
                }
                is DataState.Installing -> st.message to st.progress
                is DataState.Error -> {
                    val c = dm.retryCountdown.value
                    (if (c > 0) "تعذر الاتصال؛ إعادة المحاولة خلال ${ArabicText.arabicDigits(c)} ث" else "تعذر الاتصال؛ سيُعاد التنزيل تلقائيًا") to -1f
                }
                else -> "تجهيز البيانات…" to -1f
            }
            if (text != lastText) {
                lastText = text
                runCatching { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(text, progress, true)) }
            }
            delay(1500)
        }
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(text: String, progress: Float, withStop: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, App.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("أهل الحديث والأثر — تجهيز البيانات")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        if (progress >= 0f) b.setProgress(1000, (progress * 1000).toInt().coerceIn(0, 1000), false) else b.setProgress(0, 0, true)
        if (withStop) {
            val stop = PendingIntent.getService(this, 2, Intent(this, DataDownloadService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, "إيقاف مؤقت", stop)
        }
        return b.build()
    }

    private fun notifyDone() {
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, App.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("أهل الحديث والأثر")
            .setContentText("اكتمل تجهيز البيانات — التطبيق جاهز للاستخدام")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_DONE_ID, n) }
    }

    private fun finish() {
        progressJob?.cancel()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** أندرويد ١٥: حد ست ساعات لخدمات dataSync — عند بلوغه تُوقَف الخدمة الأمامية فورًا (وإلا أُغلق التطبيق بخطأ)؛ العمل يُستأنف عند فتح التطبيق */
    override fun onTimeout(startId: Int, fgsType: Int) {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    companion object {
        const val NOTIF_ID = 3001
        const val NOTIF_DONE_ID = 3002
        const val ACTION_STOP = "org.murabbie.ahlalhadeeth.DATA_STOP"

        fun start(context: Context) {
            val intent = Intent(context, DataDownloadService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            }.onFailure {
                // إن مُنع بدء الخدمة من الخلفية نبدأ التنزيل داخل العملية مباشرة
                App.instance.dataManager.startDownload()
            }
        }
    }
}
