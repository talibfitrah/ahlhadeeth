package org.murabbie.ahlalhadeeth.data

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * الإبلاغ عن محتوى من داخل التطبيق (سياسة Google Play للمحتوى المولَّد بالذكاء الاصطناعي: التفريغات والفهارس الآلية).
 * يُرسل البلاغ إلى نموذج Google يملكه الناشر: وصف المحتوى وسبب البلاغ فقط — لا اسم ولا بريد ولا معرّف جهاز.
 * حقلا النموذج إلزاميان، فأي خطأ في رقم الحقل يعيد 400 بدل أن يُسجَّل بلاغ فارغ.
 * عميل مستقل: بلا تتبّع للتحويلات (تحويل إلى صفحة دخول Google يعني أن البلاغ لم يُسجَّل)، وبلا ملفات تعريف ارتباط، وبمهلة كلية قصيرة.
 */
object ContentReport {
    private const val URL = "https://docs.google.com/forms/d/e/1FAIpQLSddDAA2QEbq5TDiMNACBIYUOE9b6NB-l0_Dxj2pFx3oq6kw9g/formResponse"
    val REASONS = listOf("محتوى مسيء أو غير لائق", "تفريغ أو فهرس خاطئ", "أخرى")
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(20, TimeUnit.SECONDS).build()

    fun send(content: String, reason: String) {
        val body = FormBody.Builder().add("entry.1825849213", content).add("entry.2046357856", reason).build()
        client.newCall(Request.Builder().url(URL).post(body).build()).execute().use {
            if (it.code != 200) throw IOException("تعذر إرسال البلاغ (${it.code})")
        }
    }
}
