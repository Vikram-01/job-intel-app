package com.cipherpath.ui.screens

import android.app.Activity
import android.os.Process
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cipherpath.AppContainer
import com.cipherpath.BuildConfig
import com.cipherpath.ui.Nav
import com.cipherpath.ui.Panel
import com.cipherpath.ui.SectionTitle
import com.cipherpath.ui.relTime
import com.cipherpath.work.WorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(c: AppContainer, nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val s by c.settings.state.collectAsState()
    val runs by c.db.syncRuns().recent().collectAsStateWithLifecycle(emptyList())
    var wipe by remember { mutableStateOf(false) }
    var backendUrl by remember { mutableStateOf(s.backendUrl) }
    var apiKey by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(!c.settings.apiKey.isNullOrBlank()) }

    fun update(reschedule: Boolean = false, f: (com.cipherpath.data.AppSettings) -> com.cipherpath.data.AppSettings) {
        c.settings.update(f)
        if (reschedule) WorkScheduler.schedule(ctx, c.settings.state.value, replace = true)
    }

    ScreenScaffold("Settings & data", onBack = nav::back, snackbar = snack) { pad ->
        ScrollBody(pad) {
            SectionTitle("Appearance")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEachIndexed { idx, (k, l) ->
                    SegmentedButton(s.theme == k, { update { it.copy(theme = k) } }, SegmentedButtonDefaults.itemShape(idx, 3)) { Text(l) }
                }
            }

            SectionTitle("Job sync")
            Panel {
                Text("Sync every", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(6, 12, 24, 48).forEach { h -> FilterChip(s.syncIntervalHours == h, { update(true) { it.copy(syncIntervalHours = h) } }, { Text("${h}h") }) }
                }
                ToggleRow("Only on Wi-Fi", s.syncWifiOnly) { v -> update(true) { it.copy(syncWifiOnly = v) } }
                Text("Role families", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip("cyber" in s.roleGroups, { update { it.copy(roleGroups = it.roleGroups.toggle("cyber").ifEmpty { setOf("fde") }) } }, { Text("Cybersecurity") })
                    FilterChip("fde" in s.roleGroups, { update { it.copy(roleGroups = it.roleGroups.toggle("fde").ifEmpty { setOf("cyber") }) } }, { Text("FDE / Solutions") })
                }
                ToggleRow("Prefer remote in digest", s.preferRemote) { v -> update { it.copy(preferRemote = v) } }
                ToggleRow("Prefer India in digest", s.preferIndia) { v -> update { it.copy(preferIndia = v) } }
                Button(onClick = { WorkScheduler.syncNow(ctx); scope.launch { snack.showSnackbar("Sync started") } }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Sync now") }
                runs.firstOrNull()?.let { r ->
                    Text("Last: ${relTime(r.finishedAt)} via ${r.mode} · ${r.newJobs} new, ${r.updatedJobs} updated, ${r.expiredJobs} expired",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    if (r.errors.isNotBlank()) Text("Issues:\n" + r.errors.lines().take(6).joinToString("\n"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                }
                TextButton(onClick = { nav.to("sources") }) { Text("Manage job sources") }
            }

            SectionTitle("Data source")
            Panel {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(s.syncMode == "direct", { update { it.copy(syncMode = "direct") } }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("On-device") }
                    SegmentedButton(s.syncMode == "backend", { update { it.copy(syncMode = "backend") } }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("My backend") }
                }
                Text(if (s.syncMode == "direct") "The app fetches public job boards directly. No server needed."
                    else "Jobs come from your self-hosted CipherPath API (see DEPLOY.md). Resumes are never sent to it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
                if (s.syncMode == "backend") {
                    OutlinedTextField(backendUrl, { backendUrl = it.trim().take(300) }, Modifier.fillMaxWidth(), label = { Text("Backend URL (https://…)") }, singleLine = true,
                        isError = backendUrl.isNotBlank() && !backendUrl.startsWith("https://"))
                    OutlinedTextField(apiKey, { apiKey = it.trim().take(256) }, Modifier.fillMaxWidth(), label = { Text(if (hasKey) "API key (saved — enter to replace)" else "API key") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(enabled = backendUrl.startsWith("https://"), onClick = {
                            update { it.copy(backendUrl = backendUrl, lastBackendSync = null) }
                            if (apiKey.isNotBlank()) { c.settings.apiKey = apiKey; apiKey = ""; hasKey = true }
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { c.backend.health() }
                                snack.showSnackbar(if (ok) "Saved · backend reachable" else "Saved · backend not reachable")
                            }
                        }) { Text("Save & test") }
                        if (hasKey) OutlinedButton(onClick = { c.settings.apiKey = null; hasKey = false }) { Text("Remove key") }
                    }
                }
            }

            SectionTitle("Notifications")
            Panel {
                ToggleRow("New jobs after sync", s.notifyNewJobs) { v -> update { it.copy(notifyNewJobs = v) } }
                ToggleRow("High-match alerts", s.notifyHighMatch) { v -> update { it.copy(notifyHighMatch = v) } }
                ToggleRow("Daily digest (8 am)", s.notifyDigest) { v -> update { it.copy(notifyDigest = v) } }
                ToggleRow("Interview, follow-up & deadline reminders", s.notifyReminders) { v -> update { it.copy(notifyReminders = v) } }
                var t by remember { mutableFloatStateOf(s.highMatchThreshold.toFloat()) }
                Text("High-match threshold: ${t.toInt()}%", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Slider(t, { t = it }, valueRange = 40f..95f, steps = 10, onValueChangeFinished = { update { it.copy(highMatchThreshold = t.toInt()) } })
            }

            SectionTitle("Privacy & data")
            Panel {
                Text("• Everything is stored on this device in an encrypted database (SQLCipher) whose key is protected by the Android Keystore.\n" +
                    "• Resume files are individually encrypted (AES-256-GCM). They're never uploaded, and screenshots are blocked.\n" +
                    "• No analytics, ads or third-party trackers. Network access is limited to public job boards and, if you set one, your own backend.\n" +
                    "• Apply buttons only open the employer's page; CipherPath never submits applications or shares your data.\n" +
                    "• Backups are disabled so personal data isn't copied to the cloud.",
                    style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                TextButton(onClick = { nav.tab("tracker") }) { Text("Export applications (CSV / PDF) from the tracker") }
                TextButton(onClick = { scope.launch { c.db.notifications().clear(); snack.showSnackbar("Notifications cleared") } }) { Text("Clear notification history") }
                TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { c.matches.recomputeAll(force = true) }; snack.showSnackbar("Match scores recalculated") } }) { Text("Recalculate all match scores") }
                OutlinedButton(onClick = { wipe = true }, modifier = Modifier.fillMaxWidth()) { Text("Erase all data", color = MaterialTheme.colorScheme.error) }
            }
            Text("CipherPath ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
        }
    }

    if (wipe) AlertDialog(onDismissRequest = { wipe = false }, title = { Text("Erase everything?") },
        text = { Text("Deletes all jobs, resumes, applications, notes, settings and encryption keys from this device. This cannot be undone. The app will close.") },
        confirmButton = {
            TextButton(onClick = {
                c.wipeEverything()
                (ctx as? Activity)?.finishAffinity()
                Process.killProcess(Process.myPid())
            }) { Text("Erase", color = MaterialTheme.colorScheme.error) }
        }, dismissButton = { TextButton(onClick = { wipe = false }) { Text("Cancel") } })
}

@Composable
fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(value, onChange)
    }
}

