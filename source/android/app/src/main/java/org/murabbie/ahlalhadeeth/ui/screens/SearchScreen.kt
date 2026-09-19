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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.murabbie.ahlalhadeeth.App
import org.murabbie.ahlalhadeeth.data.ArabicText
import org.murabbie.ahlalhadeeth.data.Book
import org.murabbie.ahlalhadeeth.data.Repository
import org.murabbie.ahlalhadeeth.data.SearchOptions
import org.murabbie.ahlalhadeeth.data.SegmentWithChapter
import org.murabbie.ahlalhadeeth.data.Sheekh
import org.murabbie.ahlalhadeeth.ui.AppTopBar
import org.murabbie.ahlalhadeeth.ui.openMedia
import org.murabbie.ahlalhadeeth.ui.Routes
import org.murabbie.ahlalhadeeth.ui.SegmentRow

class SearchViewModel : ViewModel() {
    val query = MutableStateFlow("")
    val matchAll = MutableStateFlow(true)
    val inLine = MutableStateFlow(true)
    val inWrite = MutableStateFlow(true)
    val exact = MutableStateFlow(false)
    val questionsOnly = MutableStateFlow(false)
    val literal = MutableStateFlow(false)
    val sheekhIds = MutableStateFlow<Set<Int>>(emptySet())
    val bookIds = MutableStateFlow<Set<Int>>(emptySet())
    val results = MutableStateFlow<List<SegmentWithChapter>?>(null)
    val total = MutableStateFlow(-1L)
    val searching = MutableStateFlow(false)
    val progress = MutableStateFlow(0)
    val error = MutableStateFlow<String?>(null)
    val lastWords = MutableStateFlow<List<String>>(emptyList())
    private var job: Job? = null
    private val pageSize = 200

    private fun options(offset: Int) = SearchOptions(
        query = query.value, matchAll = matchAll.value, inLine = inLine.value || !inWrite.value, inWrite = inWrite.value,
        exactPhrase = exact.value, sheekhIds = sheekhIds.value, bookIds = bookIds.value, questionsOnly = questionsOnly.value,
        limit = pageSize, offset = offset,
    )

    fun search(repo: Repository, app: App) {
        val q = query.value.trim()
        if (q.isEmpty()) return
        job?.cancel()
        job = viewModelScope.launch {
            searching.value = true
            error.value = null
            progress.value = 0
            lastWords.value = ArabicText.queryWords(q)
            try {
                app.userDb.addWord(q)
                if (literal.value) {
                    results.value = repo.literalSearch(options(0).copy(limit = 1000)) { progress.value = it }
                    total.value = results.value?.size?.toLong() ?: 0
                } else {
                    val opts = options(0)
                    results.value = repo.search(opts)
                    total.value = repo.searchCount(opts)
                }
            } catch (e: Exception) {
                error.value = "خطأ في البحث: ${e.message}"
                results.value = emptyList()
            } finally {
                searching.value = false
            }
        }
    }

    fun loadMore(repo: Repository) {
        val cur = results.value ?: return
        if (literal.value || searching.value || cur.size >= total.value) return
        job = viewModelScope.launch {
            searching.value = true
            try {
                val more = repo.search(options(cur.size))
                results.value = cur + more
            } catch (e: Exception) {
                error.value = e.message
            } finally {
                searching.value = false
            }
        }
    }

