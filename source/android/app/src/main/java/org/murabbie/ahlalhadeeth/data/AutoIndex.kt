package org.murabbie.ahlalhadeeth.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.murabbie.ahlalhadeeth.data.UserContent.Companion.ParsedSegment
import org.murabbie.ahlalhadeeth.data.YouTube.TimedText
import java.util.concurrent.TimeUnit

/**
 * الفهرسة والتفريغ التلقائيان لدرس:
 *  - دروس يوتيوب: يُجلب التفريغ الآلي من يوتيوب (بأزمنته) ثم تُستنبط المواضيع منه (Gemini إن وُجد مفتاحه، وإلا استنباط آلي مبسط بالقرائن).
 *  - دروس الصوت/الفيديو (ملف أو رابط): يُرسل الصوت إلى Gemini ليفرّغه ويقسّمه مواضيع بالأزمنة (يلزم مفتاح).
 */
object TopicSegmenter {

    private val questionCues = listOf(
        "السؤال", "سؤال", "يقول السائل", "سائل يقول", "يقول احد", "تقول السائله", "يقول هذا السائل", "احسن الله اليكم", "احسن الله اليك", "بارك الله فيكم",
        "ما حكم", "هل يجوز", "ما رايكم", "ما قولكم", "يسال", "تسال", "هذا سائل", "ورد سؤال", "فضيله الشيخ", "شيخنا", "ما الحكم", "ما صحه", "هل يصح", "ما معنى", "ما المراد",
    ).map { ArabicText.normalize(it) }

    /** قرائن بنيوية قوية (بداية باب أو مسألة أو حديث مشروح) */
    private val strongTopicCues = listOf(
        "قال المصنف", "قال المؤلف", "قال رحمه الله", "قال الشيخ رحمه الله", "قال الامام", "باب ", "فصل ", "الحديث الاول", "الحديث الثاني", "الحديث الثالث", "الحديث رقم", "حديث رقم", "المساله الاولى", "المساله الثانيه", "المساله الثالثه",
        "ننتقل الى", "الفائده الاولى", "الفائده الثانيه", "الفائده الثالثه", "القاعده الاولى", "القاعده الثانيه", "القاعده الثالثه",
    ).map { ArabicText.normalize(it) }

    /** قرائن ضعيفة (تقسيم داخلي) */
    private val topicCues = listOf(
        "قال الشيخ", "قال الله تعالى", "قال النبي", "قال رسول الله", "الحديث ", "حديث ", "المساله ", "مساله ", "القاعده", "الامر الثاني", "الامر الثالث", "ثانيا", "ثالثا", "رابعا", "خامسا",
        "النقطه", "الشرط ", "الفائده", "فائده", "تنبيه", "خلاصه", "ثم قال",
    ).map { ArabicText.normalize(it) }

    private val fillers = listOf("يعني", "نعم", "طيب", "اه", "ايه", "إيه", "أه", "هاه", "ها", "طبعا", "طبعًا", "إذن", "إذًا", "اذن", "يا", "أي", "اي")

    private val hadithRegex = Regex("(?:الحديث|حديث)\\s*(?:رقم)?\\s*(\\d{1,4})")

    /** أول [maxWords] كلمة من النص، بلا حشو، مقطوعة عند علامة استفهام أو نقطة */
    fun makeTitle(text: String, maxWords: Int = 12, maxChars: Int = 90): String {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val kept = ArrayList<String>()
        for (w in words) {
            val clean = w.trim('،', ',', '.', '؛', ':')
            if (kept.isEmpty() && clean in fillers) continue
            kept.add(w)
            if (w.endsWith("؟") || w.endsWith("?")) break
            if (kept.size >= maxWords) break
        }
        var t = kept.joinToString(" ")
        if (t.length > maxChars) t = t.take(maxChars).substringBeforeLast(' ') + "…"
        else if (kept.size >= maxWords && words.size > maxWords && !t.endsWith("؟")) t += "…"
        return t.ifBlank { "موضع" }
    }

    fun hadithNumber(text: String): Int {
        val n = ArabicText.normalize(text.take(300))
        return hadithRegex.find(n)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun cueType(normalized: String): Int {
        // ٣ = سؤال، ٢ = قرينة بنيوية قوية، ١ = قرينة ضعيفة، ٠ = لا شيء
        val head = normalized.take(60)
        if (questionCues.any { head.contains(it) }) return 3
        if (strongTopicCues.any { head.startsWith(it) || head.contains(" $it") }) return 2
        if (topicCues.any { head.startsWith(it) || head.contains(" $it") }) return 1
        return 0
    }

    /**
     * تقسيم التفريغ المؤقّت إلى مواضع. إن وُجدت فصول (من وصف الفيديو) فهي الحدود، وإلا قرائن نصية مع حدود طول.
     */
    fun segment(items: List<TimedText>, chapters: List<Pair<Long, String>> = emptyList(), minMs: Long = 30_000, maxMs: Long = 8 * 60_000): List<ParsedSegment> {
        if (items.isEmpty()) return emptyList()
        if (chapters.size >= 2) {
            val out = ArrayList<ParsedSegment>()
            for ((i, ch) in chapters.withIndex()) {
                val start = ch.first
                val end = chapters.getOrNull(i + 1)?.first ?: Long.MAX_VALUE
                val text = items.filter { it.startMs >= start && it.startMs < end }.joinToString(" ") { it.text }
                val title = ch.second
                val ques = title.endsWith("؟") || title.endsWith("?") || cueType(ArabicText.normalize(title)) == 3
                out.add(ParsedSegment(start, title, text.ifBlank { null }, ques, hadithNumber(title).takeIf { it > 0 } ?: hadithNumber(text)))
            }
            return out
        }
        // قرائن نصية
        val out = ArrayList<ParsedSegment>()
        var segStart = items.first().startMs
        var segType = 0
        val buf = ArrayList<TimedText>()
        fun flush(nextStart: Long) {
            if (buf.isEmpty()) return
            val text = buf.joinToString(" ") { it.text }
            val ques = segType == 3
            var title = makeTitle(text)
            if (ques && !title.endsWith("؟") && !title.endsWith("…")) title += "؟"
            if (out.isEmpty() && segType == 0) {
                val n = ArabicText.normalize(text.take(80))
                if (n.startsWith("بسم الله") || n.startsWith("الحمد لله") || n.startsWith("اما بعد") || n.startsWith("السلام عليكم")) title = "المقدمة"
            }
            out.add(ParsedSegment(segStart, title, text, ques, hadithNumber(text)))
            buf.clear()
            segStart = nextStart
        }
        for (it in items) {
            val n = ArabicText.normalize(it.text)
            val type = cueType(n)
            val elapsed = it.startMs - segStart
            val strong = type >= 2 && elapsed >= minMs
            val weak = type == 1 && elapsed >= minMs * 3
            val tooLong = elapsed >= maxMs && buf.isNotEmpty()
            if ((strong || weak || tooLong) && buf.isNotEmpty()) {
                flush(it.startMs)
                segType = if (tooLong && type == 0) 0 else type
            }
            buf.add(it)
        }
        flush(Long.MAX_VALUE)
        // دمج مقطع أول قصير جدًا مع التالي
        if (out.size >= 2 && (out[1].start - out[0].start) < 10_000) {
            val merged = ParsedSegment(out[0].start, out[1].line, listOfNotNull(out[0].write, out[1].write).joinToString(" ").ifBlank { null }, out[1].ques, out[1].hnum)
            out[0] = merged; out.removeAt(1)
        }
        return out
    }
}

/** عميل Gemini (REST) للاستنباط من التفريغ ولتفريغ الصوت */
class GeminiClient(private val apiKey: String, var model: String = MODELS.first()) {

