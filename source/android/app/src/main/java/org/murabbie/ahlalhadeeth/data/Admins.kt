package org.murabbie.ahlalhadeeth.data

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * نظام المشرفين المستقل للتطبيق (لا علاقة له بحسابات NAS):
 * ملف admins.json على الخادم (برابط مشاركة دائم) يحوي المشرف العام والمشرفين، ولكل واحد رقم سري.
 * لا يُخزَّن الرقم السري نفسه، بل بصمة منه (PBKDF2)، ومفتاح الخادم الذي يرفع به التطبيق ملفات النشر
 * مغلَّف (AES-GCM) بمفتاح مشتق من الرقم السري لكل مشرف، فلا يظهر بنص صريح في التطبيق ولا في أي ملف عام.
 */
object AdminCrypto {
    const val ITERATIONS = 100_000
    /** أقل طول لرقم سري جديد: admins.json عام، فالرقم القصير يُكسر بالتجربة خارج التطبيق (الأرقام القديمة الأقصر تبقى صالحة للدخول) */
    const val MIN_PIN = 12
    private val rnd = SecureRandom()

    /** توحيد الأرقام (هندية/فارسية → لاتينية) وإزالة الفراغات */
    fun normalizePin(pin: String): String = pin.trim().map { c ->
        when (c) {
            in '٠'..'٩' -> '0' + (c - '٠')
            in '۰'..'۹' -> '0' + (c - '۰')
            else -> c
        }
    }.filter { !it.isWhitespace() && it != '-' }.joinToString("")

    // حروف صغيرة وأرقام بلا المتشابهات (0/o/1/l/i): ١٦ خانة ≈ ٧٩ بت بدل ٢٧ بت لثمانية أرقام
    private const val PIN_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
    fun randomPin(): String = (1..16).map { PIN_ALPHABET[rnd.nextInt(PIN_ALPHABET.length)] }.joinToString("")
    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rnd.nextBytes(it) }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    /** PBKDF2-HMAC-SHA256 (كتلة واحدة = ٣٢ بايت) — مطابق لـ hashlib.pbkdf2_hmac في بايثون */
    fun deriveKey(pin: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val p = normalizePin(pin).toByteArray(Charsets.UTF_8)
        require(p.isNotEmpty()) { "الرقم السري فارغ" }
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(p, "HmacSHA256")) }
        var u = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val t = u.copyOf()
        for (i in 1 until iterations) {
            u = mac.doFinal(u)
            for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
        }
        return t
    }

    /** بصمة التحقق من المفتاح (تُخزَّن في الملف بدل الرقم السري) */
    fun verifier(key: ByteArray): String = b64(hmac(key, "verify".toByteArray()))

    private fun encKey(key: ByteArray): ByteArray = hmac(key, "enc".toByteArray())

    /** تغليف نص (مفتاح الخادم) بـ AES-256-GCM؛ الناتج base64(iv ‖ ciphertext ‖ tag) */
    fun wrap(key: ByteArray, plaintext: String): String {
        val iv = randomBytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey(key), "AES"), GCMParameterSpec(128, iv))
        return b64(iv + c.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }

    /** فك التغليف؛ يعيد null إن كان المفتاح خاطئًا */
    fun unwrap(key: ByteArray, wrapped: String): String? = runCatching {
        val all = unb64(wrapped)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey(key), "AES"), GCMParameterSpec(128, all.copyOfRange(0, 12)))
        String(c.doFinal(all.copyOfRange(12, all.size)), Charsets.UTF_8)
    }.getOrNull()

    // okio بدل java.util.Base64 (غير متاح قبل أندرويد ٨)
    fun b64(b: ByteArray): String = b.toByteString().base64()
    fun unb64(s: String): ByteArray = s.decodeBase64()?.toByteArray() ?: throw IllegalArgumentException("base64 غير صالح")
}

/** سجل المشرفين (محتوى admins.json) مع عمليات القراءة والتعديل */
class AdminRegistry(val root: JSONObject) {

    companion object {
        const val FORMAT = "ahl-alhadeeth-admins"
        const val ROLE_SUPER = "super"
        const val ROLE_ADMIN = "admin"

        fun parse(text: String): AdminRegistry {
            val o = JSONObject(text)
            if (o.optString("format") != FORMAT) throw IllegalArgumentException("ملف المشرفين غير صالح")
            if (o.optJSONObject("super") == null) throw IllegalArgumentException("ملف المشرفين بلا مشرف عام")
            if (o.optJSONArray("admins") == null) o.put("admins", JSONArray())
            return AdminRegistry(o)
        }

        /** إنشاء سجل جديد بمشرف عام ومفتاح خادم */
        fun create(superUser: String, superName: String, superPin: String, credential: String): AdminRegistry {
            val o = JSONObject().put("format", FORMAT).put("version", 1).put("updated", System.currentTimeMillis())
                .put("kdf", JSONObject().put("alg", "pbkdf2-hmac-sha256").put("iterations", AdminCrypto.ITERATIONS))
                .put("super", JSONObject().put("user", superUser).put("name", superName))
                .put("admins", JSONArray())
            val r = AdminRegistry(o)
            r.setSecret(r.root.getJSONObject("super"), superPin, credential)
            return r
        }
    }

    data class Entry(val user: String, val name: String, val active: Boolean, val created: Long, val createdBy: String, val needsPin: Boolean, val isSuper: Boolean)

    /** نتيجة دخول ناجح */
    class Auth(val role: String, val user: String, val name: String, val key: ByteArray, val credential: String)

