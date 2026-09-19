package org.murabbie.ahlalhadeeth.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import org.murabbie.ahlalhadeeth.App
import org.murabbie.ahlalhadeeth.data.ArabicText
import org.murabbie.ahlalhadeeth.data.Category
import org.murabbie.ahlalhadeeth.data.Repository
import org.murabbie.ahlalhadeeth.data.SegmentWithChapter
import org.murabbie.ahlalhadeeth.data.Sheekh
import org.murabbie.ahlalhadeeth.ui.AppTopBar
import org.murabbie.ahlalhadeeth.ui.openMedia
import org.murabbie.ahlalhadeeth.ui.Loading
import org.murabbie.ahlalhadeeth.ui.Routes
import org.murabbie.ahlalhadeeth.ui.SegmentRow

/** شجرة التصانيف الفقهية (نافذة «التصانيف» في نسخة ويندوز) */
@Composable
fun CategoriesScreen(app: App, repo: Repository, nav: NavHostController, parent: Int) {
    var children by remember { mutableStateOf<List<Category>?>(null) }
    var path by remember { mutableStateOf<List<Category>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Category>>(emptyList()) }

    LaunchedEffect(parent) {
        children = repo.categoryChildren(parent)
        path = if (parent > 0) repo.categoryPath(parent) else emptyList()
    }
    LaunchedEffect(query) { hits = if (query.trim().length >= 2) repo.searchCategories(query.trim()) else emptyList() }

    val current = path.lastOrNull()
    Scaffold(topBar = {
        AppTopBar(current?.name ?: "التصانيف الفقهية", nav = if (parent > 0) nav else null, subtitle = if (current != null) "${ArabicText.arabicDigits(current.totalCount)} مقطع في هذا الفرع" else "تصفح المقاطع بحسب الموضوع")
    }) { padding ->
        val list = children
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                placeholder = { Text("بحث في أسماء التصانيف") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            )
            if (path.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { nav.navigate(Routes.categories(0)) }) { Text("الجذر") }
                    path.forEach { c ->
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                        TextButton(onClick = { nav.navigate(Routes.categories(c.id)) }) { Text(c.name) }
                    }
                }
                HorizontalDivider()
            }
            if (hits.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(hits, key = { it.id }) { c ->
                        CategoryRow(c, onOpen = { if (c.childCount > 0) nav.navigate(Routes.categories(c.id)) else nav.navigate(Routes.category(c.id)) }, onSegments = { nav.navigate(Routes.category(c.id)) })
                    }
                }
                return@Column
            }
            if (list == null) {
                Loading(); return@Column
            }
            LazyColumn(Modifier.fillMaxSize()) {
                if (current != null) {
                    item {
                        ListItem(
                            headlineContent = { Text("كل مقاطع «${current.name}» (${ArabicText.arabicDigits(current.totalCount)})", color = MaterialTheme.colorScheme.primary) },
                            leadingContent = { Icon(Icons.Outlined.Label, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.clickable { nav.navigate(Routes.category(current.id)) },
                        )
                        HorizontalDivider()
                    }
                }
                items(list, key = { it.id }) { c ->
                    CategoryRow(c, onOpen = { if (c.childCount > 0) nav.navigate(Routes.categories(c.id)) else nav.navigate(Routes.category(c.id)) }, onSegments = { nav.navigate(Routes.category(c.id)) })
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

@Composable
private fun CategoryRow(c: Category, onOpen: () -> Unit, onSegments: () -> Unit) {
    ListItem(
        headlineContent = { Text(c.name) },
        supportingContent = {
            val parts = ArrayList<String>()
            if (c.childCount > 0) parts.add("${ArabicText.arabicDigits(c.childCount)} فرع")
            parts.add("${ArabicText.arabicDigits(c.totalCount)} مقطع")
            Text(parts.joinToString(" — "), style = MaterialTheme.typography.labelMedium)
        },
        leadingContent = { Icon(if (c.childCount > 0) Icons.Filled.Folder else Icons.Outlined.Label, contentDescription = null, tint = if (c.childCount > 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary) },
        trailingContent = {
            if (c.childCount > 0 && c.totalCount > 0) IconButton(onClick = onSegments) { Icon(Icons.Outlined.Label, contentDescription = "عرض المقاطع") }
        },
        modifier = Modifier.clickable(onClick = onOpen),
    )
    HorizontalDivider()
}

/** مقاطع تصنيف معيّن مع تصفية بالشيخ */
@Composable
fun CategorySegmentsScreen(app: App, repo: Repository, nav: NavHostController, categoryId: Int) {
    var category by remember { mutableStateOf<Category?>(null) }
    var path by remember { mutableStateOf<List<Category>>(emptyList()) }
    var sheekhs by remember { mutableStateOf<List<Sheekh>>(emptyList()) }
    var sheekhId by remember { mutableStateOf(0) }
    var includeChildren by remember { mutableStateOf(true) }
    var items by remember { mutableStateOf<List<SegmentWithChapter>?>(null) }
    var total by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(false) }
    val playerState by app.player.state.collectAsState()
    val page = 200

    LaunchedEffect(categoryId) {
        category = repo.category(categoryId)
        path = repo.categoryPath(categoryId)
        sheekhs = repo.sheekhs()
    }
    LaunchedEffect(categoryId, sheekhId, includeChildren) {
        loading = true
        items = null
        total = repo.countSegmentsOfCategory(categoryId, includeChildren, sheekhId)
        items = repo.segmentsOfCategory(categoryId, includeChildren, sheekhId, page, 0)
        loading = false
    }

    Scaffold(topBar = {
        AppTopBar(category?.name ?: "", nav, subtitle = path.dropLast(1).joinToString(" ← ") { it.name }.ifBlank { "التصانيف الفقهية" })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = sheekhId == 0, onClick = { sheekhId = 0 }, label = { Text("كل المشايخ") })
                sheekhs.forEach { s -> FilterChip(selected = sheekhId == s.id, onClick = { sheekhId = s.id }, label = { Text(s.name.removePrefix("الشيخ ").trim()) }) }
            }
            if ((category?.childCount ?: 0) > 0) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = includeChildren, onCheckedChange = { includeChildren = it })
                    Spacer(Modifier.width(8.dp))
                    Text("تضمين الفروع", style = MaterialTheme.typography.labelMedium)
                }
            }
            Text("${ArabicText.arabicDigits(total)} مقطع", Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
            HorizontalDivider()
            val list = items
            if (list == null) {
                Loading(); return@Column
            }
            if (list.isEmpty()) Text("لا مقاطع في هذا التصنيف للاختيار الحالي.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.segment.id }) { r ->
                    SegmentRow(
                        segment = r.segment, chapter = r.chapter, showChapter = true,
                        isCurrent = playerState.chapter?.code == r.segment.code && playerState.positionMs >= r.segment.offsetStart && playerState.positionMs < r.segment.offsetStart + 60_000,
                        onClick = { nav.navigate(Routes.tape(r.segment.code, r.segment.seq)) },
                        onLongClick = { if (r.segment.hasWrite) nav.navigate(Routes.transcript(r.segment.id)) else openMedia(app, nav, r.chapter, r.segment.offsetStart) },
                    )
                }
                if (list.size < total) {
                    item {
                        if (loading) CircularProgressIndicator(Modifier.padding(16.dp))
                        else TextButton(onClick = {
                            loading = true
                        }, Modifier.fillMaxWidth()) { Text("عرض المزيد (${ArabicText.arabicDigits(total - list.size)} متبقٍ)") }
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
            LaunchedEffect(loading) {
                if (loading && list.isNotEmpty() && list.size < total) {
                    val more = repo.segmentsOfCategory(categoryId, includeChildren, sheekhId, page, list.size)
                    items = list + more
                    loading = false
                }
            }
        }
    }
}
