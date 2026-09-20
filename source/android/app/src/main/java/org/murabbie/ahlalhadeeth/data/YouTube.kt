package org.murabbie.ahlalhadeeth.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** أدوات يوتيوب: استخراج معرّف الفيديو أو قائمة التشغيل من الرابط، وجلب العنوان عبر oEmbed (بلا مفتاح API). */
object YouTube {

    private val idPatterns = listOf(
        Regex("(?:youtube\\.com|youtube-nocookie\\.com)/(?:watch\\?(?:.*&)?v=|embed/|v/|shorts/|live/)([A-Za-z0-9_-]{11})"),
        Regex("youtu\\.be/([A-Za-z0-9_-]{11})"),
        Regex("^youtube:([A-Za-z0-9_-]{11})$"),
        Regex("^([A-Za-z0-9_-]{11})$"),
    )

    /** معرّف الفيديو (١١ حرفًا) من أي صيغة رابط، أو null إن لم يكن رابط يوتيوب */
    fun extractId(url: String?): String? {
        val u = url?.trim() ?: return null
        if (u.isEmpty()) return null
        if (!u.contains("youtu", true) && !u.startsWith("youtube:")) {
            // معرّف مجرّد (١١ حرفًا) لا يُعدّ يوتيوب إلا بصيغة youtube:ID
            return null
        }
        for (p in idPatterns) p.find(u)?.let { return it.groupValues[1] }
        return null
    }

    /** معرّف قائمة التشغيل من الرابط (list=…) */
    fun extractPlaylistId(url: String?): String? {
        val u = url?.trim() ?: return null
        return Regex("[?&]list=([A-Za-z0-9_-]+)").find(u)?.groupValues?.get(1)
    }

    fun isYouTubeUrl(url: String?): Boolean = extractId(url) != null || (url?.contains("youtu", true) == true && extractPlaylistId(url) != null)

    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    /** عنوان الفيديو عبر oEmbed؛ يعيد null عند الفشل */
    suspend fun fetchTitle(id: String): String? = fetchInfo(watchUrl(id))?.first

