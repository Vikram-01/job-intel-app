package com.cipherpath.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cipherpath.AppContainer
import com.cipherpath.analysis.AtsChecker
import com.cipherpath.analysis.AtsResult
import com.cipherpath.data.db.JobEntity
import com.cipherpath.data.db.JobFilter
import com.cipherpath.ui.Bar
import com.cipherpath.ui.EmptyState
import com.cipherpath.ui.Nav
import com.cipherpath.ui.Panel
import com.cipherpath.ui.ScoreRing
import com.cipherpath.ui.SectionTitle
import com.cipherpath.ui.SkillChips
import com.cipherpath.ui.isDark
import com.cipherpath.ui.theme.scoreColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AtsScreen(c: AppContainer, nav: Nav, jobId: String?) {
    val resumes by c.resumes.observeAll().collectAsStateWithLifecycle(null)
    val candidates by remember { c.jobs.search(JobFilter(sort = "match"), 60, 0) }.collectAsStateWithLifecycle(emptyList())
    var resumeId by rememberSaveable { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<JobEntity?>(null) }
    var mode by rememberSaveable { mutableStateOf(0) }
    var pastedTitle by rememberSaveable { mutableStateOf("") }
    var pastedJd by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<AtsResult?>(null) }
    var running by remember { mutableStateOf(false) }
    var trigger by remember { mutableStateOf(0) }
    LaunchedEffect(jobId) { if (jobId != null) job = c.jobs.get(jobId) }
    val rs = resumes
    if (resumeId == null && !rs.isNullOrEmpty()) resumeId = (rs.firstOrNull { it.isDefault } ?: rs.first()).id

    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        val r = rs?.firstOrNull { it.id == resumeId } ?: return@LaunchedEffect
        running = true
        result = withContext(Dispatchers.Default) {
            val doc = c.resumes.doc(r)
            if (mode == 0) job?.let { AtsChecker.check(doc, it.title, it.description, it.minYears, c.taxonomy) }
            else AtsChecker.check(doc, pastedTitle.trim(), pastedJd.trim(), null, c.taxonomy)
        }
        running = false
    }

    ScreenScaffold("ATS Analyzer", onBack = nav::back) { pad ->
        ScrollBody(pad) {
            if (rs != null && rs.isEmpty()) {
                EmptyState(Icons.Outlined.Description, "Add a resume first", "The ATS check compares one of your resumes against a job description.", "Go to resumes") { nav.tab("resumes") }
                return@ScrollBody
            }
            Panel {
                Text("Resume", style = MaterialTheme.typography.labelLarge)
                var open by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(rs?.firstOrNull { it.id == resumeId }?.name ?: "Choose resume") }
                    DropdownMenu(open, { open = false }) { rs?.forEach { r -> DropdownMenuItem(text = { Text(r.name) }, onClick = { resumeId = r.id; open = false; result = null }) } }
                }
                Spacer(Modifier.height(12.dp))
                Text("Job description", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(mode == 0, { mode = 0; result = null }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Synced job") }
                    SegmentedButton(mode == 1, { mode = 1; result = null }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Paste JD") }
                }
                Spacer(Modifier.height(8.dp))
                if (mode == 0) {
                    var jopen by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { jopen = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(job?.let { "${it.title} · ${it.companyName}" } ?: "Choose a job", maxLines = 1)
                        }
                        DropdownMenu(jopen, { jopen = false }, Modifier.heightIn(max = 420.dp)) {
                            candidates.forEach { j -> DropdownMenuItem(text = { Text("${j.title} · ${j.companyName}", maxLines = 2) }, onClick = { job = j; jopen = false; result = null }) }
                        }
                    }
                } else {
                    OutlinedTextField(pastedTitle, { pastedTitle = it.take(200) }, Modifier.fillMaxWidth(), label = { Text("Job title") }, singleLine = true)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(pastedJd, { pastedJd = it.take(30_000) }, Modifier.fillMaxWidth().heightIn(min = 140.dp), label = { Text("Paste the full job description") })
                }
                Spacer(Modifier.height(12.dp))
                val ready = resumeId != null && if (mode == 0) job != null else (pastedTitle.isNotBlank() && pastedJd.length >= 50)
                Button(onClick = { trigger++ }, enabled = ready && !running, modifier = Modifier.fillMaxWidth()) { Text(if (running) "Analysing…" else "Run ATS check") }
            }
            result?.let { AtsReport(it) }
        }
    }
}

@Composable
private fun AtsReport(r: AtsResult) {
    val dark = isDark()
    SectionTitle("Estimated ATS compatibility")
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(r.score, 72.dp, 7.dp)
            Spacer(Modifier.width(14.dp))
            Text(r.disclaimer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        AtsChecker.WEIGHTS.forEach { (k, w) ->
            val v = r.breakdown[k] ?: 0
            Row(Modifier.fillMaxWidth()) {
                Text(AtsChecker.LABELS[k] ?: k, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text("$v%  ×  ${Math.round(w * 100)}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Bar(v / 100f, scoreColor(v, dark), Modifier.padding(top = 4.dp, bottom = 10.dp))
        }
    }
    if (r.missingKeywords.isNotEmpty() || r.matchedKeywords.isNotEmpty()) {
        SectionTitle("Keywords")
        Panel {
            if (r.matchedKeywords.isNotEmpty()) { SubLabel("Found"); SkillChips(r.matchedKeywords, MaterialTheme.colorScheme.onPrimaryContainer, MaterialTheme.colorScheme.primaryContainer) }
            if (r.missingKeywords.isNotEmpty()) { SubLabel("Missing"); SkillChips(r.missingKeywords, MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.error.copy(alpha = 0.12f)) }
        }
    }
    SectionTitle("Findings & fixes")
    Panel {
        val order = mapOf("fail" to 0, "warn" to 1, "info" to 2, "pass" to 3)
        r.findings.sortedBy { order[it.severity] ?: 9 }.forEach { f ->
            val (icon, tint) = when (f.severity) {
                "fail" -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
                "warn" -> Icons.Outlined.WarningAmber to MaterialTheme.colorScheme.tertiary
                "pass" -> Icons.Outlined.CheckCircle to MaterialTheme.colorScheme.primary
                else -> Icons.Outlined.Info to Color(0xFF7C8AA3)
            }
            Row(Modifier.padding(vertical = 6.dp)) {
                Icon(icon, f.severity, tint = tint, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.message, style = MaterialTheme.typography.bodyMedium)
                    if (f.fix.isNotBlank()) Text(f.fix, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