@Composable
fun SourcesScreen(c: AppContainer, nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val companies by c.db.companies().observeAll().collectAsStateWithLifecycle(emptyList())
    var adding by remember { mutableStateOf(false) }
    ScreenScaffold("Job sources", onBack = nav::back, snackbar = snack, actions = { TextButton(onClick = { adding = true }) { Text("Add board") } }) { pad ->
        ScrollBody(pad) {
            Text("Imported directly (public ATS job-board APIs)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Text("Greenhouse, Lever, Ashby and SmartRecruiters publish permitted, unauthenticated job-board APIs. Toggle any company off to stop syncing it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            companies.forEach { co ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(co.name, style = MaterialTheme.typography.bodyMedium)
                        Text("${com.cipherpath.ui.prettySource(co.ats)} · ${co.slug}" + (co.lastStatus?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (co.lastStatus?.startsWith("OK") == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (co.userAdded) TextButton(onClick = { scope.launch { c.db.companies().deleteUserAdded(co.id) } }) { Text("Remove") }
                    Switch(co.enabled, { v -> scope.launch { c.db.companies().setEnabled(co.id, v) } })
                }
            }
            SectionTitle("Search-link only")
            Panel {
                Text("These sites have no permitted public job-search API. CipherPath does not scrape them or bypass logins/CAPTCHAs; Discover opens their own search instead.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                com.cipherpath.data.net.DeepLinks.SOURCES.forEach { (_, v) ->
                    Text(v.first, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    Text(v.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { com.cipherpath.ui.openExternal(ctx, "https://github.com/Vikram-01/job-intel-app/blob/main/docs/SOURCES.md") }) { Text("Why? Read the source policy") }
            }
        }
    }

    if (adding) {
        var name by remember { mutableStateOf("") }
        var ats by remember { mutableStateOf("greenhouse") }
        var slug by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var open by remember { mutableStateOf(false) }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { adding = false }, title = { Text("Add a company board") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Find the board ID in the company's careers URL, e.g. boards.greenhouse.io/<id>, jobs.lever.co/<id>, jobs.ashbyhq.com/<id>.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it.take(200) }, label = { Text("Company name") }, singleLine = true)
                Box {
                    OutlinedButton(onClick = { open = true }) { Text("ATS: ${com.cipherpath.ui.prettySource(ats)}") }
                    DropdownMenu(open, { open = false }) {
                        com.cipherpath.data.Settings.SUPPORTED_ATS.forEach { a -> DropdownMenuItem(text = { Text(com.cipherpath.ui.prettySource(a)) }, onClick = { ats = a; open = false }) }
                    }
                }
                OutlinedTextField(slug, { slug = it.trim().take(100) }, label = { Text("Board ID") }, singleLine = true)
                OutlinedTextField(url, { url = it.trim().take(500) }, label = { Text("Careers page (optional, https://)") }, singleLine = true)
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }, confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    c.jobs.addCompany(name, ats, slug, url.ifBlank { null }).fold(
                        { adding = false; snack.showSnackbar("Added — it will be included in the next sync") },
                        { err = it.message })
                }
            }) { Text("Add") }
        }, dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } })
    }
}
