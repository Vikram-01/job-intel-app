package com.cipherpath.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.cipherpath.AppContainer
import com.cipherpath.R
import com.cipherpath.work.WorkScheduler

@Composable
fun OnboardingScreen(c: AppContainer, onDone: () -> Unit) {
    val s by c.settings.state.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(120.dp))
            Text("CipherPath", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text("Job intelligence for security and forward-deployed engineering careers.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(28.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("What are you looking for?", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = "cyber" in s.roleGroups, onClick = {
                        c.settings.update { it.copy(roleGroups = it.roleGroups.toggle("cyber")) }
                    }, label = { Text("Cybersecurity") })
                    FilterChip(selected = "fde" in s.roleGroups, onClick = {
                        c.settings.update { it.copy(roleGroups = it.roleGroups.toggle("fde")) }
                    }, label = { Text("FDE / Solutions") })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Prefer remote roles", Modifier.weight(1f))
                    Switch(s.preferRemote, { v -> c.settings.update { it.copy(preferRemote = v) } })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Prefer roles in India", Modifier.weight(1f))
                    Switch(s.preferIndia, { v -> c.settings.update { it.copy(preferIndia = v) } })
                }
                Spacer(Modifier.height(8.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Your data stays on this phone", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text("Resumes, matches and applications are encrypted on-device. Jobs are fetched from public company job boards. " +
                            "Nothing about you is sent to job sites; \"Apply\" simply opens the employer's page in your browser.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (Build.VERSION.SDK_INT >= 33) OutlinedButton(onClick = { notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Allow job alerts")
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    if (s.roleGroups.isEmpty()) c.settings.update { it.copy(roleGroups = setOf("cyber", "fde")) }
                    onDone()
                    WorkScheduler.syncNow(ctx)
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Find jobs") }
        }
    }
}

fun Set<String>.toggle(v: String): Set<String> = if (v in this) this - v else this + v
