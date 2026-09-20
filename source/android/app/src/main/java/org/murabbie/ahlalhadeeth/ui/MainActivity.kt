package org.murabbie.ahlalhadeeth.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import org.murabbie.ahlalhadeeth.App
import org.murabbie.ahlalhadeeth.ui.theme.AppTheme

class MainActivity : ComponentActivity() {

    private val pendingOpen = mutableStateOf<String?>(null)

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pendingOpen.value = intent?.getStringExtra("open") // عند إعادة الإنشاء لا نعيد فتح شاشة الإشعار القديم
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
        val app = application as App
        // متابعة نقل لم يكتمل (بعد إغلاق التطبيق أو قتله) — من نشاط مرئي حتى يُسمح ببدء الخدمة الأمامية
        app.transfer.resumeIfPending()
        app.autoIndex.resumeIfPending()
        setContent {
            val settings by app.settings.state.collectAsState()
            LaunchedEffect(settings.keepScreenOn) {
                if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            AppTheme(themeMode = settings.themeMode, fontScale = settings.fontScale) {
                AppRoot(app = app, pendingOpen = pendingOpen)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingOpen.value = intent.getStringExtra("open")
    }
}
