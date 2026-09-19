package org.murabbie.ahlalhadeeth.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import org.murabbie.ahlalhadeeth.App
import org.murabbie.ahlalhadeeth.data.DataState
import org.murabbie.ahlalhadeeth.data.Repository
import org.murabbie.ahlalhadeeth.ui.screens.AboutScreen
import org.murabbie.ahlalhadeeth.ui.screens.BooksScreen
import org.murabbie.ahlalhadeeth.ui.screens.CategoriesScreen
import org.murabbie.ahlalhadeeth.ui.screens.CategorySegmentsScreen
import org.murabbie.ahlalhadeeth.ui.screens.ChaptersScreen
import org.murabbie.ahlalhadeeth.ui.screens.DownloadsScreen
import org.murabbie.ahlalhadeeth.ui.screens.FavoritesScreen
import org.murabbie.ahlalhadeeth.ui.screens.HistoryScreen
import org.murabbie.ahlalhadeeth.ui.screens.HomeScreen
import org.murabbie.ahlalhadeeth.ui.screens.MoreScreen
import org.murabbie.ahlalhadeeth.ui.screens.PlayerScreen
import org.murabbie.ahlalhadeeth.ui.screens.SearchScreen
import org.murabbie.ahlalhadeeth.ui.screens.SettingsScreen
import org.murabbie.ahlalhadeeth.ui.screens.SetupScreen
import org.murabbie.ahlalhadeeth.ui.screens.StatsScreen
import org.murabbie.ahlalhadeeth.ui.screens.TapeScreen
import org.murabbie.ahlalhadeeth.ui.screens.TapeTranscriptScreen
import org.murabbie.ahlalhadeeth.ui.screens.TranscriptScreen
import org.murabbie.ahlalhadeeth.ui.screens.UserContentScreen
import org.murabbie.ahlalhadeeth.ui.screens.EditTapeScreen
import org.murabbie.ahlalhadeeth.ui.screens.EditSegmentScreen
import org.murabbie.ahlalhadeeth.ui.screens.PasteIndexScreen
import org.murabbie.ahlalhadeeth.ui.screens.AdminsScreen
import org.murabbie.ahlalhadeeth.ui.screens.AutoIndexScreen
import org.murabbie.ahlalhadeeth.ui.screens.AutoIndexBatchScreen
import org.murabbie.ahlalhadeeth.ui.screens.TransferScreen
import org.murabbie.ahlalhadeeth.ui.screens.YouTubeScreen
import org.murabbie.ahlalhadeeth.ui.screens.YouTubeImportScreen
import org.murabbie.ahlalhadeeth.data.Chapter

object Routes {
    const val HOME = "home"
    const val BOOKS = "books/{sheekhId}"
    const val CHAPTERS = "chapters/{sheekhId}/{bookId}"
    const val TAPE = "tape/{code}?seq={seq}"
    const val TRANSCRIPT = "transcript/{contentId}?q={q}"
    const val SEARCH = "search"
    const val CATEGORIES = "categories?parent={parent}"
    const val CATEGORY = "category/{id}"
    const val FAVORITES = "favorites"
    const val MORE = "more"
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"
    const val STATS = "stats"
    const val PLAYER = "player"
    const val ABOUT = "about"
    const val HISTORY = "history"
    const val TAPE_TEXT = "tapetext/{code}?seq={seq}&q={q}"
    const val MANAGE = "manage"
    const val EDIT_TAPE = "edittape/{sheekhId}/{bookId}?code={code}"
    const val EDIT_SEGMENT = "editseg/{code}?id={id}&t={t}"
    const val PASTE_INDEX = "pasteindex/{code}"
    const val YT = "yt/{code}?start={start}"
    const val YT_IMPORT = "ytimport/{sheekhId}/{bookId}?url={url}"
    const val ADMINS = "admins"
    const val AUTO_INDEX = "autoindex/{code}"
    const val AUTO_BATCH = "autobatch/{sheekhId}/{bookId}?auto={auto}"
    const val TRANSFER = "transfer/{sheekhId}/{bookId}?auto={auto}&q={q}"

