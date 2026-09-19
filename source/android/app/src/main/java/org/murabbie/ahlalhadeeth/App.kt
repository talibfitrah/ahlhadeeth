package org.murabbie.ahlalhadeeth

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import kotlinx.coroutines.launch
import org.murabbie.ahlalhadeeth.data.AudioDownloads
import org.murabbie.ahlalhadeeth.data.DataManager
import org.murabbie.ahlalhadeeth.data.Settings
import org.murabbie.ahlalhadeeth.data.UserDb
import org.murabbie.ahlalhadeeth.player.PlayerHolder

class App : Application() {

    lateinit var settings: Settings
        private set
    lateinit var userDb: UserDb
        private set
    lateinit var dataManager: DataManager
        private set
    lateinit var audioDownloads: AudioDownloads
        private set
    lateinit var player: PlayerHolder
        private set
    lateinit var sharedSync: org.murabbie.ahlalhadeeth.data.SharedSync
        private set
    /** الفهرسة والتفريغ التلقائيان (دفعات في الخلفية) */
    lateinit var autoIndex: org.murabbie.ahlalhadeeth.data.AutoIndexJob
        private set
    /** نقل دروس يوتيوب إلى خادم البيانات */
    lateinit var transfer: org.murabbie.ahlalhadeeth.data.TransferJob
        private set

    /** عميل Gemini إن ضبط المشرف العام مفتاحه */
    fun gemini(): org.murabbie.ahlalhadeeth.data.GeminiClient? = sharedSync.geminiKey()?.let { org.murabbie.ahlalhadeeth.data.GeminiClient(it) }

