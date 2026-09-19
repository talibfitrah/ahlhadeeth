plugins {
    kotlin("jvm") version "2.0.21"
    application
}
repositories { google(); mavenCentral() }
dependencies {
    implementation("androidx.sqlite:sqlite-bundled-jvm:2.5.2")
    implementation("androidx.sqlite:sqlite-jvm:2.5.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
}
kotlin { jvmToolchain(21) }
sourceSets {
    main {
        kotlin.srcDir("../android/app/src/main/java/org/murabbie/ahlalhadeeth/data")
        kotlin.exclude("**/Mp4Mux.kt", "**/AacAdts.kt", "**/AutoIndexService.kt", "**/Maintenance.kt", "**/AudioSource.kt", "**/AudioDownloads.kt", "**/DownloadService.kt", "**/DataDownloadService.kt", "**/DataManager.kt", "**/Settings.kt", "**/UserDb.kt", "**/SharedSync.kt", "**/TransferJob.kt", "**/TransferService.kt", "**/YouTubeMedia.kt")
    }
}
application { mainClass.set("TestMainKt") }