    open class GeminiError(val code: Int, msg: String) : Exception(msg)
    /** نفدت الحصة اليومية لكل النماذج المتاحة (429 من كلها) — يُوقف الدفعة بدل تكرار الفشل درسًا درسًا */
    class QuotaError(msg: String) : GeminiError(429, msg)
    /** رفض Gemini معالجة الملف المرفوع (الحالة FAILED غالبًا بسبب نوع ملف لا يوافق محتواه) */
    class FileProcessingError(val state: String, val mime: String, detail: String) :
        Exception("تعذرت معالجة الملف في Gemini ($state، النوع $mime" + (if (detail.isNotBlank()) ": $detail" else "") + ")")

    private val client = NasHttp.client.newBuilder().readTimeout(15, TimeUnit.MINUTES).writeTimeout(15, TimeUnit.MINUTES).callTimeout(20, TimeUnit.MINUTES).build()
    private val base = "https://generativelanguage.googleapis.com"

    private fun errorMessage(code: Int, body: String): String {
        val msg = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
        return when (code) {
            400 -> "طلب مرفوض من Gemini: ${msg.take(160)}"
            401, 403 -> "مفتاح Gemini غير صالح أو بلا صلاحية"
            404 -> "النموذج $model غير متاح لهذا المفتاح"
            429 -> "تجاوز حد الاستخدام المجاني لـ Gemini (٢٠ طلبًا يوميًا لكل نموذج في الطبقة المجانية)؛ جرّب لاحقًا أو فعّل الفوترة لمشروع المفتاح"
            in 500..599 -> "خلل مؤقت في خادم Gemini ($code)"
            else -> "خطأ من Gemini ($code): ${msg.take(120)}"
        }
    }