    /**
     * تحميل وسائط درس إلى ملف مؤقت لإرساله إلى Gemini: رابط الخادم أو رابط مباشر (تنزيل متوازٍ)، أو يوتيوب
     * (استخراج مسار الصوت على الهاتف ثم تنزيله متوازيًا)، أو ملف على الجهاز. يعيد (الملف، النوع، المدة بالمللي ثانية أو 0).
     */
    val mediaLoader: org.murabbie.ahlalhadeeth.data.MediaLoader = { ch, onStatus, onProgress ->
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // التقدّم: التنزيل حتى ٩٠٪ من هذه المرحلة، والتهيئة الباقي
            val dl: (Long, Long) -> Unit = { b, t -> if (t > 0) onProgress(0.9f * (b.toFloat() / t).coerceIn(0f, 1f)) }
            val uri = ch.mediaUri
            val dir = java.io.File(cacheDir, "autoindex").apply { mkdirs() }
            val fmt = org.murabbie.ahlalhadeeth.data.ArabicText::formatSize
            val ytId = ch.youtubeId
            val result: Pair<java.io.File, String>? = if (ytId != null) {
                onStatus("استخراج مسار الصوت من يوتيوب…")
                val ex = org.murabbie.ahlalhadeeth.data.YouTubeMedia.extract(ytId)
                var pick = ex.audio ?: ex.video ?: return@withContext null
                val out = java.io.File(dir, "m" + (-ch.code) + "." + pick.ext)
                val refresh: suspend () -> String = {
                    val ex2 = org.murabbie.ahlalhadeeth.data.YouTubeMedia.extract(ytId)
                    val p2 = ex2.audio ?: ex2.video
                    if (p2 != null && p2.ext == pick.ext) pick = p2
                    pick.url
                }
                org.murabbie.ahlalhadeeth.data.ParallelDownload.download(pick.url, refresh, out, "Mozilla/5.0", onProgress = { b, t -> dl(b, t); onStatus("تنزيل الصوت من يوتيوب ${fmt(b)}" + (if (t > 0) " / ${fmt(t)}" else "")) })
                out to pick.mime
            } else if (uri.startsWith("http", true)) {
                val ext = uri.substringBefore('?').substringAfterLast('.', "").lowercase().takeIf { it.length in 2..4 && it.all { c -> c.isLetterOrDigit() } } ?: "bin"
                val out = java.io.File(dir, "m" + (-ch.code) + "." + ext)
                try {
                    org.murabbie.ahlalhadeeth.data.ParallelDownload.download(uri, { uri }, out, "AhlAlhadeeth-Android/1.0", onProgress = { b, t -> dl(b, t); onStatus("تنزيل ملف الدرس ${fmt(b)}" + (if (t > 0) " / ${fmt(t)}" else "")) })
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    out.delete()
                    val size = org.murabbie.ahlalhadeeth.data.NasHttp.remoteSize(uri)
                    org.murabbie.ahlalhadeeth.data.NasHttp.download(uri, out, size) { have, total -> dl(have, total); onStatus("تنزيل ملف الدرس ${fmt(have)}" + (if (total > 0) " / ${fmt(total)}" else "")) }
                }
                out to org.murabbie.ahlalhadeeth.data.AutoIndexer.mimeOf(uri)
            } else {
                val u = android.net.Uri.parse(uri)
                val out = java.io.File(dir, "m" + (-ch.code) + ".bin")
                val input = contentResolver.openInputStream(u) ?: return@withContext null
                input.use { i -> out.outputStream().use { i.copyTo(it) } }
                out to (contentResolver.getType(u) ?: org.murabbie.ahlalhadeeth.data.AutoIndexer.mimeOf(uri))
            }
            var (file, mime) = result ?: return@withContext null
            onProgress(0.9f)
            // المدة (لتقسيم الدروس الطويلة نوافذ عند التفريغ)
            val duration = runCatching {
                val r = android.media.MediaMetadataRetriever()
                try { r.setDataSource(file.absolutePath); r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L } finally { runCatching { r.release() } }
            }.getOrDefault(0L)
            // تهيئة الملف لـ Gemini: WebM الصوتي (كما تُنقل دروس يوتيوب) يُرسل بنوع audio/webm لا video/webm (وإلا رُفضت معالجته: FAILED)،
            // ومسار AAC في حاويات MP4/M4A يُستخرج إلى ملف ADTS صغير بلا إعادة ترميز
            val prepared = org.murabbie.ahlalhadeeth.data.MediaPrep.prepare(file, mime, dir, onStatus)
            file = prepared.first; mime = prepared.second
            onProgress(1f)
            org.murabbie.ahlalhadeeth.data.LoadedMedia(file, mime, duration)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        org.murabbie.ahlalhadeeth.data.CrashLog.install(this, org.murabbie.ahlalhadeeth.BuildConfig.VERSION_NAME)
        settings = Settings(this)
        userDb = UserDb(this)
        sharedSync = org.murabbie.ahlalhadeeth.data.SharedSync(this, userDb.content)
        autoIndex = org.murabbie.ahlalhadeeth.data.AutoIndexJob(userDb.content, { gemini() }, mediaLoader, filesDir,
            onRunningChanged = { running -> if (running) runCatching { org.murabbie.ahlalhadeeth.data.AutoIndexService.start(this) } })
        transfer = org.murabbie.ahlalhadeeth.data.TransferJob(userDb.content, sharedSync, cacheDir, filesDir,
            onRunningChanged = { running -> if (running) runCatching { org.murabbie.ahlalhadeeth.data.TransferService.start(this) } },
            onFinished = { if (settings.value.autoCleanCache) runCatching { org.murabbie.ahlalhadeeth.data.CacheCleaner.autoClean(this, transfer.keepFiles(), false, autoIndex.state.value.running) } })
        dataManager = DataManager(this, settings)
        audioDownloads = AudioDownloads(this, settings, userDb)
        player = PlayerHolder(this)
        createChannels()
        // تنظيف الذاكرة المؤقتة تلقائيًا عند البدء (ملفات نقل وتفريغ لم تعد لازمة، ومخلفات WebView)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            kotlinx.coroutines.delay(3000)
            if (settings.value.autoCleanCache) runCatching { org.murabbie.ahlalhadeeth.data.CacheCleaner.autoClean(this@App, transfer.keepFiles(), transfer.state.value.running, autoIndex.state.value.running) }
        }
        // خوادم الصوت المعروفة (المختار أولًا) لمعترض التبديل التلقائي عند تعذر ملف على خادم
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            settings.state.collect { s ->
                val list = s.audioServers.map { it.url }
                val sel = s.audioBaseUrl
                org.murabbie.ahlalhadeeth.data.NasHttp.audioServers = (listOf(sel) + list.filter { it != sel }).distinct()
            }
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PLAYER, getString(R.string.notification_channel_player), NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DOWNLOADS, getString(R.string.notification_channel_downloads), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        const val CHANNEL_PLAYER = "player"
        const val CHANNEL_DOWNLOADS = "downloads"
        lateinit var instance: App
            private set
    }
}
