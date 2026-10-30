package com.cipherpath.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.WorkOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cipherpath.AppContainer
import com.cipherpath.analysis.MatchResult
import com.cipherpath.data.db.JobEntity
import com.cipherpath.data.db.Stage
import com.cipherpath.data.repo.Ingest
import com.cipherpath.ui.Bar
import com.cipherpath.ui.EmptyState
import com.cipherpath.ui.Loading
import com.cipherpath.ui.Nav
import com.cipherpath.ui.Panel
import com.cipherpath.ui.Pill
import com.cipherpath.ui.ScoreRing
import com.cipherpath.ui.SectionTitle
import com.cipherpath.ui.SkillChips
import com.cipherpath.ui.fmtDate
import com.cipherpath.ui.fmtSalary
import com.cipherpath.ui.isDark
import com.cipherpath.ui.openExternal
import com.cipherpath.ui.prettyMode
import com.cipherpath.ui.prettySeniority
import com.cipherpath.ui.prettySource
import com.cipherpath.ui.theme.scoreColor
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JobDetailScreen(c: AppContainer, nav: Nav, id: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val job by remember(id) { c.jobs.observe(id) }.collectAsStateWithLifecycle(null)
    val matchRows by remember(id) { c.db.matches().forJob(id) }.collectAsStateWithLifecycle(emptyList())
    val resumes by c.resumes.observeAll().collectAsStateWithLifecycle(emptyList())
    val app by remember(id) { c.applications.observeForJob(id) }.collectAsStateWithLifecycle(null)
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(id) { c.jobs.markViewed(id); kotlinx.coroutines.delay(400); loaded = true }
    var selectedResume by remember { mutableStateOf<String?>(null) }

    val j = job
    ScreenScaffold(
        title = j?.companyName ?: "Job",
        onBack = nav::back,
        snackbar = snack,
        actions = {
            if (j != null) {
                IconButton(onClick = { scope.launch { c.jobs.setBookmarked(j.id, !j.bookmarked) } }) {
                    Icon(if (j.bookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, "Bookmark")
                }
                IconButton(onClick = {
                    if (j.id in nav.compare) nav.compare.remove(j.id)
                    else { if (nav.compare.size >= 3) nav.compare.removeAt(0); nav.compare.add(j.id) }
                    scope.launch { snack.showSnackbar("${nav.compare.size} job(s) in comparison", actionLabel = "Compare").let { r ->
                        if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) nav.to("compare") } }
                }) { Icon(Icons.Outlined.Compare, "Compare") }
            }
        },
    ) { pad ->
        if (j == null) {
            if (!loaded) Loading() else EmptyState(Icons.Outlined.WorkOff, "Job not found", "This posting may have been removed from the source.")
            return@ScreenScaffold
        }
        ScrollBody(pad) {
            Text(j.title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(listOf(j.companyName, j.location.ifBlank { null }).filterNotNull().joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                prettyMode(j.workMode).takeIf { it.isNotEmpty() }?.let { Pill(it) }
                prettySeniority(j.seniority).takeIf { it.isNotEmpty() }?.let { Pill(it) }
                j.roleCategory?.let { c.taxonomy.roleById[it]?.label }?.let { Pill(it) }
                j.employmentType?.takeIf { it.isNotBlank() }?.let { Pill(it) }
                j.minYears?.let { Pill("$it+ yrs") }
                fmtSalary(j.salaryMin, j.salaryMax, j.salaryCurrency)?.let { Pill(it, MaterialTheme.colorScheme.primary) }
            }
            if (!j.isActive) {
                Spacer(Modifier.height(10.dp))
                com.cipherpath.ui.ErrorBanner("This posting is no longer listed on the source and may be closed.")
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    openExternal(ctx, j.applyUrl)
                    scope.launch {
                        val a = app
                        if (a == null) {
                            val r = snack.showSnackbar("Track this application?", actionLabel = "Mark applied")
                            if (r == androidx.compose.material3.SnackbarResult.ActionPerformed)
                                c.applications.createFromJob(j, j.bestResumeId, Stage.APPLIED)
                        }
                    }
                }, modifier = Modifier.weight(1f)) { Text("Apply on ${prettySource(j.source)}") }
                OutlinedButton(onClick = {
                    scope.launch {
                        val a = app
                        if (a != null) nav.application(a.id)
                        else { val newId = c.applications.createFromJob(j, j.bestResumeId); nav.application(newId) }
                    }
                }, modifier = Modifier.weight(1f)) { Text(if (app != null) "In tracker · ${app?.stage}" else "Save to tracker") }
            }
            Text("Apply opens the employer's page in your browser. CipherPath never submits applications or shares your data.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))

            SectionTitle("Match")
            if (resumes.isEmpty()) {
                Panel {
                    Text("Add a resume to see how well you match this role.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { nav.tab("resumes") }) { Text("Add resume") }
                }
            } else {
                val names = resumes.associate { it.id to it.name }
                val chosen = selectedResume ?: matchRows.firstOrNull()?.resumeId
                if (matchRows.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    matchRows.forEach { m ->
                        FilterChip(chosen == m.resumeId, { selectedResume = m.resumeId }, { Text("${names[m.resumeId] ?: "Resume"} · ${m.score}%") })
                    }
                }
                val row = matchRows.firstOrNull { it.resumeId == chosen }
                if (row == null) Loading() else {
                    val r = remember(row.resultJson) { c.matches.decodeMatch(row.resultJson) }
                    MatchBreakdown(r, isBest = row.resumeId == matchRows.first().resumeId && matchRows.size > 1)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { nav.ats(j.id) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.FactCheck, null); Spacer(Modifier.width(8.dp)); Text("Run ATS check against this JD")
                }
            }

            val skills = Ingest.csv(j.skillsCsv)
            if (skills.isNotEmpty()) {
                SectionTitle("Skills mentioned")
                SkillChips(skills, MaterialTheme.colorScheme.onSecondaryContainer, MaterialTheme.colorScheme.secondaryContainer)
            }

            SectionTitle("Job description")
            Panel {
                SelectionContainer { Text(j.description.ifBlank { "The source did not include a description." }, style = MaterialTheme.typography.bodyMedium) }
            }
            SectionTitle("Source")
            Panel {
                InfoRow("Source", prettySource(j.source))
                InfoRow("Posted", fmtDate(j.postedAt))
                InfoRow("First seen", fmtDate(j.firstSeenAt))
                InfoRow("Last checked", fmtDate(j.lastSeenAt))
                j.department?.let { InfoRow("Team", it) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                CompanyLinks(c, j)
            }
        }
    }
}

@Composable
private fun CompanyLinks(c: AppContainer, j: JobEntity) {
    val ctx = LocalContext.current
    var careers by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(j.companyName) { careers = c.db.companies().byName(j.companyName)?.careersUrl }
    Row {
        careers?.let { u -> TextButton(onClick = { openExternal(ctx, u) }) { Text("${j.companyName} careers") } }
        TextButton(onClick = { openExternal(ctx, j.applyUrl) }) { Text("Original posting") }
    }
}

@Composable
fun InfoRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Text(v, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
fun MatchBreakdown(r: MatchResult, isBest: Boolean) {
    val dark = isDark()
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(r.score, 64.dp, 6.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(when { r.score >= 75 -> "Strong match"; r.score >= 50 -> "Partial match"; else -> "Weak match" }, style = MaterialTheme.typography.titleMedium)
                Text(if (isBest) "Recommended resume for this role" else "Weighted score · see breakdown",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        r.components.forEach { comp ->
            Row(Modifier.fillMaxWidth()) {
                Text(comp.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text(if (comp.score == null) "n/a" else "${Math.round(comp.score * 100)}%  ×  ${Math.round(comp.weight * 100)}%",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (comp.score != null) Bar(comp.score.toFloat(), scoreColor((comp.score * 100).toInt(), dark), Modifier.padding(vertical = 4.dp))
            Text(comp.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
        Text("Components marked n/a aren't stated in the JD, so their weight is redistributed rather than counted for or against you.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (r.matchedRequired.isNotEmpty()) { SubLabel("Required skills you have"); SkillChips(r.matchedRequired, MaterialTheme.colorScheme.onPrimaryContainer, MaterialTheme.colorScheme.primaryContainer) }
        if (r.missingRequired.isNotEmpty()) { SubLabel("Required skills not found"); SkillChips(r.missingRequired, MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.error.copy(alpha = 0.12f)) }
        if (r.matchedPreferred.isNotEmpty()) { SubLabel("Preferred skills you have"); SkillChips(r.matchedPreferred, MaterialTheme.colorScheme.onPrimaryContainer, MaterialTheme.colorScheme.primaryContainer) }
        if (r.missingPreferred.isNotEmpty()) { SubLabel("Preferred skills not found"); SkillChips(r.missingPreferred, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)) }
        if (r.suggestions.isNotEmpty()) {
            SubLabel("How to improve")
            r.suggestions.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
        }
    }
}

@Composable
fun SubLabel(t: String) {
    Text(t, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
}
