package com.cipherpath.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cipherpath.AppContainer
import com.cipherpath.BuildConfig
import com.cipherpath.ui.EmptyState
import com.cipherpath.ui.Nav
import com.cipherpath.ui.relTime
import kotlinx.coroutines.launch

@Composable
fun MoreScreen(c: AppContainer, nav: Nav) {
    val unread by c.db.notifications().unreadCount().collectAsStateWithLifecycle(0)
    ScreenScaffold("More") { pad ->
        ScrollBody(pad) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MoreItem(Icons.Outlined.FactCheck, "ATS Analyzer", "Check a resume against any JD") { nav.ats() }
                MoreItem(Icons.Outlined.Insights, "Career intelligence", "Skill gaps, certifications, salary insights, companies") { nav.to("intel") }
                MoreItem(Icons.Outlined.Notifications, "Notifications", if (unread > 0) "$unread unread" else "Alerts and digests", badge = unread) { nav.to("notifications") }
                MoreItem(Icons.Outlined.Bookmark, "Saved jobs", "Jobs you bookmarked") { nav.tab("discover") }
                MoreItem(Icons.Outlined.Compare, "Compare jobs", "${nav.compare.size} selected") { nav.to("compare") }
                MoreItem(Icons.Outlined.Business, "Job sources", "Company boards and other sites") { nav.to("sources") }
                MoreItem(Icons.Outlined.Settings, "Settings & data", "Sync, alerts, theme, privacy") { nav.to("settings") }
            }
            Text("CipherPath ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp))
        }
    }
}

@Composable
private fun MoreItem(icon: ImageVector, title: String, subtitle: String, badge: Int = 0, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (badge > 0) Badge { Text("$badge") }
        }
    }
}

@Composable
fun NotificationsScreen(c: AppContainer, nav: Nav) {
    val scope = rememberCoroutineScope()
    val items by c.db.notifications().observeAll().collectAsStateWithLifecycle(null)
    ScreenScaffold("Notifications", onBack = nav::back, actions = {
        TextButton(onClick = { scope.launch { c.db.notifications().markAllRead() } }) { Text("Mark read") }
        TextButton(onClick = { scope.launch { c.db.notifications().clear() } }) { Text("Clear") }
    }) { pad ->
        val list = items
        when {
            list == null -> com.cipherpath.ui.Loading(Modifier.padding(pad))
            list.isEmpty() -> Column(Modifier.padding(pad)) {
                EmptyState(Icons.Outlined.NotificationsNone, "You're all caught up", "New-job alerts, high-match alerts, daily digests and reminders will appear here.")
            }
            else -> LazyColumn(Modifier.padding(pad), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { n ->
                    Surface(onClick = {
                        when {
                            n.jobId != null -> nav.job(n.jobId)
                            n.applicationId != null -> nav.application(n.applicationId)
                            n.kind == "sync_error" -> nav.to("settings")
                            else -> nav.tab("dashboard")
                        }
                    }, shape = MaterialTheme.shapes.large,
                        color = if (n.read) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row {
                                Text(n.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                Text(relTime(n.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(n.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
