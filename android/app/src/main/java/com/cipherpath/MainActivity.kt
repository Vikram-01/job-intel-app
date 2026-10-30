package com.cipherpath

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cipherpath.ui.CipherPathRoot
import com.cipherpath.ui.theme.CipherPathTheme
import com.cipherpath.work.Notifier

class MainActivity : ComponentActivity() {
    private val pendingRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Resumes and personal data are on screen: keep them out of screenshots and the recents thumbnail.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        pendingRoute.value = sanitizeRoute(intent)
        val container = (application as CipherPathApp).container
        setContent {
            val settings by container.settings.state.collectAsStateWithLifecycle()
            CipherPathTheme(theme = settings.theme) {
                CipherPathRoot(container, pendingRoute)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingRoute.value = sanitizeRoute(intent)
    }

    /** Only accept internal routes from our own notifications; never arbitrary deep links. */
    private fun sanitizeRoute(i: Intent?): String? {
        val r = i?.getStringExtra(Notifier.EXTRA_ROUTE) ?: return null
        return r.takeIf { it.matches(Regex("^(dashboard|settings|notifications|job/[a-z]+:[A-Za-z0-9._-]{1,200}|application/\\d{1,12})$")) }
    }
}
