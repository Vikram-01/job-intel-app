package com.cipherpath.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.cipherpath.AppContainer
import com.cipherpath.R
import com.cipherpath.data.db.Stage
import com.cipherpath.ui.EmptyState
import com.cipherpath.ui.ErrorBanner
import com.cipherpath.ui.JobCard
import com.cipherpath.ui.Nav
import com.cipherpath.ui.Panel
import com.cipherpath.ui.SectionTitle
import com.cipherpath.ui.StatTile
import com.cipherpath.ui.fmtDateTime
import com.cipherpath.ui.relTime
import com.cipherpath.work.WorkScheduler
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun DashboardScreen(c: AppContainer, nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by c.settings.state.collectAsState()
    val startOfDay = remember { LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
    val today by c.db.jobs().newSince(startOfDay, 6).collectAsStateWithLifecycle(emptyList())
    val todayCount by c.db.jobs().countNewSince(startOfDay).collectAsStateWithLifecycle(0)
    val total by c.db.jobs().countActive().collectAsStateWithLifecycle(0)
    val high by c.db.jobs().highMatch(s.highMatchThreshold, 5).collectAsStateWithLifecycle(emptyList())
    val stages by c.applications.stageCounts().collectAsStateWithLifecycle(emptyList())
    val interviews by c.applications.upcomingInterviews().collectAsStateWithLifecycle(emptyList())
    val lastRun by c.db.syncRuns().last().collectAsStateWithLifecycle(null)
    val resumes by c.resumes.observeAll().collectAsStateWithLifecycle(emptyList())
    val work by remember { WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(WorkScheduler.MANUAL) }.collectAsStateWithLifecycle(emptyList())
    val syncing = work.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    val byStage = stages.associate { it.stage to it.n }
    val active = byStage.filterKeys { it !in setOf(Stage.SAVED, Stage.REJECTED, Stage.OFFER) }.values.sum()

    ScreenScaffold(
        title = "",
        actions = {
            IconButton(onClick = { WorkScheduler.syncNow(ctx) }, enabled = !syncing) { Icon(Icons.Outlined.Sync, "Sync now") }
        },
    ) { pad ->
        ScrollBody(pad) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(44.dp))
                Spacer(Modifier.width(6.dp))
                Column {
                    Text("Good ${greeting()}", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        lastRun?.let { "Last sync ${relTime(it.finishedAt)} · ${it.newJobs} new" } ?: "Not synced yet",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (syncing) { Spacer(Modifier.height(10.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            val err = lastRun?.errors?.takeIf { it.isNotBlank() }
            if (err != null && (lastRun?.newJobs ?: 0) == 0 && total == 0) {
                Spacer(Modifier.height(10.dp))
                ErrorBanner("Last sync could not reach some sources. Check your connection.", onRetry = { WorkScheduler.syncNow(ctx) })
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("New today", "$todayCount", Modifier.weight(1f), onClick = { nav.tab("discover") })
                StatTile("Open roles", "$total", Modifier.weight(1f), accent = MaterialTheme.colorScheme.secondary, onClick = { nav.tab("discover") })
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Applied & active", "$active", Modifier.weight(1f), accent = MaterialTheme.colorScheme.tertiary, onClick = { nav.tab("tracker") })
                StatTile("Offers", "${byStage[Stage.OFFER] ?: 0}", Modifier.weight(1f), onClick = { nav.tab("tracker") })
            }

            if (resumes.isEmpty()) {
                Spacer(Modifier.height(16.dp))
                Panel {
                    Text("Add a resume to unlock match scores", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text("CipherPath scores every job against your resumes on-device and explains each score.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.material3.TextButton(onClick = { nav.tab("resumes") }) { Text("Add resume") }
                }
            }

            if (interviews.isNotEmpty()) {
                SectionTitle("Upcoming interviews")
                Panel {
                    interviews.take(3).forEach { iv ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(fmtDateTime(iv.at), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(110.dp))
                            Text(iv.round, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            androidx.compose.material3.TextButton(onClick = { nav.application(iv.applicationId) }) { Text("Open") }
                        }
                    }
                }
            }

            if (high.isNotEmpty()) {
                SectionTitle("High-match jobs", "See all") { nav.tab("discover") }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    high.forEach { j -> JobCard(j, { nav.job(j.id) }, { scope.launch { c.jobs.setBookmarked(j.id, !j.bookmarked) } }) }
                }
            }

            SectionTitle("New today", "Discover") { nav.tab("discover") }
            if (today.isEmpty()) {
                EmptyState(
                    if (total == 0) Icons.Outlined.WorkOutline else Icons.Outlined.Description,
                    if (total == 0) "No jobs yet" else "No new roles today",
                    if (total == 0) "Run a sync to pull roles from public company job boards." else "New postings appear here automatically after each sync.",
                    if (total == 0) "Sync now" else null,
                ) { WorkScheduler.syncNow(ctx) }
            } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                today.forEach { j -> JobCard(j, { nav.job(j.id) }, { scope.launch { c.jobs.setBookmarked(j.id, !j.bookmarked) } }) }
            }
        }
    }
}

private fun greeting(): String {
    val h = java.time.LocalTime.now().hour
    return when { h < 12 -> "morning"; h < 17 -> "afternoon"; else -> "evening" }
}