    class AuthError(msg: String) : Exception(msg)

    // الملف عام وغير موثوق: لا نقبل عدد دورات أقل من الافتراضي (وإلا أُضعفت البصمات الجديدة)
    val iterations: Int get() = (root.optJSONObject("kdf")?.optInt("iterations", AdminCrypto.ITERATIONS) ?: AdminCrypto.ITERATIONS).coerceAtLeast(AdminCrypto.ITERATIONS)
    val updated: Long get() = root.optLong("updated", 0)
    val superUser: String get() = root.getJSONObject("super").optString("user", "admin")

    private fun adminsArr(): JSONArray = root.getJSONArray("admins")

    private fun findAdmin(user: String): JSONObject? {
        val arr = adminsArr()
        for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("user") == user) return arr.getJSONObject(i)
        return null
    }

    /** المشرف العام أو مشرف بالاسم */
    fun find(user: String): JSONObject? = if (user == superUser) root.getJSONObject("super") else findAdmin(user)

    fun entries(): List<Entry> {
        val arr = adminsArr()
        return (0 until arr.length()).map { i ->
            val a = arr.getJSONObject(i)
            Entry(a.optString("user"), a.optString("name"), a.optBoolean("active", true), a.optLong("created", 0), a.optString("created_by", ""), a.optString("wrapped", "").isBlank(), false)
        }
    }

    private fun setSecret(obj: JSONObject, pin: String, credential: String) {
        val p = AdminCrypto.normalizePin(pin)
        require(p.length >= AdminCrypto.MIN_PIN) { "الرقم السري قصير (١٢ خانة على الأقل)" }
        val salt = AdminCrypto.randomBytes(16)
        val key = AdminCrypto.deriveKey(p, salt, iterations)
        obj.put("salt", AdminCrypto.b64(salt)).put("hash", AdminCrypto.verifier(key)).put("wrapped", AdminCrypto.wrap(key, credential)).put("updated", System.currentTimeMillis())
    }

    /** الدخول: يعيد المفتاح ومفتاح الخادم، أو يرمي AuthError برسالة واضحة */
    fun authenticate(user: String, pin: String): Auth {
        val u = user.trim()
        val obj = find(u) ?: throw AuthError("لا يوجد مشرف بهذا الاسم")
        val isSuper = u == superUser
        if (!isSuper && !obj.optBoolean("active", true)) throw AuthError("هذا الحساب موقوف؛ راجع المشرف العام")
        val wrapped = obj.optString("wrapped", "")
        if (wrapped.isBlank()) throw AuthError("يحتاج هذا الحساب إلى رقم سري جديد من المشرف العام")
        val salt = AdminCrypto.unb64(obj.getString("salt"))
        val key = AdminCrypto.deriveKey(pin, salt, iterations)
        if (AdminCrypto.verifier(key) != obj.optString("hash")) throw AuthError("الرقم السري غير صحيح")
        val cred = AdminCrypto.unwrap(key, wrapped) ?: throw AuthError("تعذر فك مفتاح الخادم؛ اطلب رقمًا سريًا جديدًا")
        return Auth(if (isSuper) ROLE_SUPER else ROLE_ADMIN, u, obj.optString("name", u), key, cred)
    }

    /** هل ما زال المفتاح المحفوظ محليًا صالحًا لهذا المستخدم؟ */
    fun verify(user: String, key: ByteArray): Boolean {
        val obj = find(user) ?: return false
        if (user != superUser && !obj.optBoolean("active", true)) return false
        return AdminCrypto.verifier(key) == obj.optString("hash")
    }

    fun addAdmin(name: String, user: String, pin: String, credential: String, createdBy: String) {
        val u = user.trim()
        require(u.isNotEmpty() && !u.any { it.isWhitespace() }) { "اسم المستخدم فارغ أو فيه فراغات" }
        require(u != superUser && findAdmin(u) == null) { "اسم المستخدم مستعمل" }
        val a = JSONObject().put("user", u).put("name", name.trim().ifBlank { u }).put("active", true).put("created", System.currentTimeMillis()).put("created_by", createdBy)
        setSecret(a, pin, credential)
        adminsArr().put(a)
        touch()
    }

    fun setPin(user: String, pin: String, credential: String) {
        val obj = find(user) ?: throw IllegalArgumentException("لا يوجد مشرف بهذا الاسم")
        setSecret(obj, pin, credential)
        touch()
    }

    fun setActive(user: String, active: Boolean) {
        findAdmin(user)?.put("active", active)
        touch()
    }

    fun remove(user: String) {
        val arr = adminsArr()
        for (i in (0 until arr.length()).reversed()) if (arr.getJSONObject(i).optString("user") == user) arr.remove(i)
        touch()
    }

    /**
     * تغيير مفتاح الخادم: يُغلَّف للمشرف العام بمفتاحه الحالي، وتُمحى أغلفة المشرفين
     * (فيحتاج كل مشرف إلى رقم سري جديد يولّده المشرف العام).
     */
    fun changeCredential(superKey: ByteArray, credential: String) {
        val s = root.getJSONObject("super")
        s.put("wrapped", AdminCrypto.wrap(superKey, credential)).put("updated", System.currentTimeMillis())
        val arr = adminsArr()
        for (i in 0 until arr.length()) arr.getJSONObject(i).put("wrapped", "")
        touch()
    }

    private fun touch() { root.put("updated", System.currentTimeMillis()) }

    fun toText(): String = root.toString(2)
}
