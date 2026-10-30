package com.cipherpath.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cipherpath.AppContainer
import com.cipherpath.data.db.JobFilter
import com.cipherpath.data.net.DeepLinks
import com.cipherpath.ui.EmptyState
import com.cipherpath.ui.JobCard
import com.cipherpath.ui.Nav
import com.cipherpath.ui.Panel
import com.cipherpath.ui.openExternal
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.Duration

private const val PAGE = 30

private val SORTS = listOf("newest" to "Newest", "match" to "Best match", "posted" to "Posting date", "salary" to "Salary", "company" to "Company A–Z")
private val MODES = listOf("remote" to "Remote", "hybrid" to "Hybrid", "onsite" to "On-site")
private val LEVELS = listOf("intern", "entry", "junior", "mid", "senior", "lead", "principal", "architect", "manager")
private val POSTED = listOf(1 to "24 h", 3 to "3 days", 7 to "Week", 30 to "Month")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DiscoverScreen(c: AppContainer, nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by remember {
        mutableStateOf(JobFilter(
            workMode = if (settings.preferRemote) "remote" else null,
            country = if (settings.preferIndia && !settings.preferRemote) "IN" else null,
        ))
    }
    var limit by remember { mutableIntStateOf(PAGE) }
    var showFilters by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val effective = filter.copy(query = query.ifBlank { null })
    LaunchedEffect(effective) { limit = PAGE }
    val jobs by remember(effective, limit) { c.jobs.search(effective, limit, 0) }.collectAsStateWithLifecycle(null)
    val listState = rememberLazyListState()
    val atEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 3 } ?: false } }
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow { atEnd }.distinctUntilChanged().collect { end ->
            val n = jobs?.size ?: 0
            if (end && n >= limit) limit += PAGE
        }
    }
    val activeFilters = listOfNotNull(filter.roleGroup, filter.roleCategory, filter.country, filter.workMode, filter.seniority,
        filter.company, filter.skill, filter.minSalary, filter.postedAfter, filter.minMatch, if (filter.bookmarkedOnly) 1 else null).size

    ScreenScaffold(
        title = "Discover",
        actions = {
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.Outlined.Sort, "Sort") }
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    SORTS.forEach { (k, l) ->
                        DropdownMenuItem(text = { Text(if (filter.sort == k) "✓ $l" else l) }, onClick = { filter = filter.copy(sort = k); sortMenu = false })
                    }
                }
            }
            IconButton(onClick = { showFilters = true }) { Icon(Icons.Outlined.FilterList, "Filters") }
        },
    ) { pad ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it.take(100) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Title, company, skill or city") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Clear") } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    shape = MaterialTheme.shapes.large,
                )
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(filter.roleGroup == "cyber", { filter = filter.copy(roleGroup = if (filter.roleGroup == "cyber") null else "cyber", roleCategory = null) }, { Text("Cyber") })
                    FilterChip(filter.roleGroup == "fde", { filter = filter.copy(roleGroup = if (filter.roleGroup == "fde") null else "fde", roleCategory = null) }, { Text("FDE") })
                    FilterChip(filter.workMode == "remote", { filter = filter.copy(workMode = if (filter.workMode == "remote") null else "remote") }, { Text("Remote") })
                    FilterChip(filter.country == "IN", { filter = filter.copy(country = if (filter.country == "IN") null else "IN") }, { Text("India") })
                    FilterChip(filter.bookmarkedOnly, { filter = filter.copy(bookmarkedOnly = !filter.bookmarkedOnly) }, { Text("Saved") })
                    if (activeFilters > 0) AssistChip(onClick = { filter = JobFilter(sort = filter.sort) }, label = { Text("Clear ($activeFilters)") })
                }
            }
            val list = jobs
            when {
                list == null -> item { com.cipherpath.ui.Loading() }
                list.isEmpty() -> item {
                    EmptyState(Icons.Outlined.SearchOff, "No matching jobs",
                        "Try fewer filters, run a sync, or search the sites below that we can't import directly.")
                }
                else -> items(list, key = { it.id }) { j ->
                    JobCard(j, { nav.job(j.id) }, { scope.launch { c.jobs.setBookmarked(j.id, !j.bookmarked) } })
                }
            }
            item {
                Spacer(Modifier.height(12.dp))
                Panel {
                    Text("Search other job sites", style = MaterialTheme.typography.titleSmall)
                    Text("These sites don't offer a permitted public API, so we open their own search with your keywords. No personal data is sent.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    val q = query.ifBlank { if (filter.roleGroup == "fde") "forward deployed engineer" else "security engineer" }
                    val loc = if (filter.country == "IN") "India" else ""
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DeepLinks.build(q, loc, filter.workMode == "remote").forEach { l ->
                            OutlinedButton(onClick = { openExternal(ctx, l.url) }) { Text(l.label) }
                        }
                    }
                }
            }
        }
    }

    if (showFilters) {
        ModalBottomSheet(onDismissRequest = { showFilters = false }) {
            FilterSheet(c, filter, onApply = { filter = it; showFilters = false })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(c: AppContainer, initial: JobFilter, onApply: (JobFilter) -> Unit) {
    var f by remember { mutableStateOf(initial) }
    var salary by remember { mutableStateOf(initial.minSalary?.toLong()?.toString() ?: "") }
    var skill by remember { mutableStateOf(initial.skill ?: "") }
    val companies by c.db.jobs().companyCounts().collectAsStateWithLifecycle(emptyList())
    val roles = c.taxonomy.data.roles.filter { f.roleGroup == null || it.group == f.roleGroup }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
        Text("Filters", style = MaterialTheme.typography.titleLarge)
        Label("Role")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            roles.forEach { r -> FilterChip(f.roleCategory == r.id, { f = f.copy(roleCategory = if (f.roleCategory == r.id) null else r.id) }, { Text(r.label) }) }
        }
        Label("Work mode")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MODES.forEach { (k, l) -> FilterChip(f.workMode == k, { f = f.copy(workMode = if (f.workMode == k) null else k) }, { Text(l) }) }
        }
        Label("Location")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("IN" to "India", "US" to "United States", "GB" to "United Kingdom").forEach { (k, l) ->
                FilterChip(f.country == k, { f = f.copy(country = if (f.country == k) null else k) }, { Text(l) })
            }
        }
        Label("Experience level")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LEVELS.forEach { k -> FilterChip(f.seniority == k, { f = f.copy(seniority = if (f.seniority == k) null else k) }, { Text(k.replaceFirstChar { it.uppercase() }) }) }
        }
        Label("Posted within")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            POSTED.forEach { (d, l) ->
                val v = System.currentTimeMillis() - Duration.ofDays(d.toLong()).toMillis()
                val sel = f.postedAfter != null && kotlin.math.abs((f.postedAfter ?: 0) - v) < Duration.ofHours(12).toMillis()
                FilterChip(sel, { f = f.copy(postedAfter = if (sel) null else v) }, { Text(l) })
            }
        }
        Label("Minimum match")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(50, 60, 70, 80).forEach { m -> FilterChip(f.minMatch == m, { f = f.copy(minMatch = if (f.minMatch == m) null else m) }, { Text("$m%+") }) }
        }
        Label("Skill")
        OutlinedTextField(skill, { skill = it.take(40) }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("e.g. Kubernetes, SIEM, Python") })
        Label("Minimum salary (posted ranges only)")
        OutlinedTextField(salary, { salary = it.filter(Char::isDigit).take(9) }, Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), placeholder = { Text("Annual, in the posting's currency") })
        Label("Company")
        var open by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(f.company ?: "Any company") }
            DropdownMenu(open, { open = false }) {
                DropdownMenuItem(text = { Text("Any company") }, onClick = { f = f.copy(company = null); open = false })
                companies.forEach { row -> row.key?.let { k -> DropdownMenuItem(text = { Text("$k (${row.n})") }, onClick = { f = f.copy(company = k); open = false }) } }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { f = JobFilter(sort = f.sort); salary = ""; skill = "" }) { Text("Reset") }
            Spacer(Modifier.weight(1f))
            Button(onClick = {
                val canonicalSkill = c.taxonomy.data.skills.firstOrNull { it.name.equals(skill.trim(), true) || it.aliases.any { a -> a.equals(skill.trim(), true) } }?.name
                onApply(f.copy(minSalary = salary.toDoubleOrNull(), skill = canonicalSkill ?: skill.trim().ifBlank { null }))
            }) { Text("Show jobs") }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Label(t: String) {
    Text(t, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}
