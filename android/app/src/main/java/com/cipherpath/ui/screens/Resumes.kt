package com.cipherpath.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cipherpath.AppContainer
import com.cipherpath.analysis.AtsChecker
import com.cipherpath.data.db.JobEntity
import com.cipherpath.data.db.ResumeEntity
import com.cipherpath.ui.EmptyState
import com.cipherpath.ui.Nav
import com.cipherpath.ui.Panel
import com.cipherpath.ui.ScoreRing
import com.cipherpath.ui.SkillChips
import com.cipherpath.ui.fmtDate
import kotlinx.coroutines.launch

private val MIME = arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")

@Composable
fun ResumesScreen(c: AppContainer, nav: Nav) {
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val resumes by c.resumes.observeAll().collectAsStateWithLifecycle(null)
    var busy by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<ResumeEntity?>(null) }
    var deleting by remember { mutableStateOf<ResumeEntity?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val r = c.resumes.import(uri)
            busy = false
            snack.showSnackbar(r.fold({ "Imported ${it.name} · ${it.skillsCsv.split(",").count { s -> s.isNotBlank() }} skills found" }, { it.message ?: "Import failed" }))
        }
    }

    ScreenScaffold("Resumes", snackbar = snack, floating = {
        ExtendedFloatingActionButton(onClick = { if (!busy) picker.launch(MIME) }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Add PDF / DOCX") })
    }) { pad ->
        ScrollBody(pad) {
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Parsing and scoring…", style = MaterialTheme.typography.bodySmall) }
            Text("Files are encrypted on this device and never uploaded. Matching runs locally.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
            val list = resumes
            when {
                list == null -> com.cipherpath.ui.Loading()
                list.isEmpty() -> EmptyState(Icons.Outlined.Description, "No resumes yet",
                    "Add one or more resumes (PDF or DOCX). Each job is scored against all of them and the best fit is recommended.",
                    "Add resume") { picker.launch(MIME) }
                else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    list.forEach { r ->
                        ResumeCard(c, nav, r, expanded == r.id, { expanded = if (expanded == r.id) null else r.id },
                            onDefault = { scope.launch { c.resumes.setDefault(r.id) } },
                            onRename = { renaming = r }, onDelete = { deleting = r })
                    }
                }
            }
            Spacer(Modifier.height(80.dp))
        }
    }

    renaming?.let { r ->
        var name by remember(r.id) { mutableStateOf(r.name) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename resume") },
            text = { OutlinedTextField(name, { name = it.take(80) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { scope.launch { c.resumes.rename(r, name) }; renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } })
    }
    deleting?.let { r ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${r.name}?") },
            text = { Text("The encrypted file and its match scores will be removed from this device.") },
            confirmButton = { TextButton(onClick = { scope.launch { c.resumes.delete(r.id) }; deleting = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}

@Composable
private fun ResumeCard(c: AppContainer, nav: Nav, r: ResumeEntity, open: Boolean, toggle: () -> Unit, onDefault: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.titleMedium)
                Text("${r.fileType.uppercase()} · ${r.sizeBytes / 1024} KB · added ${fmtDate(r.importedAt)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("~${r.yearsExperience} yrs experience detected · ${r.skillsCsv.split(",").count { it.isNotBlank() }} skills",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDefault) {
                Icon(if (r.isDefault) Icons.Outlined.Star else Icons.Outlined.StarBorder, if (r.isDefault) "Default resume" else "Make default",
                    tint = if (r.isDefault) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRename) { Icon(Icons.Outlined.Edit, "Rename") }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Delete") }
        }
        TextButton(onClick = toggle) { Text(if (open) "Hide details" else "Skills, health check & top jobs") }
        if (open) {
            val doc = remember(r.analysisJson) { c.resumes.doc(r) }
            val health = remember(r.analysisJson) { AtsChecker.check(doc, "", "", null, c.taxonomy) }
            var top by remember { mutableStateOf<List<Pair<JobEntity, Int>>>(emptyList()) }
            LaunchedEffect(r.id) {
                val ms = c.db.matches().forResume(r.id).sortedByDescending { it.score }.take(5)
                val jobs = c.db.jobs().getMany(ms.map { it.jobId }).associateBy { it.id }
                top = ms.mapNotNull { m -> jobs[m.jobId]?.takeIf { it.isActive }?.let { it to m.score } }
            }
            SubLabel("Extracted skills")
            if (doc.skills.isEmpty()) Text("No known skills recognised.", style = MaterialTheme.typography.bodySmall)
            else SkillChips(doc.skills, MaterialTheme.colorScheme.onSecondaryContainer, MaterialTheme.colorScheme.secondaryContainer)
            if (doc.titles.isNotEmpty()) { SubLabel("Relevant titles found"); doc.titles.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) } }
            SubLabel("Sections detected")
            Text(doc.sections.filter { it.key != "header" && it.value.isNotBlank() }.keys.joinToString(", ").ifBlank { "None" }, style = MaterialTheme.typography.bodySmall)
            SubLabel("Resume health (job-independent)")
            health.findings.filter { it.check !in setOf("keywords", "title", "experience") && it.severity != "pass" }.ifEmpty { null }?.forEach { f ->
                Text("• ${f.message}" + if (f.fix.isNotBlank()) " ${f.fix}" else "", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
            } ?: Text("No structural issues found.", style = MaterialTheme.typography.bodySmall)
            SubLabel("Best-matching jobs for this resume")
            if (top.isEmpty()) Text("No scored jobs yet — run a sync.", style = MaterialTheme.typography.bodySmall)
            top.forEach { (j, s) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ScoreRing(s, 34.dp, 4.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(j.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Text(j.companyName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { nav.job(j.id) }) { Text("View") }
                }
            }
        }
    }
}