    fun clear() {
        job?.cancel()
        results.value = null
        total.value = -1
        query.value = ""
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(app: App, repo: Repository, nav: NavHostController) {
    val vm: SearchViewModel = viewModel()
    val query by vm.query.collectAsState()
    val matchAll by vm.matchAll.collectAsState()
    val inLine by vm.inLine.collectAsState()
    val inWrite by vm.inWrite.collectAsState()
    val exact by vm.exact.collectAsState()
    val questionsOnly by vm.questionsOnly.collectAsState()
    val literal by vm.literal.collectAsState()
    val sheekhIds by vm.sheekhIds.collectAsState()
    val bookIds by vm.bookIds.collectAsState()
    val results by vm.results.collectAsState()
    val total by vm.total.collectAsState()
    val searching by vm.searching.collectAsState()
    val progress by vm.progress.collectAsState()
    val error by vm.error.collectAsState()
    val words by vm.lastWords.collectAsState()
    val playerState by app.player.state.collectAsState()

    var sheekhs by remember { mutableStateOf<List<Sheekh>>(emptyList()) }
    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var history by remember { mutableStateOf<List<String>>(emptyList()) }
    var showFilters by remember { mutableStateOf(false) }
    var showBooks by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        sheekhs = repo.sheekhs()
        books = repo.books()
    }
    LaunchedEffect(query, results) { history = app.userDb.words(if (results == null) query else "") }

    Scaffold(topBar = {
        AppTopBar("البحث", subtitle = "في فهارس المقاطع والتفريغات", actions = {
            IconButton(onClick = { showFilters = !showFilters }) { Icon(Icons.Filled.FilterList, contentDescription = "خيارات البحث") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { vm.query.value = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                placeholder = { Text("اكتب كلمة أو أكثر…") },
                singleLine = true,
                leadingIcon = { IconButton(onClick = { keyboard?.hide(); vm.search(repo, app) }) { Icon(Icons.Filled.Search, contentDescription = "بحث") } },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { vm.clear() }) { Icon(Icons.Filled.Clear, contentDescription = "مسح") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); vm.search(repo, app) }),
            )
            // خيارات سريعة
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = matchAll, onClick = { vm.matchAll.value = true }, label = { Text("كل الكلمات (و)") })
                FilterChip(selected = !matchAll, onClick = { vm.matchAll.value = false }, label = { Text("أي كلمة (أو)") })
                FilterChip(selected = inLine, onClick = { vm.inLine.value = !inLine }, label = { Text("الفهارس") })
                FilterChip(selected = inWrite, onClick = { vm.inWrite.value = !inWrite }, label = { Text("التفريغات") })
                FilterChip(selected = exact, onClick = { vm.exact.value = !exact }, label = { Text("عبارة كاملة") })
                FilterChip(selected = questionsOnly, onClick = { vm.questionsOnly.value = !questionsOnly }, label = { Text("الأسئلة فقط") })
            }
            if (showFilters) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("تصفية بالشيخ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = sheekhIds.isEmpty(), onClick = { vm.sheekhIds.value = emptySet(); vm.bookIds.value = emptySet() }, label = { Text("الكل") })
                        sheekhs.forEach { s ->
                            FilterChip(
                                selected = s.id in sheekhIds,
                                onClick = { vm.sheekhIds.value = if (s.id in sheekhIds) sheekhIds - s.id else sheekhIds + s.id },
                                label = { Text(s.name.removePrefix("الشيخ ").trim()) },
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { showBooks = true }) { Text(if (bookIds.isEmpty()) "تصفية بالكتاب/السلسلة" else "الكتب المختارة: ${ArabicText.arabicDigits(bookIds.size)}") }
                        if (bookIds.isNotEmpty()) TextButton(onClick = { vm.bookIds.value = emptySet() }) { Text("إلغاء") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = literal, onCheckedChange = { vm.literal.value = it })
                        Spacer(Modifier.width(8.dp))
                        Text("بحث حرفي داخل الكلمات (أبطأ؛ يفحص كل النصوص كما في نسخة ويندوز)", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (results == null && history.isNotEmpty()) {
                Text("كلمات سابقة", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    history.forEach { w ->
                        FilterChip(selected = false, onClick = { vm.query.value = w; vm.search(repo, app) }, label = { Text(w) })
                    }
                }
            }
            if (searching) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(if (literal && progress > 0) "جارٍ الفحص… ${ArabicText.arabicDigits(progress)} مقطع" else "جارٍ البحث…")
                }
            }
            error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
            val res = results
            if (res != null) {
                Text(
                    if (total >= 0) "النتائج: ${ArabicText.arabicDigits(total)}" + (if (res.size < total) " (معروض ${ArabicText.arabicDigits(res.size)})" else "") else "",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium,
                )
                HorizontalDivider()
                if (res.isEmpty() && !searching) {
                    Text("لا نتائج. جرّب كلمات أخرى أو فعّل «أي كلمة» أو البحث الحرفي.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(res, key = { it.segment.id }) { r ->
                        SegmentRow(
                            segment = r.segment,
                            chapter = r.chapter,
                            words = words,
                            snippet = r.snippet,
                            isCurrent = playerState.chapter?.code == r.segment.code && playerState.positionMs >= r.segment.offsetStart && playerState.positionMs < r.segment.offsetStart + 60_000,
                            showChapter = true,
                            onClick = { nav.navigate(Routes.tape(r.segment.code, r.segment.seq)) },
                            onLongClick = {
                                if (r.segment.hasWrite) nav.navigate(Routes.tapeText(r.segment.code, r.segment.seq, query)) else openMedia(app, nav, r.chapter, r.segment.offsetStart)
                            },
                        )
                    }
                    if (res.size < total && !literal) {
                        item {
                            TextButton(onClick = { vm.loadMore(repo) }, Modifier.fillMaxWidth()) { Text("عرض المزيد") }
                        }
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    if (showBooks) {
        val candidates = if (sheekhIds.isEmpty()) books else books // كل الكتب (الشيوخ يتشاركون كتبًا)
        AlertDialog(
            onDismissRequest = { showBooks = false },
            title = { Text("اختيار الكتب") },
            text = {
                LazyColumn(Modifier.height(400.dp)) {
                    items(candidates, key = { it.id }) { b ->
                        Row(Modifier.fillMaxWidth().clickable { vm.bookIds.value = if (b.id in bookIds) bookIds - b.id else bookIds + b.id }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = b.id in bookIds, onCheckedChange = { vm.bookIds.value = if (it) bookIds + b.id else bookIds - b.id })
                            Column {
                                Text(b.name)
                                Text(b.typeName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showBooks = false }) { Text("تم") } },
            dismissButton = { TextButton(onClick = { vm.bookIds.value = emptySet(); showBooks = false }) { Text("إلغاء التحديد") } },
        )
    }
}
