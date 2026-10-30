package com.cipherpath.ui

import android.net.Uri
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ViewKanban
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cipherpath.AppContainer
import com.cipherpath.ui.screens.ApplicationScreen
import com.cipherpath.ui.screens.AtsScreen
import com.cipherpath.ui.screens.CompareScreen
import com.cipherpath.ui.screens.DashboardScreen
import com.cipherpath.ui.screens.DiscoverScreen
import com.cipherpath.ui.screens.IntelScreen
import com.cipherpath.ui.screens.JobDetailScreen
import com.cipherpath.ui.screens.MoreScreen
import com.cipherpath.ui.screens.NotificationsScreen
import com.cipherpath.ui.screens.OnboardingScreen
import com.cipherpath.ui.screens.ResumesScreen
import com.cipherpath.ui.screens.SettingsScreen
import com.cipherpath.ui.screens.SourcesScreen
import com.cipherpath.ui.screens.TrackerScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("dashboard", "Home", Icons.Outlined.Dashboard),
    Tab("discover", "Discover", Icons.Outlined.Search),
    Tab("tracker", "Tracker", Icons.Outlined.ViewKanban),
    Tab("resumes", "Resumes", Icons.Outlined.Description),
    Tab("more", "More", Icons.Outlined.MoreHoriz),
)

/** App-wide navigation actions handed to screens. */
class Nav(private val nav: NavHostController, val compare: MutableList<String>) {
    fun job(id: String) = nav.navigate("job/${Uri.encode(id)}")
    fun application(id: Long) = nav.navigate("application/$id")
    fun ats(jobId: String? = null) = nav.navigate(if (jobId != null) "ats?jobId=${Uri.encode(jobId)}" else "ats")
    fun to(route: String) = nav.navigate(route) { launchSingleTop = true }
    fun tab(route: String) = nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    fun back() { nav.popBackStack() }
}

@Composable
fun CipherPathRoot(c: AppContainer, pendingRoute: MutableState<String?>) {
    val navController = rememberNavController()
    val compare = remember { mutableStateListOf<String>() }
    val nav = remember(navController) { Nav(navController, compare) }
    val settings by c.settings.state.collectAsState()
    val unread by c.db.notifications().unreadCount().collectAsState(initial = 0)
    val entry by navController.currentBackStackEntryAsState()
    val current = entry?.destination?.route?.substringBefore("?")

    LaunchedEffect(pendingRoute.value) {
        val r = pendingRoute.value ?: return@LaunchedEffect
        pendingRoute.value = null
        when {
            r.startsWith("job/") -> nav.job(r.removePrefix("job/"))
            r.startsWith("application/") -> r.removePrefix("application/").toLongOrNull()?.let { nav.application(it) }
            else -> nav.to(r)
        }
    }

    if (!settings.onboarded) {
        OnboardingScreen(c) { c.settings.update { it.copy(onboarded = true) } }
        return
    }

    val showBar = current in TABS.map { it.route }
    Scaffold(
        // Each screen's TopAppBar handles the status bar; the NavigationBar handles the nav bar.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBar) NavigationBar {
                TABS.forEach { t ->
                    NavigationBarItem(
                        selected = current == t.route,
                        onClick = { nav.tab(t.route) },
                        icon = {
                            if (t.route == "more" && unread > 0) BadgedBox(badge = { Badge { Text(if (unread > 99) "99+" else "$unread") } }) { Icon(t.icon, t.label) }
                            else Icon(t.icon, t.label)
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        NavHost(
            navController, startDestination = "dashboard",
            modifier = Modifier.padding(pad)
                .then(if (showBar) Modifier else Modifier.navigationBarsPadding())
                .imePadding(),
        ) {
            composable("dashboard") { DashboardScreen(c, nav) }
            composable("discover") { DiscoverScreen(c, nav) }
            composable("tracker") { TrackerScreen(c, nav) }
            composable("resumes") { ResumesScreen(c, nav) }
            composable("more") { MoreScreen(c, nav) }
            composable("job/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                JobDetailScreen(c, nav, it.arguments?.getString("id").orEmpty())
            }
            composable("application/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                ApplicationScreen(c, nav, it.arguments?.getLong("id") ?: 0L)
            }
            composable("ats?jobId={jobId}", arguments = listOf(navArgument("jobId") { type = NavType.StringType; nullable = true; defaultValue = null })) {
                AtsScreen(c, nav, it.arguments?.getString("jobId"))
            }
            composable("compare") { CompareScreen(c, nav) }
            composable("intel") { IntelScreen(c, nav) }
            composable("notifications") { NotificationsScreen(c, nav) }
            composable("settings") { SettingsScreen(c, nav) }
            composable("sources") { SourcesScreen(c, nav) }
        }
    }
}
