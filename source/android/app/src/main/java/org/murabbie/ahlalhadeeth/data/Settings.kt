package org.murabbie.ahlalhadeeth.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.murabbie.ahlalhadeeth.BuildConfig

data class AudioServer(val title: String, val url: String)

data class AppSettings(
    val manifestUrl: String = BuildConfig.DEFAULT_MANIFEST_URL,
    val audioServers: List<AudioServer> = listOf(AudioServer("خادم أهل الحديث والأثر (alathar.net)", BuildConfig.DEFAULT_AUDIO_BASE)),
    val audioServerIndex: Int = 0,
    /** هل اختار المستخدم الخادم بنفسه؟ (وإلا يُطبَّق الخادم الافتراضي الذي يعلنه manifest) */
    val audioServerChosen: Boolean = false,
    val audioExt: String = ".mp3",
    val localAudioTreeUri: String = "",
    val downloadToExternal: Boolean = true,
    val fontScale: Float = 1.0f,
    val keepScreenOn: Boolean = false,
    val themeMode: Int = 0, // 0 نظام، 1 فاتح، 2 داكن
    val autoPlayNext: Boolean = true,
    val showTranscriptInline: Boolean = false,
    val wifiOnlyDownloads: Boolean = false,
    val lastPlayedCode: Int = 0,
    val lastPlayedPosition: Long = 0,
    val playbackSpeed: Float = 1.0f,
    /** تنظيف الذاكرة المؤقتة تلقائيًا */
    val autoCleanCache: Boolean = true,
    /** آخر جودة اختارها المشرف عند إدخال قوائم يوتيوب (صوت / فيديو عادي / فيديو عالٍ) */
    val lastImportQuality: Int = 0,
) {
    val audioBaseUrl: String get() = audioServers.getOrNull(audioServerIndex)?.url ?: BuildConfig.DEFAULT_AUDIO_BASE
}

class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state
    val value: AppSettings get() = _state.value

    private fun load(): AppSettings {
        val servers = ArrayList<AudioServer>()
        val js = prefs.getString("audioServers", null)
        if (js != null) {
            runCatching {
                val arr = JSONArray(js)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    servers.add(AudioServer(o.optString("title"), o.optString("url")))
                }
            }
        }
        if (servers.isEmpty()) servers.add(AudioServer("خادم أهل الحديث والأثر (alathar.net)", BuildConfig.DEFAULT_AUDIO_BASE))
        return AppSettings(
            manifestUrl = prefs.getString("manifestUrl", BuildConfig.DEFAULT_MANIFEST_URL) ?: BuildConfig.DEFAULT_MANIFEST_URL,
            audioServers = servers,
            audioServerIndex = prefs.getInt("audioServerIndex", 0).coerceIn(0, servers.size - 1),
            audioServerChosen = prefs.getBoolean("audioServerChosen", false),
            autoCleanCache = prefs.getBoolean("autoCleanCache", true),
            lastImportQuality = prefs.getInt("lastImportQuality", 0),
            audioExt = prefs.getString("audioExt", ".mp3") ?: ".mp3",
            localAudioTreeUri = prefs.getString("localAudioTreeUri", "") ?: "",
            downloadToExternal = prefs.getBoolean("downloadToExternal", true),
            fontScale = prefs.getFloat("fontScale", 1.0f),
            keepScreenOn = prefs.getBoolean("keepScreenOn", false),
            themeMode = prefs.getInt("themeMode", 0),
            autoPlayNext = prefs.getBoolean("autoPlayNext", true),
            showTranscriptInline = prefs.getBoolean("showTranscriptInline", false),
            wifiOnlyDownloads = prefs.getBoolean("wifiOnlyDownloads", false),
            lastPlayedCode = prefs.getInt("lastPlayedCode", 0),
            lastPlayedPosition = prefs.getLong("lastPlayedPosition", 0L),
            playbackSpeed = prefs.getFloat("playbackSpeed", 1.0f),
        )
    }

    fun update(block: (AppSettings) -> AppSettings) {
        val s = block(_state.value)
        _state.value = s
        val arr = JSONArray()
        s.audioServers.forEach { arr.put(JSONObject().put("title", it.title).put("url", it.url)) }
        prefs.edit()
            .putString("manifestUrl", s.manifestUrl)
            .putString("audioServers", arr.toString())
            .putInt("audioServerIndex", s.audioServerIndex)
            .putBoolean("audioServerChosen", s.audioServerChosen)
            .putBoolean("autoCleanCache", s.autoCleanCache)
            .putInt("lastImportQuality", s.lastImportQuality)
            .putString("audioExt", s.audioExt)
            .putString("localAudioTreeUri", s.localAudioTreeUri)
            .putBoolean("downloadToExternal", s.downloadToExternal)
            .putFloat("fontScale", s.fontScale)
            .putBoolean("keepScreenOn", s.keepScreenOn)
            .putInt("themeMode", s.themeMode)
            .putBoolean("autoPlayNext", s.autoPlayNext)
            .putBoolean("showTranscriptInline", s.showTranscriptInline)
            .putBoolean("wifiOnlyDownloads", s.wifiOnlyDownloads)
            .putInt("lastPlayedCode", s.lastPlayedCode)
            .putLong("lastPlayedPosition", s.lastPlayedPosition)
            .putFloat("playbackSpeed", s.playbackSpeed)
            .apply()
    }

    /** يُستدعى بعد قراءة manifest ليضيف خوادم الصوت المعرَّفة فيه دون تكرار. */
    fun mergeAudioServers(servers: List<AudioServer>, defaultUrl: String = "") {
        if (servers.isEmpty() && defaultUrl.isBlank()) return
        update { s ->
            val existing = s.audioServers.toMutableList()
            for (sv in servers) if (existing.none { it.url == sv.url }) existing.add(sv)
            // الخادم الافتراضي المعلَن في manifest يُطبَّق ما لم يختر المستخدم خادمًا بنفسه
            val idx = if (defaultUrl.isNotBlank() && !s.audioServerChosen) existing.indexOfFirst { it.url == defaultUrl.trim() }.takeIf { it >= 0 } ?: s.audioServerIndex else s.audioServerIndex
            s.copy(audioServers = existing, audioServerIndex = idx.coerceIn(0, existing.size - 1))
        }
    }
}
