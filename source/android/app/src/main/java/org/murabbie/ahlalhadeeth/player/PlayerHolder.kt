package org.murabbie.ahlalhadeeth.player

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.murabbie.ahlalhadeeth.App
import org.murabbie.ahlalhadeeth.data.Chapter

/** حالة المشغّل كما تراها الواجهة */
data class PlayerState(
    val chapter: Chapter? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val error: String? = null,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val isLocalSource: Boolean = false,
)

/** غلاف MediaController يوفّر حالة المشغّل كـ StateFlow وأوامر التشغيل. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerHolder(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controller: MediaController? = null
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state
    private var pollJob: Job? = null
    private val pending = ArrayList<(MediaController) -> Unit>()
    private var playlistChapters: List<Chapter> = emptyList()
    private var lastSavedAt = 0L

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh()
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.value = _state.value.copy(error = friendlyError(error), isBuffering = false, isPlaying = false)
        }
    }

    private fun friendlyError(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "تعذر الاتصال بخادم الصوت — تحقق من الإنترنت"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "الملف الصوتي غير موجود على الخادم (${e.message ?: ""})"
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "الملف الصوتي غير موجود"
        else -> "تعذر التشغيل: ${e.message ?: e.errorCodeName}"
    }

    private var connecting = false // قبل init: المُهيِّئات تُنفَّذ بترتيب النص

    init {
        connect()
    }

    private fun connect() {
        connecting = true
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            connecting = false
            try {
                val c = future.get()
                controller = c
                c.addListener(listener)
                pending.forEach { it(c) }
                pending.clear()
                refresh()
                startPolling()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "تعذر تشغيل خدمة الصوت: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null && c.isConnected) block(c) else {
            pending.add(block)
            if (!connecting) { // انقطع الاتصال، أو فشل الاتصال الأول فلم يُعَد قط
                controller = null
                connect()
            }
        }
    }

    private fun chapterFromItem(item: MediaItem?): Chapter? {
        val extras = item?.mediaMetadata?.extras ?: return null
        val code = extras.getInt("code", 0)
        if (code == 0) return null
        return playlistChapters.firstOrNull { it.code == code } ?: Chapter(
            code = code,
            sheekhId = extras.getInt("sheekh_id", 0),
            bookId = extras.getInt("book_id", 0),
            title = extras.getString("title", "") ?: "",
            fileName = extras.getString("file_name", "") ?: "",
            fileSize = 0,
            path = extras.getString("path", "") ?: "",
            cdNumber = "",
            ord = 0,
            segCount = 0,
            writeCount = 0,
            sheekhName = extras.getString("sheekh_name", "") ?: "",
            bookName = extras.getString("book_name", "") ?: "",
            mediaUri = extras.getString("media_uri", "") ?: "",
            isVideo = extras.getBoolean("is_video", false),
        )
    }

    /** المشغّل الحالي (لربط عرض الفيديو) */
    fun playerOrNull(): Player? = controller?.takeIf { it.isConnected }

    private fun refresh() {
        val c = controller ?: return
        val item = c.currentMediaItem
        val chapter = chapterFromItem(item)
        val dur = c.duration
        _state.value = _state.value.copy(
            chapter = chapter,
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = if (dur > 0) dur else 0,
            speed = c.playbackParameters.speed,
            hasNext = c.hasNextMediaItem(),
            hasPrevious = c.hasPreviousMediaItem(),
            isLocalSource = item?.mediaMetadata?.extras?.getBoolean("local", false) ?: false,
            error = if (c.playerError == null) null else _state.value.error,
        )
        savePosition(false)
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val c = controller
                if (c != null && c.isConnected && (c.isPlaying || c.playbackState == Player.STATE_BUFFERING)) {
                    val dur = c.duration
                    _state.value = _state.value.copy(
                        positionMs = c.currentPosition.coerceAtLeast(0),
                        durationMs = if (dur > 0) dur else _state.value.durationMs,
                        isBuffering = c.playbackState == Player.STATE_BUFFERING,
                        isPlaying = c.isPlaying,
                    )
                    savePosition(false)
                }
                delay(500)
            }
        }
    }

    private fun savePosition(force: Boolean) {
        val s = _state.value
        val ch = s.chapter ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastSavedAt < 5000) return
        lastSavedAt = now
        val app = App.instance
        val pos = s.positionMs
        val dur = s.durationMs
        scope.launch(Dispatchers.IO) {
            runCatching { app.userDb.savePosition(ch.code, pos, dur) }
            app.settings.update { it.copy(lastPlayedCode = ch.code, lastPlayedPosition = pos) }
        }
    }

    private fun buildItem(ch: Chapter): MediaItem {
        val app = App.instance
        val src = app.audioDownloads.audioSource.resolve(ch)
        val extras = Bundle().apply {
            putInt("code", ch.code)
            putInt("sheekh_id", ch.sheekhId)
            putInt("book_id", ch.bookId)
            putString("title", ch.title)
            putString("file_name", ch.fileName)
            putString("path", ch.path)
            putString("sheekh_name", ch.sheekhName)
            putString("book_name", ch.bookName)
            putBoolean("local", src.isLocal)
            putString("media_uri", ch.mediaUri)
            putBoolean("is_video", ch.isVideo)
        }
        val md = MediaMetadata.Builder()
            .setTitle("${ch.displayTitle} (${ch.fileName})")
            .setArtist(ch.sheekhName)
            .setAlbumTitle(ch.bookName)
            .setDisplayTitle(ch.displayTitle)
            .setExtras(extras)
            .build()
        return MediaItem.Builder().setUri(src.uri).setMediaId(ch.code.toString()).setMediaMetadata(md).build()
    }

    /**
     * تشغيل شريط من موضع معيّن. إذا مُرِّرت قائمة أشرطة (كتاب كامل) تُستخدم قائمة تشغيل للانتقال التلقائي.
     */
    fun play(chapter: Chapter, startMs: Long = 0, playlist: List<Chapter>? = null) {
        val app = App.instance
        val useList = if (app.settings.value.autoPlayNext && playlist != null && playlist.any { it.code == chapter.code }) playlist.filter { !it.isYouTube || it.code == chapter.code } else listOf(chapter) // دروس يوتيوب لا يشغّلها ExoPlayer
        playlistChapters = useList
        _state.value = _state.value.copy(error = null, chapter = chapter, positionMs = startMs)
        val c0 = controller
        if (c0 != null && c0.isConnected && c0.currentMediaItem?.mediaId == chapter.code.toString() && c0.playbackState != Player.STATE_IDLE && c0.playerError == null) {
            c0.seekTo(startMs)
            c0.play()
            return
        }
        scope.launch {
            val items = withContext(Dispatchers.IO) { useList.map { buildItem(it) } }
            val index = useList.indexOfFirst { it.code == chapter.code }.coerceAtLeast(0)
            withController { c ->
                c.setMediaItems(items, index, startMs)
                c.playbackParameters = c.playbackParameters.withSpeed(app.settings.value.playbackSpeed)
                c.prepare()
                c.play()
            }
        }
    }

    fun seekTo(ms: Long) = withController { it.seekTo(ms.coerceAtLeast(0)) }

    fun togglePlayPause() = withController { c ->
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
            c.play()
        }
    }

    fun pause() = withController { it.pause() }
    fun stop() = withController { it.stop(); it.clearMediaItems(); _state.value = PlayerState() }
    fun seekBack() = withController { it.seekBack() }
    fun seekForward() = withController { it.seekForward() }
    fun next() = withController { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
    fun previous() = withController { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() }
    fun setSpeed(speed: Float) {
        App.instance.settings.update { it.copy(playbackSpeed = speed) }
        withController { it.playbackParameters = it.playbackParameters.withSpeed(speed) }
        _state.value = _state.value.copy(speed = speed)
    }

    fun currentCode(): Int = _state.value.chapter?.code ?: 0

    fun release() {
        pollJob?.cancel()
        controller?.release()
        controller = null
    }
}
