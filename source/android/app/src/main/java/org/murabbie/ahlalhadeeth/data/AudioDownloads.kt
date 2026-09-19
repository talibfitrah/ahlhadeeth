package org.murabbie.ahlalhadeeth.data

import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** واجهة مدير تنزيل الأشرطة (كما في نافذة التنزيلات في نسخة ويندوز) */
class AudioDownloads(private val context: Context, private val settings: Settings, private val userDb: UserDb) {

    val audioSource = AudioSource(context, settings)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** تقدّم التنزيل الجاري: code -> (bytes, total) */
    private val _progress = MutableStateFlow<Map<Int, Pair<Long, Long>>>(emptyMap())
    val progress: StateFlow<Map<Int, Pair<Long, Long>>> = _progress

    private val _activeCode = MutableStateFlow(0)
    val activeCode: StateFlow<Int> = _activeCode

    fun enqueue(chapters: List<Chapter>) {
        scope.launch {
            for (ch in chapters) {
                if (ch.isUser && audioSource.remoteUrl(ch).isBlank()) continue // ملف محلي أصلًا
                val dest = audioSource.localFile(ch)
                if (dest.exists() && dest.length() > 0) {
                    val existing = userDb.download(ch.code)
                    if (existing == null) userDb.addDownload(ch.code, "${ch.sheekhName} — ${ch.bookName} — ${ch.displayTitle} (${ch.fileName})", audioSource.remoteUrl(ch), dest.absolutePath)
                    userDb.updateDownload(ch.code, dest.length(), dest.length(), DownloadEntry.DONE)
                    continue
                }
                userDb.addDownload(ch.code, "${ch.sheekhName} — ${ch.bookName} — ${ch.displayTitle} (${ch.fileName})", audioSource.remoteUrl(ch), dest.absolutePath)
            }
            startService()
        }
    }

    fun startService() {
        val intent = Intent(context, DownloadService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
    }

    fun pause(code: Int) {
        scope.launch {
            userDb.setDownloadState(code, DownloadEntry.PAUSED)
            DownloadService.cancelCurrent(code)
        }
    }

    fun pauseAll() {
        scope.launch {
            userDb.setAllDownloadsState(DownloadEntry.PENDING, DownloadEntry.PAUSED)
            DownloadService.cancelCurrent(-1)
            userDb.setAllDownloadsState(DownloadEntry.RUNNING, DownloadEntry.PAUSED)
        }
    }

    fun resume(code: Int) {
        scope.launch {
            userDb.setDownloadState(code, DownloadEntry.PENDING)
            startService()
        }
    }

    fun resumeAll() {
        scope.launch {
            userDb.setAllDownloadsState(DownloadEntry.PAUSED, DownloadEntry.PENDING)
            userDb.setAllDownloadsState(DownloadEntry.ERROR, DownloadEntry.PENDING)
            startService()
        }
    }

    fun remove(code: Int, deleteFile: Boolean) {
        scope.launch {
            val d = userDb.download(code)
            DownloadService.cancelCurrent(code)
            if (deleteFile && d != null) File(d.dest).delete()
            userDb.removeDownload(code)
        }
    }

    fun clearCompleted() {
        scope.launch {
            userDb.downloads().filter { it.state == DownloadEntry.DONE }.forEach { userDb.removeDownload(it.code) }
        }
    }

    internal fun reportProgress(code: Int, bytes: Long, total: Long) {
        _progress.value = mapOf(code to (bytes to total))
        _activeCode.value = code
    }

    internal fun reportIdle() {
        _progress.value = emptyMap()
        _activeCode.value = 0
    }
}