    fun books(sheekhId: Int) = "books/$sheekhId"
    fun chapters(sheekhId: Int, bookId: Int) = "chapters/$sheekhId/$bookId"
    fun tape(code: Int, seq: Int = 0) = "tape/$code?seq=$seq"
    fun transcript(contentId: Long, q: String = "") = "transcript/$contentId?q=${android.net.Uri.encode(q)}"
    fun categories(parent: Int = 0) = "categories?parent=$parent"
    fun category(id: Int) = "category/$id"
    fun tapeText(code: Int, seq: Int = 0, q: String = "") = "tapetext/$code?seq=$seq&q=${android.net.Uri.encode(q)}"
    fun editTape(sheekhId: Int, bookId: Int, code: Int = 0) = "edittape/$sheekhId/$bookId?code=$code"
    fun editSegment(code: Int, id: Long = 0, t: Long = 0) = "editseg/$code?id=$id&t=$t"
    fun pasteIndex(code: Int) = "pasteindex/$code"
    fun yt(code: Int, startMs: Long = 0) = "yt/$code?start=$startMs"
    fun ytImport(sheekhId: Int = 0, bookId: Int = 0, url: String = "") = "ytimport/$sheekhId/$bookId?url=${android.net.Uri.encode(url)}"
    fun autoIndex(code: Int) = "autoindex/$code"
    fun autoBatch(sheekhId: Int, bookId: Int, auto: Boolean = false) = "autobatch/$sheekhId/$bookId?auto=$auto"
    fun transfer(sheekhId: Int, bookId: Int, auto: Boolean = false, quality: Int = -1) = "transfer/$sheekhId/$bookId?auto=$auto&q=$quality"
}

/**
 * تشغيل درس: دروس يوتيوب تُفتح في شاشة مشغّل يوتيوب (المشغّل الرسمي المضمَّن)، وسواها في المشغّل الأصلي.
 */
fun openMedia(app: App, nav: NavHostController, chapter: Chapter, startMs: Long = 0, playlist: List<Chapter>? = null) {
    if (chapter.isYouTube) {
        app.player.pause()
        nav.navigate(Routes.yt(chapter.code, startMs)) { launchSingleTop = true }
    } else {
        app.player.play(chapter, startMs, playlist)
    }
}

/** فتح شاشة الدرس: يوتيوب → شاشة المشغّل بالفهرس، وسواه → شاشة الشريط */
fun NavHostController.openChapter(chapter: Chapter) {
    if (chapter.isYouTube) navigate(Routes.yt(chapter.code, 0)) else navigate(Routes.tape(chapter.code, 0))
}

data class BottomTab(val route: String, val label: String, val icon: ImageVector)

val bottomTabs = listOf(
    BottomTab(Routes.HOME, "المشايخ", Icons.Filled.Home),
    BottomTab(Routes.SEARCH, "البحث", Icons.Filled.Search),
    BottomTab(Routes.categories(0), "التصانيف", Icons.Outlined.AccountTree),
    BottomTab(Routes.FAVORITES, "المفضلة", Icons.Filled.Favorite),
    BottomTab(Routes.MORE, "المزيد", Icons.Filled.MoreHoriz),
)

