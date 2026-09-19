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

/** خدمة أمامية تُبقي نقل الدروس إلى الخادم حيًّا وتعرض تقدّمه في إشعار */
class TransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tracker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val n = build("نقل الدروس إلى الخادم…", -1f)
        // API 34+: specialUse (بلا مهلة نظام)؛ قبله dataSync
        val type = if (Build.VERSION.SDK_INT >= 34 && org.murabbie.ahlalhadeeth.BuildConfig.DISTRIBUTION != "play") ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceCompat.startForeground(this, NOTIF_ID, n, type) else startForeground(NOTIF_ID, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { App.instance.transfer.cancel(); finish(); return START_NOT_STICKY }
        if (tracker?.isActive != true) tracker = scope.launch {
            val job = App.instance.transfer
            var last = ""
            while (true) {
                val st = job.state.value
                if (!st.running) { notifyDone(st); break }
                val text = "${ArabicText.arabicDigits(st.doneCount)} / ${ArabicText.arabicDigits(st.items.size)} — ${st.phase}" + (if (st.speedBps > 0) " — ${ArabicText.formatSize(st.speedBps)}/ث" else "")
                if (text != last) {
                    last = text
                    val p = if (st.total > 0) st.bytes.toFloat() / st.total else -1f
                    runCatching { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, build(text, p)) }
                }
                delay(1200)
            }
            finish()
        }
        return START_NOT_STICKY
    }

    private fun build(text: String, progress: Float): Notification {
        val open = PendingIntent.getActivity(this, 11, Intent(this, MainActivity::class.java).putExtra("open", "transfer"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 12, Intent(this, TransferService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, App.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("نقل الدروس إلى خادم البيانات")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, "إيقاف", stop)
        if (progress >= 0f) b.setProgress(1000, (progress * 1000).toInt().coerceIn(0, 1000), false) else b.setProgress(0, 0, true)
        return b.build()
    }

    private fun notifyDone(st: TransferJob.State) {
        val ok = st.items.count { it.done }
        val bad = st.items.count { it.error != null }
        val open = PendingIntent.getActivity(this, 13, Intent(this, MainActivity::class.java).putExtra("open", "transfer"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, App.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("اكتمل نقل الدروس إلى الخادم")
            .setContentText("نُقل ${ArabicText.arabicDigits(ok)} درس" + (if (bad > 0) "، وتعذر ${ArabicText.arabicDigits(bad)}" else "") + (if (st.published) " — ونُشر للجميع" else if (st.pendingCount > 0) " — بقي ${ArabicText.arabicDigits(st.pendingCount)} (افتح التطبيق للمتابعة)" else " — لا تنسَ «نشر التغييرات للجميع»"))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_DONE_ID, n) }
    }

    /** أندرويد ١٥: عند بلوغ حد النظام لنوع الخدمة يجب إيقافها فورًا وإلا أُغلق التطبيق بخطأ؛ النقل يستمر داخل التطبيق ما دام مفتوحًا ويُستأنف لاحقًا من القائمة المحفوظة */
    override fun onTimeout(startId: Int, fgsType: Int) { finish() }

    private fun finish() {
        tracker?.cancel()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    companion object {
        const val NOTIF_ID = 31
        const val NOTIF_DONE_ID = 32
        const val ACTION_STOP = "org.murabbie.ahlalhadeeth.transfer.STOP"

        fun start(context: Context) {
            val i = Intent(context, TransferService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
        }
    }
}
