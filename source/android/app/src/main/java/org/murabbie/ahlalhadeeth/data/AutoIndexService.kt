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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.murabbie.ahlalhadeeth.App
import org.murabbie.ahlalhadeeth.R
import org.murabbie.ahlalhadeeth.ui.MainActivity

/** خدمة أمامية تُبقي التفريغ التلقائي للدروس حيًّا عند مغادرة التطبيق وتعرض نسبته في إشعار */
class AutoIndexService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tracker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val n = build("تفريغ الدروس…", -1f)
        val type = if (Build.VERSION.SDK_INT >= 34 && org.murabbie.ahlalhadeeth.BuildConfig.DISTRIBUTION != "play") ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceCompat.startForeground(this, NOTIF_ID, n, type) else startForeground(NOTIF_ID, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { App.instance.autoIndex.cancel(); finish(); return START_NOT_STICKY }
        if (tracker?.isActive != true) tracker = scope.launch {
            val job = App.instance.autoIndex
            var last = ""
            while (true) {
                val st = job.state.value
                if (!st.running) { notifyDone(st); break }
                val text = autoIndexSummary(st)
                if (text != last) {
                    last = text
                    runCatching { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, build(text, st.overall)) }
                }
                delay(1000)
            }
            finish()
        }
        return START_NOT_STICKY
    }

    private fun build(text: String, progress: Float): Notification {
        val open = PendingIntent.getActivity(this, 21, Intent(this, MainActivity::class.java).putExtra("open", "autoindex"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 22, Intent(this, AutoIndexService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, App.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("تفريغ الدروس بالذكاء الاصطناعي")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, "إيقاف", stop)
        if (progress >= 0f) b.setProgress(1000, (progress * 1000).toInt().coerceIn(0, 1000), false) else b.setProgress(0, 0, true)
        return b.build()
    }

    private fun notifyDone(st: AutoIndexJob.State) {
        val open = PendingIntent.getActivity(this, 23, Intent(this, MainActivity::class.java).putExtra("open", "autoindex"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = "فُرّغ ${ArabicText.arabicDigits(st.okCount)} درس" + (if (st.errCount > 0) "، وتعذر ${ArabicText.arabicDigits(st.errCount)}" else "") +
            (if (st.quotaStop) " — توقف لنفاد حصة Gemini اليومية، بقي ${ArabicText.arabicDigits(st.pendingCount)} (تابع غدًا من التطبيق)" else if (st.pendingCount > 0) " — بقي ${ArabicText.arabicDigits(st.pendingCount)}" else " — لا تنسَ «نشر التغييرات للجميع»")
        val n = NotificationCompat.Builder(this, App.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (st.quotaStop) "توقف التفريغ مؤقتًا" else "انتهى تفريغ الدروس")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_DONE_ID, n) }
    }

    /** أندرويد ١٥: عند بلوغ حد النظام لنوع الخدمة يجب إيقافها فورًا؛ التفريغ يستمر داخل التطبيق ما دام مفتوحًا ويُستأنف من الطابور المحفوظ */
    override fun onTimeout(startId: Int, fgsType: Int) { finish() }

    private fun finish() {
        tracker?.cancel()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    companion object {
        const val NOTIF_ID = 41
        const val NOTIF_DONE_ID = 42
        const val ACTION_STOP = "org.murabbie.ahlalhadeeth.autoindex.STOP"

        fun start(context: Context) {
            val i = Intent(context, AutoIndexService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
        }
    }
}

/** سطر ملخّص لحالة التفريغ التلقائي (للشريط والإشعار): الدرس الجاري ونسبته */
fun autoIndexSummary(st: AutoIndexJob.State): String {
    val n = st.items.size
    if (st.running) {
        val cur = st.items.getOrNull(st.current)
        val pct = ArabicText.arabicDigits((st.progress * 100).toInt()) + "٪"
        val head = "الدرس ${ArabicText.arabicDigits(st.doneCount + 1)} / ${ArabicText.arabicDigits(n)} — $pct"
        return if (cur != null && st.status.isNotBlank()) "$head — ${st.status}" else head
    }
    return "فُرّغ ${ArabicText.arabicDigits(st.okCount)} / ${ArabicText.arabicDigits(n)}" + (if (st.errCount > 0) "، تعذر ${ArabicText.arabicDigits(st.errCount)}" else "") +
        (if (st.quotaStop) " — نفدت حصة Gemini اليومية، بقي ${ArabicText.arabicDigits(st.pendingCount)}" else if (st.pendingCount > 0) "، بقي ${ArabicText.arabicDigits(st.pendingCount)}" else "")
}