@Composable
fun AppRoot(app: App, pendingOpen: MutableState<String?>) {
    val dataState by app.dataManager.state.collectAsState()
    when (val s = dataState) {
        is DataState.Ready -> MainScaffold(app, s.repo, pendingOpen)
        // imePadding: على الأجهزة الحديثة (حافة إلى حافة) لا تُقلِّص النافذة نفسها عند ظهور لوحة المفاتيح، فتُطبَّق مسافتها هنا كي لا تغطي حقول الكتابة
        else -> Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) { SetupScreen(app, dataState) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScaffold(app: App, repo: Repository, pendingOpen: MutableState<String?>) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val playerState by app.player.state.collectAsState()

    LaunchedEffect(Unit) {
        app.dataManager.checkAppUpdateDaily()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { app.sharedSync.pull(force = false) }
    }

    LaunchedEffect(pendingOpen.value) {
        when (pendingOpen.value) {
            "player" -> nav.navigate(Routes.PLAYER) { launchSingleTop = true }
            "downloads" -> nav.navigate(Routes.DOWNLOADS) { launchSingleTop = true }
            "transfer" -> { val t = app.transfer.state.value; nav.navigate(Routes.transfer(t.sheekhId, t.bookId)) { launchSingleTop = true } }
            "autoindex" -> { val t = app.autoIndex.state.value; nav.navigate(Routes.autoBatch(t.sheekhId, t.bookId)) { launchSingleTop = true } }
        }
        pendingOpen.value = null
    }

    val showBars = currentRoute != Routes.PLAYER
    // على الأجهزة الحديثة (حافة إلى حافة) لا تُقلِّص النافذة نفسها عند ظهور لوحة المفاتيح، فتُزاح الشاشة كلها فوقها هنا (imePadding)
    // ويُخفى شريط التنقل السفلي أثناء الكتابة حتى لا يُحجب حقل الكتابة ولا يبقى فراغ تحت المحتوى
    val imeVisible = WindowInsets.isImeVisible
    Scaffold(
        modifier = Modifier.imePadding(),
        // شريط الحالة يعالجه شريط العنوان في كل شاشة، وشريط التنقل السفلي يعالج شريط النظام بنفسه؛ فلا تُضاف هوامش النظام هنا مرة ثانية
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBars && !imeVisible) {
                Column {
                    // شريط نقل الدروس إلى الخادم: ظاهر في كل الشاشات (إلا شاشة النقل نفسها)
                    if (currentRoute?.startsWith("transfer/") != true) {
                        TransferBanner(app = app, onOpen = { val t = app.transfer.state.value; nav.navigate(Routes.transfer(t.sheekhId, t.bookId)) { launchSingleTop = true } })
                    }
                    // شريط التفريغ التلقائي: ظاهر في كل الشاشات (إلا شاشتي التفريغ نفسيهما)
                    if (currentRoute?.startsWith("autobatch/") != true && currentRoute?.startsWith("autoindex/") != true) {
                        AutoIndexBanner(app = app, onOpen = { val t = app.autoIndex.state.value; nav.navigate(Routes.autoBatch(t.sheekhId, t.bookId)) { launchSingleTop = true } })
                    }
                    if (playerState.chapter != null) {
                        MiniPlayer(app = app, onOpen = { nav.navigate(Routes.PLAYER) { launchSingleTop = true } })
                    }
                    NavigationBar {
                        bottomTabs.forEach { tab ->
                            val selected = when (tab.route) {
                                Routes.HOME -> currentRoute == Routes.HOME || currentRoute?.startsWith("books") == true || currentRoute?.startsWith("chapters") == true
                                Routes.SEARCH -> currentRoute == Routes.SEARCH
                                Routes.FAVORITES -> currentRoute == Routes.FAVORITES
                                Routes.MORE -> currentRoute in listOf(Routes.MORE, Routes.DOWNLOADS, Routes.SETTINGS, Routes.STATS, Routes.ABOUT, Routes.HISTORY, Routes.MANAGE, Routes.ADMINS)
                                else -> currentRoute?.startsWith("categor") == true
                            }
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    nav.navigate(tab.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        // ما دام الشريط السفلي معروضًا (أو مخفيًّا مؤقتًا للوحة المفاتيح) فهو الذي يغطي شريط التنقل في النظام، فلا تُضيف الشاشات الداخلية هامشه مرة ثانية
        val navModifier = if (showBars) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier
        NavHost(navController = nav, startDestination = Routes.HOME, modifier = Modifier.fillMaxSize().padding(padding).then(navModifier)) {
            composable(Routes.HOME) { HomeScreen(app, repo, nav) }
            composable(Routes.BOOKS, arguments = listOf(navArgument("sheekhId") { type = NavType.IntType })) {
                BooksScreen(app, repo, nav, it.arguments!!.getInt("sheekhId"))
            }
            composable(Routes.CHAPTERS, arguments = listOf(navArgument("sheekhId") { type = NavType.IntType }, navArgument("bookId") { type = NavType.IntType })) {
                ChaptersScreen(app, repo, nav, it.arguments!!.getInt("sheekhId"), it.arguments!!.getInt("bookId"))
            }
            composable(Routes.TAPE, arguments = listOf(navArgument("code") { type = NavType.IntType }, navArgument("seq") { type = NavType.IntType; defaultValue = 0 })) {
                TapeScreen(app, repo, nav, it.arguments!!.getInt("code"), it.arguments!!.getInt("seq"))
            }
            composable(Routes.TRANSCRIPT, arguments = listOf(navArgument("contentId") { type = NavType.LongType }, navArgument("q") { type = NavType.StringType; defaultValue = "" })) {
                TranscriptScreen(app, repo, nav, it.arguments!!.getLong("contentId"), it.arguments!!.getString("q") ?: "")
            }
            composable(Routes.SEARCH) { SearchScreen(app, repo, nav) }
            composable(Routes.CATEGORIES, arguments = listOf(navArgument("parent") { type = NavType.IntType; defaultValue = 0 })) {
                CategoriesScreen(app, repo, nav, it.arguments!!.getInt("parent"))
            }
            composable(Routes.CATEGORY, arguments = listOf(navArgument("id") { type = NavType.IntType })) {
                CategorySegmentsScreen(app, repo, nav, it.arguments!!.getInt("id"))
            }
            composable(Routes.FAVORITES) { FavoritesScreen(app, repo, nav) }
            composable(Routes.MORE) { MoreScreen(app, repo, nav) }
            composable(Routes.DOWNLOADS) { DownloadsScreen(app, repo, nav) }
            composable(Routes.SETTINGS) { SettingsScreen(app, repo, nav) }
            composable(Routes.STATS) { StatsScreen(app, repo, nav) }
            composable(Routes.PLAYER) { PlayerScreen(app, repo, nav) }
            composable(Routes.ABOUT) { AboutScreen(app, repo, nav) }
            composable(Routes.HISTORY) { HistoryScreen(app, repo, nav) }
            composable(Routes.MANAGE) { UserContentScreen(app, repo, nav) }
            composable(Routes.ADMINS) { AdminsScreen(app, repo, nav) }
            composable(Routes.AUTO_INDEX, arguments = listOf(navArgument("code") { type = NavType.IntType })) {
                AutoIndexScreen(app, repo, nav, it.arguments!!.getInt("code"))
            }
            composable(Routes.TRANSFER, arguments = listOf(navArgument("sheekhId") { type = NavType.IntType }, navArgument("bookId") { type = NavType.IntType }, navArgument("auto") { type = NavType.BoolType; defaultValue = false }, navArgument("q") { type = NavType.IntType; defaultValue = -1 })) {
                TransferScreen(app, repo, nav, it.arguments!!.getInt("sheekhId"), it.arguments!!.getInt("bookId"), it.arguments!!.getBoolean("auto"), it.arguments!!.getInt("q"))
            }
            composable(Routes.AUTO_BATCH, arguments = listOf(navArgument("sheekhId") { type = NavType.IntType }, navArgument("bookId") { type = NavType.IntType }, navArgument("auto") { type = NavType.BoolType; defaultValue = false })) {
                AutoIndexBatchScreen(app, repo, nav, it.arguments!!.getInt("sheekhId"), it.arguments!!.getInt("bookId"), it.arguments!!.getBoolean("auto"))
            }
            composable(Routes.EDIT_TAPE, arguments = listOf(navArgument("sheekhId") { type = NavType.IntType }, navArgument("bookId") { type = NavType.IntType }, navArgument("code") { type = NavType.IntType; defaultValue = 0 })) {
                EditTapeScreen(app, repo, nav, it.arguments!!.getInt("sheekhId"), it.arguments!!.getInt("bookId"), it.arguments!!.getInt("code"))
            }
            composable(Routes.EDIT_SEGMENT, arguments = listOf(navArgument("code") { type = NavType.IntType }, navArgument("id") { type = NavType.LongType; defaultValue = 0L }, navArgument("t") { type = NavType.LongType; defaultValue = 0L })) {
                EditSegmentScreen(app, repo, nav, it.arguments!!.getInt("code"), it.arguments!!.getLong("id"), it.arguments!!.getLong("t"))
            }
            composable(Routes.YT, arguments = listOf(navArgument("code") { type = NavType.IntType }, navArgument("start") { type = NavType.LongType; defaultValue = 0L })) {
                YouTubeScreen(app, repo, nav, it.arguments!!.getInt("code"), it.arguments!!.getLong("start"))
            }
            composable(Routes.YT_IMPORT, arguments = listOf(navArgument("sheekhId") { type = NavType.IntType }, navArgument("bookId") { type = NavType.IntType }, navArgument("url") { type = NavType.StringType; defaultValue = "" })) {
                YouTubeImportScreen(app, repo, nav, it.arguments!!.getInt("sheekhId"), it.arguments!!.getInt("bookId"), it.arguments!!.getString("url") ?: "")
            }
            composable(Routes.PASTE_INDEX, arguments = listOf(navArgument("code") { type = NavType.IntType })) {
                PasteIndexScreen(app, repo, nav, it.arguments!!.getInt("code"))
            }
            composable(Routes.TAPE_TEXT, arguments = listOf(navArgument("code") { type = NavType.IntType }, navArgument("seq") { type = NavType.IntType; defaultValue = 0 }, navArgument("q") { type = NavType.StringType; defaultValue = "" })) {
                TapeTranscriptScreen(app, repo, nav, it.arguments!!.getInt("code"), it.arguments!!.getInt("seq"), it.arguments!!.getString("q") ?: "")
            }
        }
    }
}

fun NavHostController.openTape(code: Int, seq: Int = 0) = navigate(Routes.tape(code, seq))
