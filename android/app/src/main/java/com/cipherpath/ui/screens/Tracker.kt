package com.cipherpath.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cipherpath.AppContainer
import com.cipherpath.data.Exporter
import com.cipherpath.data.db.ApplicationEntity
import com.cipherpath.data.db.Stage
import com.cipherpath.ui.Nav
import com.cipherpath.ui.Pill
import com.cipherpath.ui.relTime
import com.cipherpath.ui.theme.Amber
import com.cipherpath.ui.theme.Rose
import com.cipherpath.ui.theme.Sky
import com.cipherpath.ui.theme.Teal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

fun stageColor(stage: String): Color = when (stage) {
    Stage.SAVED -> Color(0xFF7C8AA3)
    Stage.APPLIED -> Sky
    Stage.ASSESSMENT, Stage.INTERVIEW, Stage.TECHNICAL, Stage.HR -> Amber
    Stage.OFFER -> Teal
    else -> Rose
}

@Composable
fun TrackerScreen(c: AppContainer, nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val apps by c.applications.observeAll().collectAsStateWithLifecycle(emptyList())
    var adding by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }
    val today = LocalDate.now().toString()

    fun export(uri: android.net.Uri?, pdf: Boolean) {
        if (uri == null) return
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val all = c.applications.allDetails()
                    ctx.contentResolver.openOutputStream(uri, "wt")?.use { if (pdf) Exporter.writePdf(all, it) else Exporter.writeCsv(all, it) }
                        ?: error("Could not open file")
                }.isSuccess
            }
            snack.showSnackbar(if (ok) "Exported ${apps.size} applications" else "Export failed")
        }
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { export(it, false) }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { export(it, true) }

    ScreenScaffold("Application tracker", snackbar = snack, actions = {
        Box {
            IconButton(onClick = { exportMenu = true }, enabled = apps.isNotEmpty()) { Icon(Icons.Outlined.FileDownload, "Export") }
            DropdownMenu(exportMenu, { exportMenu = false }) {
                DropdownMenuItem(text = { Text("Export CSV") }, onClick = { exportMenu = false; csvLauncher.launch("cipherpath-applications-$today.csv") })
                DropdownMenuItem(text = { Text("Export PDF") }, onClick = { exportMenu = false; pdfLauncher.launch("cipherpath-applications-$today.pdf") })
            }
        }
    }, floating = {
        ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Add") })
    }) { pad ->
        val grouped = apps.groupBy { it.stage }
        LazyRow(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(Stage.ALL, key = { it }) { stage ->
                val list = grouped[stage].orEmpty()
                Column(
                    Modifier.width(272.dp).fillMaxHeight().clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(stageColor(stage)))
                        Spacer(Modifier.width(8.dp))
                        Text(stage, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Pill("${list.size}")
                    }
                    if (list.isEmpty()) Text("No applications", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    LazyColumn(contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 90.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(list, key = { it.id }) { a ->
                            KanbanCard(a, onOpen = { nav.application(a.id) }, onMove = { s -> scope.launch { c.applications.moveTo(a.id, s) } })
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        var company by remember { mutableStateOf("") }
        var title by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { adding = false }, title = { Text("Track an application") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("For roles found outside CipherPath (e.g. LinkedIn or Naukri).", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(company, { company = it.take(200) }, label = { Text("Company") }, singleLine = true)
                OutlinedTextField(title, { title = it.take(300) }, label = { Text("Role title") }, singleLine = true)
                OutlinedTextField(url, { url = it.take(1000) }, label = { Text("Job URL (https://, optional)") }, singleLine = true,
                    isError = url.isNotBlank() && !url.trim().startsWith("https://"))
            }
        }, confirmButton = {
            TextButton(enabled = company.isNotBlank() && title.isNotBlank() && (url.isBlank() || url.trim().startsWith("https://")), onClick = {
                scope.launch { val id = c.applications.createManual(company, title, url.ifBlank { null }); adding = false; nav.application(id) }
            }) { Text("Add") }
        }, dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } })
    }
}

@Composable
private fun KanbanCard(a: ApplicationEntity, onOpen: () -> Unit, onMove: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(onClick = onOpen, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))) {
        Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(a.company, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(a.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    listOfNotNull(
                        a.appliedAt?.let { "Applied ${relTime(it)}" },
                        a.deadlineAt?.takeIf { a.stage == Stage.SAVED }?.let { "Due ${com.cipherpath.ui.fmtDate(it)}" },
                        a.followUpAt?.let { "Follow up ${com.cipherpath.ui.fmtDate(it)}" },
                    ).joinToString(" · ").ifBlank { "Updated ${relTime(a.updatedAt)}" },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Move") }
                DropdownMenu(menu, { menu = false }) {
                    Text("Move to", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    Stage.ALL.filter { it != a.stage }.forEach { s ->
                        DropdownMenuItem(text = { Text(s) }, onClick = { menu = false; onMove(s) })
                    }
                }
            }
        }
    }
}
