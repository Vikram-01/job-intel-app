package com.cipherpath.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.WorkOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cipherpath.AppContainer
import com.cipherpath.data.db.ApplicationEntity
import com.cipherpath.data.db.Stage
import com.cipherpath.ui.EmptyState
import com.cipherpath.ui.Loading
import com.cipherpath.ui.Nav
import com.cipherpath.ui.Panel
import com.cipherpath.ui.SectionTitle
import com.cipherpath.ui.fmtDate
import com.cipherpath.ui.fmtDateTime
import com.cipherpath.ui.openExternal
import kotlinx.coroutines.launch
import java.util.Calendar

fun pickDate(ctx: Context, initial: Long?, onPicked: (Long) -> Unit) {
    val cal = Calendar.getInstance().apply { initial?.let { timeInMillis = it } }
    DatePickerDialog(ctx, { _, y, m, d ->
        cal.set(y, m, d, 9, 0, 0); onPicked(cal.timeInMillis)
    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
}

fun pickDateTime(ctx: Context, onPicked: (Long) -> Unit) {
    val cal = Calendar.getInstance()
    DatePickerDialog(ctx, { _, y, m, d ->
        TimePickerDialog(ctx, { _, h, min -> cal.set(y, m, d, h, min, 0); onPicked(cal.timeInMillis) },
            10, 0, android.text.format.DateFormat.is24HourFormat(ctx)).show()
    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
}

@Composable
fun ApplicationScreen(c: AppContainer, nav: Nav, id: Long) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val details by remember(id) { c.applications.observeDetails(id) }.collectAsStateWithLifecycle(null)
    val resumes by c.resumes.observeAll().collectAsStateWithLifecycle(emptyList())
    var draft by remember { mutableStateOf<ApplicationEntity?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var addInterview by remember { mutableStateOf(false) }
    var showJd by remember { mutableStateOf(false) }
    LaunchedEffect(details) {
        val a = details?.app
        if (a != null) {
            val cur = draft
            draft = if (cur == null || cur.id != a.id || !isDirty(cur, a)) a
            else cur.copy(stage = a.stage, appliedAt = cur.appliedAt ?: a.appliedAt, updatedAt = a.updatedAt)
        }
        loaded = true
    }

    val d = draft
    val saved = details?.app
    val dirty = d != null && saved != null && isDirty(d, saved)
    ScreenScaffold(d?.company ?: "Application", onBack = nav::back, snackbar = snack, actions = {
        if (d != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Delete") }
    }) { pad ->
        if (d == null) {
            if (!loaded) Loading() else EmptyState(Icons.Outlined.WorkOff, "Not found", "This application was deleted.")
            return@ScreenScaffold
        }
        ScrollBody(pad) {
            Text(d.title, style = MaterialTheme.typography.headlineSmall)
            Text(listOf(d.company, d.location).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                d.applyUrl?.let { u -> OutlinedButton(onClick = { openExternal(ctx, u) }) { Text("Open posting") } }
                d.jobId?.let { j -> OutlinedButton(onClick = { nav.job(j) }) { Text("Job details") } }
            }

            SectionTitle("Stage")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Stage.ALL.forEach { s ->
                    FilterChip(d.stage == s, { scope.launch { c.applications.moveTo(d.id, s) } }, { Text(s) })
                }
            }

            SectionTitle("Dates & reminders")
            Panel {
                DateRow("Applied", d.appliedAt, { pickDate(ctx, d.appliedAt) { v -> draft = d.copy(appliedAt = v) } }, { draft = d.copy(appliedAt = null) })
                DateRow("Deadline", d.deadlineAt, { pickDate(ctx, d.deadlineAt) { v -> draft = d.copy(deadlineAt = v) } }, { draft = d.copy(deadlineAt = null) })
                DateRow("Follow-up reminder", d.followUpAt, { pickDate(ctx, d.followUpAt) { v -> draft = d.copy(followUpAt = v) } }, { draft = d.copy(followUpAt = null) })
            }

            SectionTitle("Interviews", "Add") { addInterview = true }
            val ivs = details?.interviews.orEmpty().sortedBy { it.at }
            if (ivs.isEmpty()) Text("No interviews scheduled.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ivs.forEach { iv ->
                Panel(Modifier.padding(bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${iv.round}${if (iv.mode.isNotBlank()) " · ${iv.mode}" else ""}", style = MaterialTheme.typography.titleSmall)
                            Text(fmtDateTime(iv.at), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            if (iv.notes.isNotBlank()) Text(iv.notes, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { scope.launch { c.applications.deleteInterview(iv.id) } }) { Icon(Icons.Outlined.Delete, "Remove interview") }
                    }
                }
            }

            SectionTitle("Recruiter / contact")
            Panel {
                OutlinedTextField(d.recruiterName, { draft = d.copy(recruiterName = it.take(200)) }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                OutlinedTextField(d.recruiterEmail, { draft = d.copy(recruiterEmail = it.take(200)) }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                OutlinedTextField(d.recruiterPhone, { draft = d.copy(recruiterPhone = it.filter { ch -> ch.isDigit() || ch in "+-() " }.take(40) ) }, Modifier.fillMaxWidth(),
                    label = { Text("Phone") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
            }

            SectionTitle("Salary & offer")
            Panel {
                OutlinedTextField(d.expectedSalary, { draft = d.copy(expectedSalary = it.take(100)) }, Modifier.fillMaxWidth(), label = { Text("Expected / discussed salary") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(d.offerAmount?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: "",
                        { v -> draft = d.copy(offerAmount = v.filter { ch -> ch.isDigit() || ch == '.' }.take(12).toDoubleOrNull()) },
                        Modifier.weight(2f), label = { Text("Offer amount") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(d.offerCurrency, { draft = d.copy(offerCurrency = it.uppercase().filter(Char::isLetter).take(3)) }, Modifier.weight(1f), label = { Text("Currency") }, singleLine = true)
                }
                OutlinedTextField(d.offerDetails, { draft = d.copy(offerDetails = it.take(5000)) }, Modifier.fillMaxWidth(), label = { Text("Offer details (equity, bonus, joining date)") })
            }

            SectionTitle("Resume used")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                resumes.forEach { r -> FilterChip(d.resumeId == r.id, { draft = d.copy(resumeId = if (d.resumeId == r.id) null else r.id) }, { Text(r.name) }) }
                if (resumes.isEmpty()) Text("No resumes added.", style = MaterialTheme.typography.bodySmall)
            }

            SectionTitle("Notes")
            OutlinedTextField(d.notes, { draft = d.copy(notes = it.take(20_000)) }, Modifier.fillMaxWidth().height(160.dp), placeholder = { Text("Prep notes, questions asked, impressions…") })

            if (dirty) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = { scope.launch { c.applications.save(d); snack.showSnackbar("Saved") } }, modifier = Modifier.fillMaxWidth()) { Text("Save changes") }
            }

            if (d.jdSnapshot.isNotBlank()) {
                SectionTitle("Job description snapshot", if (showJd) "Hide" else "Show") { showJd = !showJd }
                Text("Saved when you added this application, so you keep it even if the posting is removed.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (showJd) Panel(Modifier.padding(top = 8.dp)) { Text(d.jdSnapshot, style = MaterialTheme.typography.bodySmall) }
            }

            SectionTitle("History")
            details?.events.orEmpty().sortedByDescending { it.at }.forEach { e ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text(fmtDate(e.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
                    Text(e.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (confirmDelete && d != null) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete this application?") },
        text = { Text("Its interviews, notes and history will be removed.") },
        confirmButton = { TextButton(onClick = { scope.launch { c.applications.delete(d); confirmDelete = false; nav.back() } }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })

    if (addInterview && d != null) {
        var round by remember { mutableStateOf("") }
        var mode by remember { mutableStateOf("") }
        var notes by remember { mutableStateOf("") }
        var at by remember { mutableStateOf<Long?>(null) }
        AlertDialog(onDismissRequest = { addInterview = false }, title = { Text("Schedule interview") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(round, { round = it.take(100) }, label = { Text("Round (e.g. Technical 1)") }, singleLine = true)
                OutlinedTextField(mode, { mode = it.take(100) }, label = { Text("Mode (video, onsite, phone)") }, singleLine = true)
                AssistChip(onClick = { pickDateTime(ctx) { at = it } }, label = { Text(at?.let { fmtDateTime(it) } ?: "Pick date & time") })
                OutlinedTextField(notes, { notes = it.take(5000) }, label = { Text("Notes") })
            }
        }, confirmButton = {
            TextButton(enabled = at != null, onClick = {
                val t = at ?: return@TextButton
                scope.launch {
                    c.applications.addInterview(d.id, t, round, mode, notes)
                    if (d.stage in setOf(Stage.SAVED, Stage.APPLIED)) c.applications.moveTo(d.id, Stage.INTERVIEW)
                    addInterview = false
                }
            }) { Text("Add") }
        }, dismissButton = { TextButton(onClick = { addInterview = false }) { Text("Cancel") } })
    }
}

private fun isDirty(a: ApplicationEntity, b: ApplicationEntity): Boolean =
    a.copy(updatedAt = 0, stage = "", appliedAt = null) != b.copy(updatedAt = 0, stage = "", appliedAt = null) ||
        (a.appliedAt != b.appliedAt && a.appliedAt != null)

@Composable
private fun DateRow(label: String, value: Long?, onPick: () -> Unit, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onPick) { Text(fmtDate(value)) }
        if (value != null) TextButton(onClick = onClear) { Text("Clear") }
    }
}