    /**
     * استدعاء generateContent؛ يعيد نص الرد. النماذج تتبدل مع الزمن وحصة الطبقة المجانية لكل نموذج على حدة،
     * فعند 404 (نموذج لم يعد متاحًا) أو 429 (نفاد حصة يومية) أو 503 (ضغط) يُجرَّب النموذج التالي في [MODELS].
     * @param onPartial إن أُعطي يُستعمل البث (streamGenerateContent) ويُستدعى بالنص المتراكم كلما وصل جزء — لعرض تقدّم حقيقي
     */
    suspend fun generate(parts: JSONArray, json: Boolean = true, temperature: Double = 0.2, maxOutputTokens: Int = 65536, schema: JSONObject? = null, onPartial: ((String) -> Unit)? = null): String = withContext(Dispatchers.IO) {
        var lastErr: Exception? = null
        val tried = HashSet<String>()
        var quota429 = 0
        var waitedForQuota = false
        for (attempt in 0 until MODELS.size * 2 + 4) {
            currentCoroutineContext().ensureActive()
            if (model !in MODELS) model = MODELS.first()
            tried.add(model)
            val gen = JSONObject().put("temperature", temperature).put("maxOutputTokens", maxOutputTokens)
            if (json) gen.put("responseMimeType", "application/json")
            if (json && schema != null) gen.put("responseSchema", schema) // مخرجات منظَّمة: JSON صالح دائمًا
            val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts))).put("generationConfig", gen)
            val method = if (onPartial != null) "streamGenerateContent?alt=sse" else "generateContent"
            val req = Request.Builder().url("$base/v1beta/models/$model:$method").header("x-goog-api-key", apiKey) // المفتاح في ترويسة لا في الرابط حتى لا يظهر في السجلات
                .post(body.toString().toByteArray().toRequestBody("application/json".toMediaType())).build()
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) { val text = resp.body?.string() ?: ""; throw GeminiError(resp.code, errorMessage(resp.code, text)) }
                    val text = if (onPartial != null) readStream(resp.body?.source(), onPartial) else readWhole(resp.body?.string() ?: "")
                    if (text.isBlank()) throw GeminiError(EMPTY_REPLY, "رد فارغ من Gemini")
                    return@withContext text
                }
            } catch (e: GeminiError) {
                lastErr = e
                val next = MODELS.firstOrNull { it !in tried }
                if (e.code == 429) quota429++
                when {
                    e.code == 404 || e.code == 429 || e.code == 503 -> {
                        if (next != null) { model = next; continue }
                        else if (e.code == 503 && attempt < MODELS.size + 2) { delay(20_000L); tried.clear(); tried.add(model); continue }
                        else if (e.code == 429 && quota429 >= 2) {
                            // قد يكون حد الدقيقة لا حد اليوم: انتظار دقيقة وجولة ثانية على النماذج قبل الحكم بنفاد الحصة
                            if (!waitedForQuota) { waitedForQuota = true; quota429 = 0; delay(65_000L); tried.clear(); model = MODELS.first(); continue }
                            throw QuotaError(e.message ?: "نفدت الحصة")
                        }
                        else throw e
                    }
                    e.code in 500..599 || e.code == -1 -> { delay(3_000L * (attempt + 1)); continue }
                    else -> throw e
                }
            } catch (e: java.io.IOException) {
                lastErr = GeminiError(-1, "انقطع الاتصال بـ Gemini: ${e.message}")
                delay(3_000L * (attempt + 1)); continue
            }
        }
        throw lastErr ?: GeminiError(-1, "تعذر الاتصال بـ Gemini")
    }

    /** رد generateContent غير المبثوث → نص المرشح الأول */
    private fun readWhole(text: String): String {
        val o = JSONObject(text)
        val cand = o.optJSONArray("candidates")?.optJSONObject(0) ?: throw GeminiError(EMPTY_REPLY, "رد فارغ من Gemini" + (o.optJSONObject("promptFeedback")?.optString("blockReason")?.let { if (it.isNotBlank()) " ($it)" else "" } ?: ""))
        val partsOut = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until partsOut.length()) sb.append(partsOut.getJSONObject(i).optString("text"))
        return sb.toString()
    }

    /** رفع ملف صوتي كبير إلى Files API (رفع قابل للاستئناف في طلبين) ويعيد file_uri بعد أن يصبح ACTIVE */
    suspend fun uploadFile(file: java.io.File, mime: String, displayName: String, onStatus: (String) -> Unit = {}, onProgress: (Float) -> Unit = {}): String = withContext(Dispatchers.IO) {
        onStatus("رفع الصوت إلى Gemini (${ArabicText.formatSize(file.length())})…")
        val meta = JSONObject().put("file", JSONObject().put("display_name", displayName)).toString()
        val start = Request.Builder().url("$base/upload/v1beta/files").header("x-goog-api-key", apiKey)
            .header("X-Goog-Upload-Protocol", "resumable").header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", file.length().toString()).header("X-Goog-Upload-Header-Content-Type", mime)
            .post(meta.toByteArray().toRequestBody("application/json".toMediaType())).build()
        val uploadUrl = client.newCall(start).execute().use { resp ->
            if (!resp.isSuccessful) throw GeminiError(resp.code, errorMessage(resp.code, resp.body?.string() ?: ""))
            resp.header("X-Goog-Upload-URL") ?: throw GeminiError(-1, "لم يُعطِ Gemini رابط الرفع")
        }
        val total = file.length().coerceAtLeast(1L)
        val counting = object : okhttp3.RequestBody() {
            override fun contentType() = mime.toMediaType()
            override fun contentLength() = file.length()
            override fun writeTo(sink: okio.BufferedSink) {
                file.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024); var sent = 0L; var lastReport = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        sink.write(buf, 0, n); sent += n
                        if (sent - lastReport >= 256 * 1024 || sent == total) { lastReport = sent; onProgress(sent.toFloat() / total); onStatus("رفع الصوت إلى Gemini ${ArabicText.formatSize(sent)} / ${ArabicText.formatSize(total)}") }
                    }
                }
            }
        }
        val up = Request.Builder().url(uploadUrl).header("X-Goog-Upload-Offset", "0").header("X-Goog-Upload-Command", "upload, finalize")
            .post(counting).build()
        val fileObj = client.newCall(up).execute().use { resp ->
            val t = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw GeminiError(resp.code, errorMessage(resp.code, t))
            JSONObject(t).optJSONObject("file") ?: throw GeminiError(-1, "رد رفع غير مفهوم")
        }
        var uri = fileObj.optString("uri")
        var state = fileObj.optString("state")
        val name = fileObj.optString("name")
        var err = fileObj.optJSONObject("error")?.optString("message") ?: ""
        var waited = 0
        while (state == "PROCESSING" && waited < 600) {
            onStatus("Gemini يعالج الملف الصوتي…")
            delay(5_000); waited += 5
            val st = client.newCall(Request.Builder().url("$base/v1beta/$name").header("x-goog-api-key", apiKey).build()).execute().use { JSONObject(it.body?.string() ?: "{}") }
            state = st.optString("state", state); uri = st.optString("uri", uri)
            err = st.optJSONObject("error")?.optString("message") ?: err
        }
        if (state != "ACTIVE") {
            runCatching { client.newCall(Request.Builder().url("$base/v1beta/$name").header("x-goog-api-key", apiKey).delete().build()).execute().close() }
            throw FileProcessingError(state, mime, err)
        }
        uri
    }

    /** رفع الملف مع تجربة أنواع بديلة إن رفض Gemini معالجته بالنوع الأول (مثل video/webm لملف صوتي فقط) */
    private suspend fun uploadWithFallback(file: java.io.File, mime: String, displayName: String, onStatus: (String) -> Unit, onProgress: (Float) -> Unit = {}): Pair<String, String> {
        var last: FileProcessingError? = null
        for (m in listOf(mime) + MediaMime.alternatives(mime, file.name)) {
            try { return uploadFile(file, m, displayName, onStatus, onProgress) to m }
            catch (e: FileProcessingError) { last = e; onStatus("رفض Gemini النوع $m — تجربة نوع آخر…") }
        }
        throw last ?: GeminiError(-1, "تعذر رفع الملف")
    }

    companion object {
        /** رد فارغ أو محجوب: حتمي غالبًا فلا يُعاد داخل generate (تكفي محاولات المستدعي) حتى لا تُستنزف الحصة اليومية */
        const val EMPTY_REPLY = -2

        /** النماذج بترتيب التجربة (سبتمبر ٢٠٢٦: 2.5 لم تعد متاحة للمفاتيح الجديدة؛ حصة الطبقة المجانية لكل نموذج ٢٠ طلبًا/يوم) */
        val MODELS = listOf("gemini-3.5-flash", "gemini-3.6-flash", "gemini-3-flash-preview", "gemini-3.8-flash", "gemini-3.7-flash", "gemini-flash-latest", "gemini-2.5-flash")

        /** مخطط رد المواضع (responseSchema) */
        fun topicsSchema(withText: Boolean): JSONObject {
            val props = JSONObject()
                .put("start", JSONObject().put("type", "STRING"))
                .put("title", JSONObject().put("type", "STRING"))
                .put("question", JSONObject().put("type", "BOOLEAN"))
                .put("hadith", JSONObject().put("type", "INTEGER"))
            val req = JSONArray().put("start").put("title").put("question").put("hadith")
            if (withText) { props.put("text", JSONObject().put("type", "STRING")); req.put("text") }
            val item = JSONObject().put("type", "OBJECT").put("properties", props).put("required", req)
            return JSONObject().put("type", "OBJECT").put("properties", JSONObject().put("topics", JSONObject().put("type", "ARRAY").put("items", item))).put("required", JSONArray().put("topics"))
        }

        /** قراءة بث SSE (أسطر data: {…}) وتجميع النص مع استدعاء [onPartial] بعد كل جزء */
        fun readStream(src: okio.BufferedSource?, onPartial: (String) -> Unit): String {
            if (src == null) return ""
            val sb = StringBuilder()
            var blocked: String? = null
            while (true) {
                val line = src.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                val o = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                o.optJSONObject("error")?.let { throw GeminiError(it.optInt("code", -1), "خطأ من Gemini أثناء البث: ${it.optString("message").take(160)}") }
                o.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotBlank() }?.let { blocked = it }
                val cand = o.optJSONArray("candidates")?.optJSONObject(0) ?: continue
                val partsOut = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                var added = false
                for (i in 0 until partsOut.length()) { val t = partsOut.getJSONObject(i).optString("text"); if (t.isNotEmpty()) { sb.append(t); added = true } }
                if (added) onPartial(sb.toString())
            }
            if (sb.isBlank() && blocked != null) throw GeminiError(EMPTY_REPLY, "رد فارغ من Gemini ($blocked)")
            return sb.toString()
        }

        val startRegex = Regex("\"start\"\\s*:\\s*\"([0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?)\"")

        /** نسبة ما وصل إليه التفريغ داخل النافذة من آخر زمن بداية ظهر في النص المبثوث (أو null إن لم يظهر بعد أو جُهلت المدة) */
        fun streamedFraction(partial: String, wStart: Long, wEnd: Long): Float? {
            if (wEnd <= wStart) return null
            val tail = if (partial.length > 4000) partial.substring(partial.length - 4000) else partial
            val m = startRegex.findAll(tail).lastOrNull() ?: return null
            val ms = UserContent.parseTime(m.groupValues[1]) ?: return null
            return ((ms - wStart).toFloat() / (wEnd - wStart)).coerceIn(0f, 1f)
        }

        fun formatClock(ms: Long): String {
            val s = ms / 1000
            // Locale.US: الناتج يدخل موجّه Gemini ويُحلَّل بـ startRegex ([0-9])؛ الأرقام المشرقية في أجهزة عربية/فارسية تكسر ذلك
            return "%d:%02d:%02d".format(java.util.Locale.US, s / 3600, (s % 3600) / 60, s % 60)
        }

        /** تحويل رد JSON إلى مواضع؛ يقبل {"topics":[…]} أو مصفوفة مباشرة، ويتسامح مع أسوار الشيفرة */
        fun parseTopics(text: String, allowText: Boolean): List<ParsedSegment> {
            var t = text.trim()
            if (t.startsWith("```")) t = t.removePrefix("```json").removePrefix("```").trim().removeSuffix("```").trim()
            val arr: JSONArray = when {
                t.startsWith("[") -> JSONArray(t)
                else -> {
                    val o = JSONObject(t.substring(t.indexOf('{').coerceAtLeast(0)))
                    o.optJSONArray("topics") ?: o.optJSONArray("segments") ?: o.optJSONArray("items") ?: JSONArray()
                }
            }
            val out = ArrayList<ParsedSegment>()
            for (i in 0 until arr.length()) {
                val g = arr.optJSONObject(i) ?: continue
                val startRaw = g.opt("start") ?: g.opt("start_time") ?: g.opt("time") ?: continue
                val start = when (startRaw) {
                    is Number -> (startRaw.toDouble() * 1000).toLong()
                    else -> UserContent.parseTime(startRaw.toString()) ?: continue
                }
                val title = g.optString("title", g.optString("line", "")).trim()
                if (title.isEmpty()) continue
                val ques = g.optBoolean("question", false) || g.optBoolean("ques", false)
                val hnum = g.optInt("hadith", g.optInt("hnum", 0))
                val write = if (allowText) g.optString("text", "").trim().ifBlank { null } else null
                out.add(ParsedSegment(start, title, write, ques, hnum))
            }
            return out.sortedBy { it.start }
        }
    }

    private fun transcriptForPrompt(items: List<TimedText>, windowMs: Long = 15_000): String {
        val sb = StringBuilder()
        var winStart = -1L
        val buf = StringBuilder()
        for (it in items) {
            if (winStart < 0) winStart = it.startMs
            if (it.startMs - winStart >= windowMs && buf.isNotEmpty()) {
                sb.append('[').append(formatClock(winStart)).append("] ").append(buf.toString().trim()).append('\n')
                buf.clear(); winStart = it.startMs
            }
            buf.append(it.text).append(' ')
        }
        if (buf.isNotEmpty() && winStart >= 0) sb.append('[').append(formatClock(winStart)).append("] ").append(buf.toString().trim()).append('\n')
        return sb.toString()
    }

    /** استنباط المواضيع من تفريغ مؤقّت (تُملأ نصوص المواضع من التفريغ نفسه) */
    suspend fun segmentTranscript(items: List<TimedText>, title: String, onStatus: (String) -> Unit = {}): List<ParsedSegment> {
        onStatus("استنباط المواضيع بـ Gemini…")
        val transcript = transcriptForPrompt(items)
        val prompt = """أنت محرر فهارس للدروس العلمية الشرعية. أمامك تفريغ آلي (قد يحوي أخطاء إملائية) لدرس بعنوان «$title»، وكل سطر يبدأ بزمنه بين قوسين.
قسّم الدرس إلى مواضع متتابعة بحسب المعنى: كل مسألة أو حديث مشروح أو سؤال مع جوابه أو فائدة مستقلة موضعٌ. أعطِ لكل موضع:
- "start": زمن بدايته بصيغة h:mm:ss مأخوذًا من أقرب علامة زمنية قبل بدايته في التفريغ.
- "title": عنوانًا مختصرًا واضحًا بالعربية الفصحى (٤–١٤ كلمة) يصف مضمونه؛ وإن كان سؤالًا فاكتب نص السؤال مصحَّحًا منتهيًا بعلامة استفهام.
- "question": true إن كان سؤالًا وجوابه وإلا false.
- "hadith": رقم الحديث المشروح إن ذُكر رقمه صراحةً وإلا 0.
لا تجعل المواضع أقصر من ٣٠ ثانية غالبًا، ولا تتجاوز عشر دقائق للموضع الواحد، وابدأ أول موضع من بداية الدرس. أخرج JSON فقط بهذه الصيغة:
{"topics":[{"start":"0:00:00","title":"…","question":false,"hadith":0}]}

التفريغ:
$transcript"""
        val text = generate(JSONArray().put(JSONObject().put("text", prompt)), schema = topicsSchema(false))
        val topics = parseTopics(text, allowText = false)
        if (topics.isEmpty()) throw GeminiError(-1, "لم يُعطِ Gemini مواضع")
        // ملء النصوص من التفريغ
        val sorted = topics.sortedBy { it.start }
        return sorted.mapIndexed { i, t ->
            val end = sorted.getOrNull(i + 1)?.start ?: Long.MAX_VALUE
            val txt = items.filter { it.startMs >= t.start && it.startMs < end }.joinToString(" ") { it.text }
            ParsedSegment(t.start, t.line, txt.ifBlank { null }, t.ques, t.hnum)
        }
    }

    private fun transcribePrompt(title: String, window: Pair<Long, Long>?): String {
        val scope = if (window == null) "فرّغه كاملًا من أوله إلى آخره" else "فرّغ فقط الجزء الواقع بين ${formatClock(window.first)} و${formatClock(window.second)} من التسجيل (تجاهل ما قبله وما بعده)"
        val first = if (window == null) "0:00:00" else formatClock(window.first)
        return """استمع إلى هذا الدرس العلمي الشرعي بعنوان «$title» و$scope بالعربية الفصحى مع تصحيح الإملاء وإثبات الآيات والأحاديث كما نُطقت، ثم قسّم ما فرّغته إلى مواضع متتابعة بحسب المعنى كما تُفهرس أشرطة الدروس: كل مسألة أو باب أو حديث مشروح أو سؤال مع جوابه أو فائدة مستقلة موضعٌ. أعطِ لكل موضع:
- "start": زمن بدايته الدقيق في التسجيل بصيغة h:mm:ss (الأزمنة من بداية التسجيل الأصلي).
- "title": عنوانًا مختصرًا واضحًا (٤–١٤ كلمة) على طريقة فهارس الأشرطة (مثل: «شرح حديث: … — حكم كذا»)؛ وإن كان سؤالًا فنص السؤال منتهيًا بعلامة استفهام.
- "question": true إن كان سؤالًا وجوابه وإلا false.
- "hadith": رقم الحديث المشروح إن ذُكر رقمه صراحةً وإلا 0.
- "text": التفريغ الكامل لكلام الشيخ في هذا الموضع بلا اختصار ولا تلخيص، بترقيم مناسب.
لا تجعل المواضع أقصر من ٣٠ ثانية غالبًا ولا أطول من عشر دقائق، وابدأ أول موضع من $first. أخرج JSON فقط: {"topics":[{"start":"$first","title":"…","question":false,"hadith":0,"text":"…"}]}"""
    }

    /**
     * تفريغ صوت درس كامل وتقسيمه مواضع بالأزمنة (الملف صغير: مضمَّن؛ كبير: عبر Files API).
     * الدروس الطويلة تُفرَّغ نوافذَ (٣٠ دقيقة) على الملف المرفوع نفسه حتى لا يُقتطع الرد.
     */
    suspend fun transcribeAudio(file: java.io.File, mime: String, title: String, durationMs: Long = 0, onStatus: (String) -> Unit = {}, onProgress: (Float) -> Unit = {}): List<ParsedSegment> {
        val mediaPart: JSONObject
        // نصيب مراحل التقدّم: الرفع ١٥٪ والمعالجة ٣٪ ثم التفريغ (نوافذه بالتساوي)
        // التضمين حتى ٤ م.ب فقط: base64 ثم JSON ثم بايتات يضاعف الحجم في الذاكرة مرات (خطر نفاد الذاكرة)؛ الأكبر عبر Files API
        val uploadShare = if (file.length() <= 4L * 1024 * 1024 && durationMs <= WINDOW_MS + 5 * 60_000L) 0.03f else 0.18f
        if (uploadShare < 0.1f) {
            onStatus("إرسال الصوت إلى Gemini (${ArabicText.formatSize(file.length())})…")
            mediaPart = JSONObject().put("inline_data", JSONObject().put("mime_type", mime).put("data", okio.ByteString.of(*file.readBytes()).base64()))
        } else {
            val (uri, usedMime) = uploadWithFallback(file, mime, title, onStatus) { f -> onProgress(f * 0.15f) }
            mediaPart = JSONObject().put("file_data", JSONObject().put("mime_type", usedMime).put("file_uri", uri))
        }
        onProgress(uploadShare)
        val windows: List<Pair<Long, Long>?> = if (durationMs > WINDOW_MS + 5 * 60_000L) {
            val n = ((durationMs + WINDOW_MS - 1) / WINDOW_MS).toInt()
            (0 until n).map { i -> (i * WINDOW_MS) to minOf((i + 1) * WINDOW_MS, durationMs) }
        } else listOf(null)
        val out = ArrayList<ParsedSegment>()
        val perWindow = (1f - uploadShare) / windows.size
        for ((i, w) in windows.withIndex()) {
            val base = uploadShare + perWindow * i
            val label = if (w == null) "Gemini يفرّغ الدرس ويقسّمه (قد يستغرق دقائق)…" else "Gemini يفرّغ الجزء ${ArabicText.arabicDigits(i + 1)} / ${ArabicText.arabicDigits(windows.size)} (${formatClock(w.first)}–${formatClock(w.second)})…"
            onStatus(label); onProgress(base)
            val parts = JSONArray().put(JSONObject().put("text", transcribePrompt(title, w))).put(mediaPart)
            var topics: List<ParsedSegment> = emptyList()
            var lastErr: Exception? = null
            val wStart = w?.first ?: 0L
            val wEnd = w?.second ?: durationMs
            for (attempt in 0 until 3) {
                try {
                    val t0 = System.currentTimeMillis()
                    val text = generate(parts, temperature = 0.1, schema = topicsSchema(true)) { partial ->
                        // تقدّم حقيقي: آخر زمن "start" وصل في البث نسبةً إلى النافذة
                        val f = streamedFraction(partial, wStart, wEnd) ?: (1f - Math.exp(-(System.currentTimeMillis() - t0) / 90_000.0).toFloat()) * 0.3f
                        onProgress(base + perWindow * f.coerceIn(0f, 0.97f))
                        onStatus(label + " " + ArabicText.arabicDigits((f.coerceIn(0f, 0.97f) * 100).toInt()) + "٪")
                    }
                    topics = parseTopics(text, allowText = true)
                    if (topics.isNotEmpty()) break
                    lastErr = GeminiError(-1, "لم يُعطِ Gemini تفريغًا")
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: QuotaError) { throw e } catch (e: Exception) { lastErr = e }
                kotlinx.coroutines.delay(3000L * (attempt + 1))
            }
            if (topics.isEmpty()) throw (lastErr ?: GeminiError(-1, "لم يُعطِ Gemini تفريغًا"))
            if (w != null) {
                // ما خرج عن النافذة يُهمل (إلا أن أول موضع يبدأ من بداية النافذة)
                var lastEnd = out.lastOrNull()?.start ?: -1L
                for (t in topics) {
                    val st = if (t.start < w.first) w.first else t.start
                    if (st >= w.second + 30_000L || st <= lastEnd) continue
                    out.add(ParsedSegment(st, t.line, t.write, t.ques, t.hnum)); lastEnd = st
                }
            } else out.addAll(topics)
            onProgress(base + perWindow)
        }
        onProgress(1f)
        return out.sortedBy { it.start }
    }

    private val WINDOW_MS = 30 * 60_000L
}

