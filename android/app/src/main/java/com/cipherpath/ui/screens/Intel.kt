package com.cipherpath.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cipherpath.AppContainer
import com.cipherpath.data.db.CompanyEntity
import com.cipherpath.data.db.JobEntity
import com.cipherpath.data.repo.Ingest
import com.cipherpath.ui.Bar
import com.cipherpath.ui.EmptyState
import com.cipherpath.ui.Loading
import com.cipherpath.ui.Nav
import com.cipherpath.ui.Panel
import com.cipherpath.ui.SectionTitle
import com.cipherpath.ui.fmtSalary
import com.cipherpath.ui.openExternal
import com.cipherpath.ui.prettySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class Gap(val skill: String, val count: Int, val learning: String?)
private data class SalaryRow(val role: String, val currency: String, val n: Int, val medMin: Double, val medMax: Double)
private data class CompanyRow(val company: CompanyEntity?, val name: String, val open: Int)
private data class Intel(
    val basis: Int,
    val gaps: List<Gap>,
    val certs: List<Pair<String, List<String>>>,
    val salaries: List<SalaryRow>,
    val roles: List<Pair<String, Int>>,
    val remoteShare: Int,
    val indiaShare: Int,
    val companies: List<CompanyRow>,
    val total: Int,
)

private fun median(xs: List<Double>): Double = xs.sorted().let { s -> if (s.isEmpty()) 0.0 else if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

@Composable
fun IntelScreen(c: AppContainer, nav: Nav) {
    val ctx = LocalContext.current
    val resumes by c.resumes.observeAll().collectAsStateWithLifecycle(emptyList())
    val companies by c.db.companies().observeAll().collectAsStateWithLifecycle(emptyList())
    val threshold = c.settings.state.value.highMatchThreshold
    var intel by remember { mutableStateOf<Intel?>(null) }
    LaunchedEffect(resumes, companies) {
        intel = withContext(Dispatchers.Default) {
            val all = c.db.jobs().allActive()
            val have = resumes.flatMap { Ingest.csv(it.skillsCsv) }.toSet()
            val tracked = c.db.applications().all().mapNotNull { it.jobId }.toSet()
            // Basis = jobs you care about: bookmarked, tracked, or high/medium match; falls back to all jobs.
            var basis: List<JobEntity> = all.filter { it.bookmarked || it.id in tracked || (it.bestMatchScore ?: 0) >= (threshold - 20) }
            if (basis.size < 10) basis = all
            val gapCounts = HashMap<String, Int>()
            basis.forEach { j -> Ingest.csv(j.reqSkillsCsv).filter { it !in have }.forEach { gapCounts[it] = (gapCounts[it] ?: 0) + 1 } }
            val gaps = gapCounts.entries.sortedByDescending { it.value }.take(12).map { Gap(it.key, it.value, c.taxonomy.data.learning[it.key]) }
            val roleCounts = basis.groupingBy { it.roleCategory ?: "other" }.eachCount().entries.sortedByDescending { it.value }
            val certs = roleCounts.take(4).mapNotNull { (role, _) ->
                val list = c.taxonomy.data.certifications[role] ?: return@mapNotNull null
                (c.taxonomy.roleById[role]?.label ?: role) to list
            }
            val sal = all.filter { it.salaryMax != null && it.salaryCurrency != null && it.roleCategory != null }
                .groupBy { it.roleCategory!! to it.salaryCurrency!! }
                .filter { it.value.size >= 2 }
                .map { (k, v) -> SalaryRow(c.taxonomy.roleById[k.first]?.label ?: k.first, k.second, v.size,
                    median(v.map { it.salaryMin ?: it.salaryMax!! }), median(v.map { it.salaryMax!! })) }
                .sortedByDescending { it.n }
            val byCompany = all.groupingBy { it.companyName }.eachCount()
            val compRows = (companies.map { CompanyRow(it, it.name, byCompany[it.name] ?: 0) } +
                byCompany.keys.filter { n -> companies.none { it.name == n } }.map { CompanyRow(null, it, byCompany[it] ?: 0) })
                .sortedByDescending { it.open }
            Intel(
                basis = basis.size, gaps = gaps, certs = certs, salaries = sal,
                roles = all.groupingBy { it.roleCategory ?: "other" }.eachCount().entries.sortedByDescending { it.value }
                    .map { (c.taxonomy.roleById[it.key]?.label ?: "Other") to it.value },
                remoteShare = if (all.isEmpty()) 0 else all.count { it.workMode == "remote" } * 100 / all.size,
                indiaShare = if (all.isEmpty()) 0 else all.count { it.country == "IN" } * 100 / all.size,
                companies = compRows, total = all.size,
            )
        }
    }

    ScreenScaffold("Career intelligence", onBack = nav::back) { pad ->
        val i = intel
        if (i == null) { Loading(Modifier.padding(pad)); return@ScreenScaffold }
        if (i.total == 0) {
            Column(Modifier.padding(pad)) { EmptyState(Icons.Outlined.Insights, "No data yet", "Sync jobs first; insights are computed from the roles you've collected.") }
            return@ScreenScaffold
        }
        ScrollBody(pad) {
            SectionTitle("Skill gaps")
            Panel {
                Text(if (resumes.isEmpty()) "Add a resume to see which in-demand skills you're missing."
                    else "Required skills missing from all your resumes, across ${i.basis} relevant jobs.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (resumes.isNotEmpty() && i.gaps.isEmpty()) Text("No common gaps — nice.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                if (resumes.isNotEmpty()) i.gaps.forEach { g ->
                    Column(Modifier.padding(top = 10.dp)) {
                        Row { Text(g.skill, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)); Text("${g.count} jobs", style = MaterialTheme.typography.labelSmall) }
                        Bar(g.count.toFloat() / i.basis, MaterialTheme.colorScheme.tertiary, Modifier.padding(vertical = 4.dp))
                        g.learning?.let { Text("Learn: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }

            SectionTitle("Recommended certifications")
            Panel {
                Text("Based on the role types most common in your relevant jobs. Check each employer's JD — certifications are rarely mandatory.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                i.certs.forEach { (role, list) ->
                    Text(role, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
                    Text(list.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
            }

            SectionTitle("Salary insights")
            Panel {
                if (i.salaries.isEmpty()) Text("Not enough postings publish pay ranges yet. We only show salaries stated in the posting — never estimates.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    Text("Medians of ranges published in postings (annual, posting currency). Small samples — treat as indicative.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    i.salaries.take(10).forEach { s ->
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(s.role, style = MaterialTheme.typography.titleSmall)
                                Text("${s.n} postings · ${s.currency}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(fmtSalary(s.medMin, s.medMax, s.currency) ?: "", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            SectionTitle("Market snapshot")
            Panel {
                Text("${i.total} open roles · ${i.remoteShare}% remote · ${i.indiaShare}% in India", style = MaterialTheme.typography.bodyMedium)
                i.roles.take(8).forEach { (label, n) ->
                    Row(Modifier.padding(top = 8.dp)) { Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)); Text("$n", style = MaterialTheme.typography.labelSmall) }
                    Bar(n.toFloat() / i.total, MaterialTheme.colorScheme.secondary, Modifier.padding(top = 3.dp))
                }
            }

            SectionTitle("Companies")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                i.companies.forEach { row ->
                    Panel {
                        Row {
                            Column(Modifier.weight(1f)) {
                                Text(row.name, style = MaterialTheme.typography.titleSmall)
                                Text("${row.open} open matching roles" + (row.company?.let { " · ${prettySource(it.ats)}" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(8.dp))
                            if (row.open > 0) TextButton(onClick = { nav.tab("discover") }) { Text("Jobs") }
                            row.company?.careersUrl?.let { u -> TextButton(onClick = { openExternal(ctx, u) }) { Text("Careers") } }
                        }
                    }
                }
            }
        }
    }
}