    /** معلومات فيديو أو قائمة تشغيل عبر oEmbed: (العنوان، اسم القناة)؛ null عند الفشل */
    suspend fun fetchInfo(pageUrl: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://www.youtube.com/oembed?url=" + java.net.URLEncoder.encode(pageUrl, "UTF-8") + "&format=json"
            NasHttp.client.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val o = JSONObject(resp.body?.string() ?: "")
                val t = o.optString("title").ifBlank { null } ?: return@use null
                t to o.optString("author_name", "")
            }
        }.getOrNull()
    }

    /** رابط قائمة التشغيل من معرّفها */
    fun playlistUrl(id: String) = "https://www.youtube.com/playlist?list=$id"

    // ---------- الترجمة/التفريغ الآلي من يوتيوب (captions) ----------

    /** سطر مؤقّت من التفريغ */
    data class TimedText(val startMs: Long, val durMs: Long, val text: String)

    data class CaptionTrack(val lang: String, val kind: String, val name: String, val baseUrl: String)

    data class CaptionResult(
        val track: CaptionTrack?,
        val items: List<TimedText>,
        val title: String,
        val description: String,
        val lengthSeconds: Long,
        /** فصول الفيديو من الوصف (زمن، عنوان) */
        val chapters: List<Pair<Long, String>>,
    )

    class CaptionsUnavailable(msg: String) : Exception(msg)

    private fun playerResponse(videoId: String): JSONObject {
        // الطريقة التي يستعملها youtube-transcript-api: عميل ANDROID
        val body = JSONObject().put("context", JSONObject().put("client", JSONObject().put("clientName", "ANDROID").put("clientVersion", "20.10.38").put("hl", "ar"))).put("videoId", videoId)
        val req = Request.Builder().url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false").header("User-Agent", "Mozilla/5.0").header("Content-Type", "application/json")
            .post(body.toString().toByteArray().toRequestBody("application/json".toMediaType())).build()
        NasHttp.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("youtube player HTTP ${resp.code}")
            return JSONObject(resp.body?.string() ?: "{}")
        }
    }

    private fun playerResponseWeb(videoId: String): JSONObject {
        val body = JSONObject().put("context", JSONObject().put("client", JSONObject().put("clientName", "WEB").put("clientVersion", "2.20250101.00.00").put("hl", "ar"))).put("videoId", videoId)
        val req = Request.Builder().url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false").header("User-Agent", DESKTOP_UA).header("Content-Type", "application/json")
            .post(body.toString().toByteArray().toRequestBody("application/json".toMediaType())).build()
        NasHttp.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("youtube player HTTP ${resp.code}")
            return JSONObject(resp.body?.string() ?: "{}")
        }
    }

    /** بديل أخير: صفحة المشاهدة (ytInitialPlayerResponse) */
    private fun playerResponseFromWatchPage(videoId: String): JSONObject? {
        val req = Request.Builder().url(watchUrl(videoId) + "&hl=ar").header("User-Agent", DESKTOP_UA).header("Accept-Language", "ar,en").header("Cookie", "CONSENT=YES+cb; SOCS=CAI").build()
        val html = NasHttp.client.newCall(req).execute().use { if (!it.isSuccessful) return null; it.body?.string() ?: return null }
        val m = Regex("ytInitialPlayerResponse\\s*=\\s*(\\{.*?\\});\\s*(?:var|</script>)", RegexOption.DOT_MATCHES_ALL).find(html) ?: return null
        return runCatching { JSONObject(m.groupValues[1]) }.getOrNull()
    }

    private fun tracksOf(pr: JSONObject): List<CaptionTrack> {
        val arr = pr.optJSONObject("captions")?.optJSONObject("playerCaptionsTracklistRenderer")?.optJSONArray("captionTracks") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val t = arr.getJSONObject(i)
            val name = t.optJSONObject("name")?.let { n -> n.optString("simpleText").ifBlank { n.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: "" } } ?: ""
            CaptionTrack(t.optString("languageCode"), t.optString("kind"), name, t.optString("baseUrl"))
        }.filter { it.baseUrl.isNotBlank() }
    }

    /** اختيار أفضل مسار: عربي يدوي ثم عربي آلي ثم أي يدوي ثم أي آلي */
    fun pickTrack(tracks: List<CaptionTrack>): CaptionTrack? =
        tracks.firstOrNull { it.lang.startsWith("ar") && it.kind != "asr" }
            ?: tracks.firstOrNull { it.lang.startsWith("ar") }
            ?: tracks.firstOrNull { it.kind != "asr" }
            ?: tracks.firstOrNull()

    /** تحليل صيغة json3 لملف الترجمة */
    fun parseJson3(text: String): List<TimedText> {
        val o = JSONObject(text)
        val events = o.optJSONArray("events") ?: return emptyList()
        val out = ArrayList<TimedText>()
        for (i in 0 until events.length()) {
            val e = events.getJSONObject(i)
            val segs = e.optJSONArray("segs") ?: continue
            val sb = StringBuilder()
            for (j in 0 until segs.length()) sb.append(segs.getJSONObject(j).optString("utf8"))
            val t = sb.toString().replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
            if (t.isEmpty() || t == "[موسيقى]" || t == "[Music]") continue
            out.add(TimedText(e.optLong("tStartMs"), e.optLong("dDurationMs"), t))
        }
        return out
    }

    /** فصول الفيديو من نص الوصف: أسطر تبدأ بزمن مثل 0:00 أو 1:02:05 */
    fun parseChapters(description: String): List<Pair<Long, String>> {
        val re = Regex("^\\s*[\\[(]?\\s*((?:\\d{1,2}:)?\\d{1,2}:\\d{2})\\s*[\\])]?\\s*[-–—:|]?\\s*(.+?)\\s*$")
        val out = ArrayList<Pair<Long, String>>()
        for (line in description.lines()) {
            val m = re.find(line.map { c -> if (c in '٠'..'٩') '0' + (c - '٠') else c }.joinToString("")) ?: continue
            val t = UserContent.parseTime(m.groupValues[1]) ?: continue
            val title = m.groupValues[2].trim()
            if (title.isEmpty()) continue
            out.add(t to title)
        }
        // يجب أن تكون متصاعدة وأولها قريب من الصفر
        if (out.size < 2 || out.first().first > 60_000 || out.zipWithNext().any { (a, b) -> b.first <= a.first }) return emptyList()
        return out
    }

    /**
     * جلب التفريغ الآلي (أو اليدوي) لفيديو يوتيوب مع وصفه وفصوله.
     * يرمي CaptionsUnavailable إن لم توجد ترجمة، أو IOException عند تعذر الوصول.
     */
    suspend fun fetchCaptions(videoId: String, onStatus: (String) -> Unit = {}): CaptionResult = withContext(Dispatchers.IO) {
        // نسخة المتجر لا تستعمل واجهة يوتيوب الداخلية غير الموثَّقة (youtubei/timedtext بعميل منتحَل): مخالفة لشروط يوتيوب
        if (org.murabbie.ahlalhadeeth.BuildConfig.DISTRIBUTION == "play") throw IllegalStateException("غير متاح في هذه النسخة")
        onStatus("الاتصال بيوتيوب…")
        var pr = runCatching { playerResponse(videoId) }.getOrNull()
        var tracks = pr?.let { tracksOf(it) } ?: emptyList()
        var status = pr?.optJSONObject("playabilityStatus")?.optString("status") ?: ""
        if (tracks.isEmpty()) {
            val w = runCatching { playerResponseWeb(videoId) }.getOrNull()
            if (w != null) { val tw = tracksOf(w); if (tw.isNotEmpty()) { pr = w; tracks = tw }; if (status.isBlank()) status = w.optJSONObject("playabilityStatus")?.optString("status") ?: "" }
        }
        if (tracks.isEmpty()) {
            val h = runCatching { playerResponseFromWatchPage(videoId) }.getOrNull()
            if (h != null) { val th = tracksOf(h); if (th.isNotEmpty()) { pr = h; tracks = th } }
        }
        val details = pr?.optJSONObject("videoDetails")
        val title = details?.optString("title") ?: ""
        val description = details?.optString("shortDescription") ?: ""
        val length = details?.optLong("lengthSeconds") ?: 0L
        val chapters = parseChapters(description)
        val track = pickTrack(tracks) ?: run {
            val reason = pr?.optJSONObject("playabilityStatus")?.optString("reason") ?: ""
            if (status == "LOGIN_REQUIRED") throw CaptionsUnavailable("يوتيوب يطلب تسجيل الدخول من هذه الشبكة (${reason.ifBlank { "تحقق آلي" }})؛ جرّب من شبكة أخرى")
            if (status.isNotBlank() && status != "OK") throw CaptionsUnavailable("الفيديو غير متاح (${reason.ifBlank { status }})")
            if (pr == null) throw java.io.IOException("تعذر الوصول إلى يوتيوب")
            throw CaptionsUnavailable("لا توجد ترجمة/تفريغ آلي لهذا الفيديو على يوتيوب")
        }
        onStatus("تنزيل التفريغ (${track.lang}${if (track.kind == "asr") " آلي" else ""})…")
        val url = track.baseUrl + (if ("fmt=" in track.baseUrl) "" else "&fmt=json3")
        val body = NasHttp.client.newCall(Request.Builder().url(url).header("User-Agent", DESKTOP_UA).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("timedtext HTTP ${resp.code}")
            resp.body?.string() ?: ""
        }
        val items = parseJson3(body)
        if (items.isEmpty()) throw CaptionsUnavailable("ملف التفريغ فارغ")
        CaptionResult(track, items, title, description, length, chapters)
    }

    // ---------- قراءة قائمة التشغيل كاملة عبر واجهة youtubei (بلا مفتاح API) ----------

    data class PlaylistVideo(val id: String, val title: String)
    data class PlaylistInfo(val id: String, val title: String, val author: String, val videos: List<PlaylistVideo>)

    private const val INNERTUBE = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false"
    private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private fun varint(n: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var v = n
        while (true) {
            val b = (v and 0x7f).toInt(); v = v ushr 7
            if (v != 0L) out.write(b or 0x80) else { out.write(b); break }
        }
        return out.toByteArray()
    }
    private fun pbField(fn: Int, wireType: Int, payload: ByteArray): ByteArray =
        varint(((fn.toLong() shl 3) or wireType.toLong())) + (if (wireType == 0) payload else varint(payload.size.toLong()) + payload)
    private fun b64(b: ByteArray): String = okio.ByteString.of(*b).base64()

    /** رمز المتابعة لصفحة تبدأ عند [index] (١٠٠ فيديو في الصفحة) — بنية protobuf التي يستعملها موقع يوتيوب */
    internal fun continuationToken(playlistId: String, index: Int): String {
        val pt = "PT:" + okio.ByteString.of(*pbField(1, 0, varint(index.toLong()))).base64Url().trimEnd('=')
        val params = b64(pbField(1, 0, varint(1)) + pbField(15, 2, pt.toByteArray()))
        val paramsEnc = java.net.URLEncoder.encode(params, "UTF-8")
        val inner = pbField(2, 2, ("VL$playlistId").toByteArray()) + pbField(3, 2, paramsEnc.toByteArray()) + pbField(35, 2, playlistId.toByteArray())
        return b64(pbField(80226972, 2, inner))
    }

    private fun collectVideos(o: Any?, out: MutableList<PlaylistVideo>) {
        when (o) {
            is JSONObject -> {
                o.optJSONObject("playlistVideoRenderer")?.let { r ->
                    val id = r.optString("videoId")
                    val runs = r.optJSONObject("title")?.optJSONArray("runs")
                    val title = if (runs != null) (0 until runs.length()).joinToString("") { runs.getJSONObject(it).optString("text") } else r.optJSONObject("title")?.optString("simpleText") ?: ""
                    if (id.length == 11) out.add(PlaylistVideo(id, title))
                }
                o.optJSONObject("lockupViewModel")?.let { l ->
                    if (l.optString("contentType") == "LOCKUP_CONTENT_TYPE_VIDEO") {
                        val id = l.optString("contentId")
                        val title = l.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")?.optJSONObject("title")?.optString("content") ?: ""
                        if (id.length == 11) out.add(PlaylistVideo(id, title))
                    }
                }
                val keys = o.keys()
                while (keys.hasNext()) collectVideos(o.opt(keys.next()), out)
            }
            is JSONArray -> for (i in 0 until o.length()) collectVideos(o.opt(i), out)
        }
    }

    private fun browse(body: JSONObject): JSONObject {
        val req = Request.Builder().url(INNERTUBE).header("User-Agent", DESKTOP_UA).header("Content-Type", "application/json")
            .post(body.toString().toByteArray().toRequestBody("application/json".toMediaType())).build()
        NasHttp.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("youtube HTTP ${resp.code}")
            return JSONObject(resp.body?.string() ?: "{}")
        }
    }

    /**
     * كل فيديوهات قائمة التشغيل (بعناوينها) عبر youtubei/browse مع توليد رموز المتابعة لكل ١٠٠ فيديو.
     * يرمي استثناءً عند الفشل ليُستعمل المشغّل المخفي بديلًا.
     */
    suspend fun fetchPlaylist(playlistId: String, onProgress: (Int) -> Unit = {}): PlaylistInfo = withContext(Dispatchers.IO) {
        // نسخة المتجر لا تستعمل واجهة يوتيوب الداخلية غير الموثَّقة (youtubei/timedtext بعميل منتحَل): مخالفة لشروط يوتيوب
        if (org.murabbie.ahlalhadeeth.BuildConfig.DISTRIBUTION == "play") throw IllegalStateException("غير متاح في هذه النسخة")
        val ctx = JSONObject().put("context", JSONObject().put("client", JSONObject().put("clientName", "WEB").put("clientVersion", "2.20250101.00.00").put("hl", "ar")))
        val first = browse(JSONObject(ctx.toString()).put("browseId", "VL$playlistId"))
        val title = first.optJSONObject("microformat")?.optJSONObject("microformatDataRenderer")?.optString("title") ?: ""
        val videos = ArrayList<PlaylistVideo>()
        val seen = HashSet<String>()
        val page = ArrayList<PlaylistVideo>()
        collectVideos(first, page)
        for (v in page) if (seen.add(v.id)) videos.add(v)
        if (videos.isEmpty()) throw IllegalStateException("لم يُعثر على فيديوهات في القائمة (قد تكون خاصة أو فارغة)")
        onProgress(videos.size)
        var index = 100
        while (index <= 5000) {
            currentCoroutineContext().ensureActive()
            page.clear()
            val d = runCatching { browse(JSONObject(ctx.toString()).put("continuation", continuationToken(playlistId, index))) }.getOrNull() ?: break
            collectVideos(d, page)
            var added = 0
            for (v in page) if (seen.add(v.id)) { videos.add(v); added++ }
            if (added == 0) break
            onProgress(videos.size)
            index += 100
        }
        val info = fetchInfo(playlistUrl(playlistId))
        PlaylistInfo(playlistId, info?.first?.ifBlank { title } ?: title, info?.second ?: "", videos)
    }

    /**
     * صفحة HTML لمشغّل IFrame الرسمي مع جسر إلى Kotlin (Android.onTime / Android.onReady / Android.onPlaylist).
     * تُحمَّل بـ loadDataWithBaseURL("https://www.youtube.com", …) ليقبلها التضمين.
     */
    fun playerHtml(videoId: String?, playlistId: String?, startSeconds: Long, autoplay: Boolean): String {
        val vid = videoId?.let { "'$it'" } ?: "undefined"
        val listVars = if (playlistId != null) "listType: 'playlist', list: '$playlistId'," else ""
        return """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"><style>html,body{margin:0;padding:0;background:#000;height:100%;overflow:hidden}#player{width:100%;height:100%}</style></head>
<body><div id="player"></div>
<script>
var player=null, ready=false, timer=null, plSent=false;
var tag=document.createElement('script'); tag.src='https://www.youtube.com/iframe_api'; document.head.appendChild(tag);
function sendPlaylist(){ if(plSent||!player||!player.getPlaylist) return; try{var pl=player.getPlaylist(); if(pl&&pl.length){plSent=true; Android.onPlaylist(JSON.stringify(pl));}}catch(x){} }
function onYouTubeIframeAPIReady(){
  player=new YT.Player('player',{videoId:$vid, playerVars:{$listVars playsinline:1, rel:0, fs:0, autoplay:${if (autoplay) 1 else 0}, start:${startSeconds}, modestbranding:1, controls:1, enablejsapi:1, origin:'https://www.youtube.com'},
    events:{onReady:function(e){ready=true; try{Android.onReady();}catch(x){} sendPlaylist(); if(!timer){timer=setInterval(tick,500);} ${if (autoplay) "try{player.playVideo();}catch(x){}" else ""}},
            onStateChange:function(e){try{Android.onState(e.data);}catch(x){} sendPlaylist();},
            onError:function(e){try{Android.onError(e.data);}catch(x){}}}});
}
function tick(){ if(!ready||!player||!player.getCurrentTime) return; sendPlaylist(); try{Android.onTime(player.getCurrentTime(), player.getDuration()||0, player.getPlayerState());}catch(x){} }
function seekTo(s){ if(ready){player.seekTo(s,true); player.playVideo();} }
function playVideo(){ if(ready) player.playVideo(); }
function pauseVideo(){ if(ready) player.pauseVideo(); }
function loadVideo(id,s){ if(ready) player.loadVideoById(id, s||0); }
function setRate(r){ if(ready) try{player.setPlaybackRate(r);}catch(x){} }
</script></body></html>"""
    }
}