/** نتيجة الفهرسة التلقائية لدرس */
data class AutoIndexResult(val segments: List<ParsedSegment>, val source: String, val transcriptLines: Int)

/** وسائط درس محمَّلة إلى ملف مؤقت: الملف ونوعه ومدته (٠ إن جُهلت) */
data class LoadedMedia(val file: java.io.File, val mime: String, val durationMs: Long)

/** محمِّل وسائط درس إلى ملف مؤقت: (الدرس، الحالة النصية، التقدّم ٠..١) → الوسائط أو null إن تعذر */
typealias MediaLoader = suspend (Chapter, (String) -> Unit, (Float) -> Unit) -> LoadedMedia?

/** منسّق الفهرسة التلقائية لدرس واحد */
object AutoIndexer {

    fun mimeOf(uriOrName: String): String {
        val n = uriOrName.substringBefore('?').lowercase()
        return when {
            n.endsWith(".mp3") -> "audio/mpeg"; n.endsWith(".m4a") -> "audio/mp4"; n.endsWith(".aac") -> "audio/aac"; n.endsWith(".ogg") || n.endsWith(".oga") -> "audio/ogg"
            n.endsWith(".opus") -> "audio/opus"; n.endsWith(".wav") -> "audio/wav"; n.endsWith(".flac") -> "audio/flac"
            n.endsWith(".mp4") || n.endsWith(".m4v") -> "video/mp4"; n.endsWith(".webm") -> "video/webm"; n.endsWith(".mkv") -> "video/x-matroska"; n.endsWith(".mov") -> "video/quicktime"
            else -> "audio/mpeg"
        }
    }

