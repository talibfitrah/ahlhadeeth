import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "org.murabbie.ahlalhadeeth"
    // compileSdk 36 (أندرويد ١٦) لأن Google Play يشترط منذ ٣١ أغسطس ٢٠٢٦ أن تستهدف التطبيقات الجديدة وتحديثاتها API 36؛
    // نكهة التوزيع المباشر تبقى targetSdk 35 (المختبَرة)، ونكهة play تستهدف 36 (تحتاج إلى اختبار على أجهزة أندرويد ١٦)
    compileSdk = 36

    defaultConfig {
        applicationId = "org.murabbie.ahlalhadeeth"
        minSdk = 24
        targetSdk = 35
        versionCode = 19
        versionName = "1.7.8"
        vectorDrawables.useSupportLibrary = true
        // رابط manifest الافتراضي على NAS (يمكن تغييره من الإعدادات)
        buildConfigField("String", "DEFAULT_MANIFEST_URL", "\"${project.findProperty("manifestUrl") ?: "https://files.murabbie.org/fsdownload/MANIFEST_ID/manifest.json"}\"")
        buildConfigField("String", "DEFAULT_AUDIO_BASE", "\"https://www.alathar.net/files/sound/\"")
        buildConfigField("String", "DATA_VERSION", "\"4.14.0-1\"")
        // تنزيل البيانات تلقائيًا عند أول فتح (نسخة النشر)
        buildConfigField("boolean", "AUTO_DOWNLOAD", (project.findProperty("autoDownload") ?: "true").toString())
        // المحتوى المشترك (ينشره المشرفون على NAS ويجلبه الجميع)
        buildConfigField("String", "DEFAULT_SHARED_URL", "\"${project.findProperty("sharedUrl") ?: "https://files.murabbie.org/fsdownload/SHARED_ID/shared-content.json"}\"")
        // ملف المشرفين (المشرف العام والمشرفون وأرقامهم السرية) — رابط مشاركة دائم
        buildConfigField("String", "DEFAULT_ADMINS_URL", "\"${project.findProperty("adminsUrl") ?: "https://files.murabbie.org/fsdownload/ADMINS_ID/admins.json"}\"")
        // رابط سياسة الخصوصية العام (-PprivacyUrl): يشترط Play أن يكون داخل التطبيق أيضًا؛ يظهر في «عن البرنامج» إن ضُبط
        buildConfigField("String", "PRIVACY_URL", "\"${project.findProperty("privacyUrl") ?: ""}\"")
        buildConfigField("String", "DEFAULT_NAS_API", "\"https://files.murabbie.org/webapi\"")
        buildConfigField("String", "DEFAULT_SHARED_PATH", "\"/downloads/ahl-alhadeeth\"")
    }

    // نكهتا التوزيع:
    //  direct = التوزيع المباشر (APK من NAS): كامل الوظائف بما فيها نقل دروس يوتيوب إلى الخادم والتحديث الذاتي من manifest
    //  play   = نسخة Google Play: بلا استخراج/تنزيل وسائط يوتيوب (مخالف لسياسات Play وشروط يوتيوب)، بلا تحديث ذاتي (التحديث من المتجر)،
    //           وبلا خدمة أمامية من نوع specialUse (تحتاج إلى تصريح في Play Console) — تُستعمل dataSync بحدّها
    flavorDimensions += "distribution"
    productFlavors {
        create("direct") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"direct\"")
        }
        create("play") {
            dimension = "distribution"
            targetSdk = 36
            buildConfigField("String", "DISTRIBUTION", "\"play\"")
        }
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST", "META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
    // استخراج روابط وسائط يوتيوب على الهاتف (لنقل الدروس إلى خادم البيانات)
    // استخراج وسائط يوتيوب (نقل الدروس إلى الخادم): في نكهة التوزيع المباشر فقط
    "directImplementation"("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation("androidx.sqlite:sqlite-bundled:2.5.2")
    implementation("androidx.sqlite:sqlite:2.5.2")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
