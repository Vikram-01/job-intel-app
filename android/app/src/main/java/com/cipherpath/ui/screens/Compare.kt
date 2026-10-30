package com.cipherpath.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cipherpath.AppContainer
import com.cipherpath.data.db.JobEntity
import com.cipherpath.data.repo.Ingest
import com.cipherpath.ui.EmptyState
import com.cipherpath.ui.Nav
import com.cipherpath.ui.Panel
import com.cipherpath.ui.ScoreRing
import com.cipherpath.ui.fmtDate
import com.cipherpath.ui.fmtSalary
import com.cipherpath.ui.prettyMode
import com.cipherpath.ui.prettySeniority

@Composable
fun CompareScreen(c: AppContainer, nav: Nav) {
    var jobs by remember { mutableStateOf<List<JobEntity>>(emptyList()) }
    val ids = nav.compare.toList()
    LaunchedEffect(ids) { jobs = c.db.jobs().getMany(ids).sortedBy { ids.indexOf(it.id) } }
    ScreenScaffold("Compare jobs", onBack = nav::back, actions = {
        if (ids.isNotEmpty()) TextButton(onClick = { nav.compare.clear() }) { Text("Clear") }
    }) { pad ->
        if (jobs.isEmpty()) {
            Column(Modifier.padding(pad)) {
                EmptyState(Icons.Outlined.Compare, "Nothing to compare", "Open a job and tap the compare icon to add up to three jobs.")
            }
            return@ScreenScaffold
        }
        val rows: List<Pair<String, (JobEntity) -> String>> = listOf(
            "Company" to { j: JobEntity -> j.companyName },
            "Location" to { j: JobEntity -> j.location.ifBlank { "—" } },
            "Mode" to { j: JobEntity -> prettyMode(j.workMode).ifBlank { "—" } },
            "Level" to { j: JobEntity -> prettySeniority(j.seniority).ifBlank { "—" } },
            "Experience" to { j: JobEntity -> j.minYears?.let { y -> "$y+ yrs" } ?: "—" },
            "Salary" to { j: JobEntity -> fmtSalary(j.salaryMin, j.salaryMax, j.salaryCurrency) ?: "Not posted" },
            "Posted" to { j: JobEntity -> fmtDate(j.postedAt ?: j.firstSeenAt) },
            "Required" to { j: JobEntity -> Ingest.csv(j.reqSkillsCsv).joinToString(", ").ifBlank { "—" } },
            "Preferred" to { j: JobEntity -> Ingest.csv(j.prefSkillsCsv).joinToString(", ").ifBlank { "—" } },
        )
        ScrollBody(pad) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                jobs.forEach { j ->
                    Panel(Modifier.width(240.dp)) {
                        ScoreRing(j.bestMatchScore, 52.dp)
                        Spacer(Modifier.height(8.dp))
                        Text(j.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        rows.forEach { (label, f) ->
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                            Text(f(j), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { nav.job(j.id) }) { Text("Open") }
                    }
                }
            }
        }
    }
}