    const val KEY_HINT = "تفريغ الصوت بالذكاء الاصطناعي يحتاج إلى مفتاح Gemini (مجاني من aistudio.google.com/apikey) يضبطه المشرف العام في «المشرفون ← مفاتيح الخادم والذكاء الاصطناعي»"

    /**
     * الأصل: تفريغ صوت الدرس نفسه بالذكاء الاصطناعي (Gemini) واستنباط المواضيع منه — لا يعتمد على تفريغ يوتيوب.
     * تفريغ يوتيوب (إن وُجد) بديل فقط عند غياب المفتاح أو تعذر تحميل الصوت.
     * @param gemini عميل Gemini إن وُجد مفتاح، وإلا null
     * @param mediaLoader يحمّل وسائط الدرس (من الخادم أو يوتيوب أو الجهاز) إلى ملف مؤقت، أو null إن تعذر
     */
    suspend fun run(chapter: Chapter, gemini: GeminiClient?, mediaLoader: MediaLoader, onStatus: (String) -> Unit, onProgress: (Float) -> Unit = {}): AutoIndexResult {
        var loadError: String? = null
        if (gemini != null) {
            try {
                onStatus("تحميل صوت الدرس…"); onProgress(0.01f)
                // التحميل ٣٠٪ من التقدّم الكلي، والتفريغ ٦٩٪، والحفظ الباقي
                val media = mediaLoader(chapter, onStatus) { f -> onProgress(0.01f + 0.29f * f.coerceIn(0f, 1f)) }
                if (media != null) {
                    onProgress(0.3f)
                    // الملف المؤقت يُحذف حتى عند الفشل أو الإيقاف أو نفاد الحصة
                    val segs = try { gemini.transcribeAudio(media.file, media.mime, chapter.title, media.durationMs, onStatus) { f -> onProgress(0.3f + 0.69f * f.coerceIn(0f, 1f)) } } finally { runCatching { media.file.delete() } }
                    onProgress(0.99f)
                    return AutoIndexResult(segs, "تفريغ الصوت بالذكاء الاصطناعي (Gemini) واستنباط المواضيع", 0)
                }
                loadError = "تعذر تحميل ملف الدرس"
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: GeminiClient.QuotaError) { throw e } catch (e: Exception) {
                loadError = e.message ?: e.javaClass.simpleName
                onStatus("تعذر تفريغ الصوت ($loadError)؛ محاولة بديلة…")
            }
        }
        val ytId = chapter.captionSourceId
        if (ytId != null) {
            val cap = try { YouTube.fetchCaptions(ytId, onStatus) } catch (e: Exception) {
                throw IllegalStateException(if (gemini == null) "$KEY_HINT. ولا تفريغ لهذا الفيديو على يوتيوب." else "تعذر تفريغ الصوت: $loadError — ولا تفريغ لهذا الفيديو على يوتيوب")
            }
            val trackNote = if (cap.track?.kind == "asr") "تفريغ يوتيوب الآلي (بديل)" else "ترجمة يوتيوب (بديل)"
            if (gemini != null) {
                val segs = runCatching { gemini.segmentTranscript(cap.items, chapter.title, onStatus) }
                segs.getOrNull()?.let { return AutoIndexResult(it, "$trackNote + استنباط المواضيع بـ Gemini", cap.items.size) }
                onStatus("تعذر Gemini (${segs.exceptionOrNull()?.message}); استنباط آلي مبسط…")
            }
            val segs = TopicSegmenter.segment(cap.items, cap.chapters)
            val how = if (cap.chapters.size >= 2) "فصول الفيديو من وصفه" else "استنباط آلي مبسط بالقرائن"
            return AutoIndexResult(segs, "$trackNote + $how", cap.items.size)
        }
        throw IllegalStateException(if (gemini == null) KEY_HINT else "تعذر تفريغ الصوت: $loadError")
    }
}


/**
 * مهمة الفهرسة والتفريغ التلقائيين لعدة دروس: طابور يعمل في الخلفية داخل التطبيق (يبقى أثناء التنقل، وتُبقيه خدمة أمامية عند مغادرة التطبيق)،
 * بتقدّم مئوي لكل درس، ويُحفظ الطابور في ملف فيُستأنف بعد إغلاق التطبيق؛ وعند نفاد حصة Gemini اليومية يتوقف ويُبقي الباقي «بانتظار الحصة» للمتابعة لاحقًا.
 */
class AutoIndexJob(
    private val content: UserContent,
    private val geminiProvider: () -> GeminiClient?,
    private val mediaLoader: MediaLoader,
    private val filesDir: java.io.File? = null,
    private val onRunningChanged: (Boolean) -> Unit = {},
) {
    data class Item(val code: Int, val title: String, val state: String = "", val count: Int = 0, val error: String? = null, val progress: Float = 0f, val done: Boolean = false, val pending: Boolean = true, val replace: Boolean = true, val sheekhId: Int = 0, val bookId: Int = 0)
    data class State(
        val running: Boolean = false,
        val items: List<Item> = emptyList(),
        val status: String = "",
        val finishedAt: Long = 0,
        /** رقم الدرس الجاري في القائمة (-1 إن لا شيء) */
        val current: Int = -1,
        val dismissed: Boolean = false,
        /** توقفت لنفاد الحصة اليومية */
        val quotaStop: Boolean = false,
        val sheekhId: Int = 0,
        val bookId: Int = 0,
        val bookName: String = "",
        val quotaMessage: String = "",
    ) {
        val doneCount get() = items.count { it.done }
        val okCount get() = items.count { it.done && it.error == null }
        val errCount get() = items.count { it.error != null }
        val pendingCount get() = items.count { it.pending && !it.done }
        val progress get() = items.getOrNull(current)?.progress ?: 0f
        /** التقدّم الكلي للدفعة (الدروس المنجزة + نسبة الجاري) */
        val overall get() = if (items.isEmpty()) 0f else (doneCount + (if (current in items.indices && !items[current].done) progress else 0f)) / items.size
        val visible get() = items.isNotEmpty() && !dismissed
    }

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(State())
    val state: kotlinx.coroutines.flow.StateFlow<State> = _state
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
    private var job: kotlinx.coroutines.Job? = null
    @Volatile private var userCancelled = false
    private val queueFile get() = filesDir?.let { java.io.File(it, "autoindex_queue.json") }

    /** بدء (أو إضافة إلى الطابور الجاري): replace = استبدال الفهرس الموجود، وإلا تُتخطى الدروس التي لها مواضع */
    fun start(chapters: List<Chapter>, replace: Boolean, bookName: String = "") {
        if (chapters.isEmpty()) return
        val cur = _state.value
        val keepOld = cur.running || cur.pendingCount > 0
        // ما هو في الطابور (لم يُنجز) لا يُكرَّر؛ وما أُنجز أو تعذر ثم أُعيد اختياره يُستبدل بمدخل جديد
        val pendingCodes = if (keepOld) cur.items.filter { !it.done }.map { it.code }.toSet() else emptySet()
        val fresh = chapters.filter { it.code !in pendingCodes }.map { Item(it.code, it.displayTitle, "بانتظار", replace = replace, sheekhId = it.sheekhId, bookId = it.bookId) }
        val freshCodes = fresh.map { it.code }.toSet()
        val items = if (keepOld) cur.items.filter { it.code !in freshCodes } + fresh else fresh
        userCancelled = false
        val first = chapters.first()
        _state.update { st -> st.copy(items = items, dismissed = false, quotaStop = false, quotaMessage = "", finishedAt = 0,
            sheekhId = if (st.running) st.sheekhId else first.sheekhId, bookId = if (st.running) st.bookId else first.bookId,
            bookName = if (st.running && st.bookName.isNotBlank()) st.bookName else bookName.ifBlank { first.bookName }) }
        saveQueue()
        launchWorker()
    }

    /** متابعة ما بقي (بعد نفاد الحصة أو إغلاق التطبيق) */
    fun resume() { if (!_state.value.running && _state.value.pendingCount > 0) { userCancelled = false; _state.update { it.copy(quotaStop = false, quotaMessage = "", dismissed = false, finishedAt = 0) }; launchWorker() } }

    /** استئناف الطابور المحفوظ عند فتح التطبيق (إلا ما توقف لنفاد الحصة: يُعرض للمتابعة اليدوية) */
    fun resumeIfPending() {
        val f = queueFile ?: return
        if (_state.value.running || _state.value.items.isNotEmpty() || !f.exists()) return
        runCatching {
            val o = JSONObject(f.readText())
            val arr = o.optJSONArray("items") ?: JSONArray()
            val items = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { Item(it.getInt("code"), it.optString("title"), "بانتظار", replace = it.optBoolean("replace", true), sheekhId = it.optInt("sheekhId"), bookId = it.optInt("bookId")) } }
            if (items.isEmpty()) { f.delete(); return }
            val quota = o.optBoolean("quota", false)
            val paused = o.optBoolean("paused", quota)
            _state.value = State(items = items, sheekhId = o.optInt("sheekhId"), bookId = o.optInt("bookId"), bookName = o.optString("bookName"), quotaStop = quota,
                quotaMessage = if (quota) "توقف التفريغ سابقًا لنفاد حصة Gemini اليومية — تابع بعد تجدد الحصة" else "", finishedAt = if (paused) System.currentTimeMillis() else 0)
            // ما أوقفه المستخدم بنفسه أو توقف لنفاد الحصة لا يُستأنف تلقائيًا، بل يُعرض في الشريط للمتابعة اليدوية
            if (!paused) launchWorker()
        }.onFailure { f.delete() } // ملف تالف: يُحذف بدل إعادة المحاولة عند كل فتح
    }

    /** كتابة ذرّية (ملف مؤقت ثم إعادة تسمية) ومتزامنة: تُستدعى من خيط الواجهة ومن العامل */
    @Synchronized private fun saveQueue(quota: Boolean = false, paused: Boolean = false) {
        val f = queueFile ?: return
        val st = _state.value
        val pending = st.items.filter { !it.done }
        if (pending.isEmpty()) { f.delete(); return }
        val arr = JSONArray()
        for (it in pending) arr.put(JSONObject().put("code", it.code).put("title", it.title).put("replace", it.replace).put("sheekhId", it.sheekhId).put("bookId", it.bookId))
        runCatching { val tmp = java.io.File(f.path + ".tmp"); tmp.writeText(JSONObject().put("items", arr).put("sheekhId", st.sheekhId).put("bookId", st.bookId).put("bookName", st.bookName).put("quota", quota).put("paused", paused || quota).toString()); tmp.renameTo(f) }
    }

    private fun launchWorker() {
        if (_state.value.running) return
        _state.update { it.copy(running = true, status = "") }
        onRunningChanged(true)
        job = scope.launch {
            val me = coroutineContext[kotlinx.coroutines.Job]
            var quotaStopped: String? = null
            try {
                while (isActive) {
                    val idx = _state.value.items.indexOfFirst { !it.done }
                    if (idx < 0) break
                    val item = _state.value.items[idx]
                    // بالرمز لا بالموضع: start() قد يعيد ترتيب القائمة أثناء عمل العامل
                    fun upd(f: (Item) -> Item) { _state.update { st -> st.copy(items = st.items.map { x -> if (x.code == item.code && !x.done) f(x) else x }) } }
                    _state.update { it.copy(current = idx, status = "") }
                    val ch = content.chapter(-item.code)
                    if (ch == null) { upd { it.copy(state = "الدرس لم يعد موجودًا", error = "غير موجود", done = true, pending = false) }; saveQueue(); continue }
                    try {
                        val existing = content.segments(-ch.code).size
                        if (existing > 0 && !item.replace) { upd { it.copy(state = "له فهرس (${ArabicText.arabicDigits(existing)}) — تُخطي", done = true, pending = false, progress = 1f) }; saveQueue(); continue }
                        upd { it.copy(state = "جارٍ…", pending = false) }
                        val res = AutoIndexer.run(ch, geminiProvider(), mediaLoader, { st -> _state.update { it.copy(status = st) }; upd { it.copy(state = st) } }) { p -> upd { it.copy(progress = p) } }
                        if (item.replace && existing > 0) content.deleteSegmentsOfChapter(-ch.code)
                        val n = content.addSegments(-ch.code, res.segments)
                        upd { it.copy(state = "تم: ${ArabicText.arabicDigits(n)} موضع (${res.source})", count = n, done = true, progress = 1f) }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        upd { it.copy(state = "أُوقف", pending = true) }; throw e
                    } catch (e: GeminiClient.QuotaError) {
                        upd { it.copy(state = "بانتظار تجدد الحصة", pending = true, progress = 0f) }
                        quotaStopped = e.message ?: "نفدت حصة Gemini اليومية"
                        break
                    } catch (e: Exception) {
                        upd { it.copy(state = "تعذر: ${e.message}", error = e.message ?: "خطأ", done = true) }
                    }
                    saveQueue()
                    delay(400) // تخفيف الضغط على يوتيوب/Gemini بين الدروس
                }
            } finally {
                val q = quotaStopped
                if (q != null) {
                    _state.update { st -> st.copy(items = st.items.map { if (!it.done) it.copy(state = "بانتظار تجدد الحصة", pending = true) else it }, quotaStop = true, quotaMessage = q) }
                }
                saveQueue(quota = q != null, paused = userCancelled)
                // لا يُغيَّر الوضع إن كان عامل جديد قد بدأ بعد إيقاف هذا (متابعة سريعة بعد الإيقاف)
                if (job === me) {
                    _state.update { it.copy(running = false, status = "", current = -1, finishedAt = System.currentTimeMillis()) }
                    onRunningChanged(false)
                }
            }
        }
    }

    /** إيقاف الدفعة: ما لم يُنجز يبقى في الطابور للمتابعة */
    fun cancel() { userCancelled = true; job?.cancel(); _state.update { it.copy(running = false, status = "أُوقفت الدفعة", current = -1, finishedAt = System.currentTimeMillis()) } }
    /** مسح النتائج (والطابور المتبقي) */
    fun clear() { if (!_state.value.running) { _state.value = State(); queueFile?.delete() } }
    /** إخفاء الشريط بعد الانتهاء */
    fun dismiss() { _state.update { it.copy(dismissed = true) } }
}
