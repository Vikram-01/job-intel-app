package com.cipherpath

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import com.cipherpath.analysis.Taxonomy
import com.cipherpath.data.SettingsStore
import com.cipherpath.data.db.AppDatabase
import com.cipherpath.data.net.BackendClient
import com.cipherpath.data.net.SafeHttp
import com.cipherpath.data.repo.ApplicationRepository
import com.cipherpath.data.repo.JobRepository
import com.cipherpath.data.repo.MatchEngine
import com.cipherpath.data.repo.ResumeRepository
import com.cipherpath.security.DatabaseKey
import com.cipherpath.security.KeystoreCrypto
import com.cipherpath.security.SecureFileStore
import com.cipherpath.work.Notifier
import com.cipherpath.work.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class AppContainer(val context: Context) {
    val settings = SettingsStore(context)
    val taxonomy: Taxonomy = Taxonomy.parse(context.assets.open("taxonomy.json").bufferedReader().use { it.readText() })
    val db: AppDatabase = AppDatabase.build(context)
    val files = SecureFileStore(context)
    val http = SafeHttp { settings.state.value.backendUrl.toHttpUrlOrNull()?.host }
    val backend = BackendClient(http, { settings.state.value.backendUrl }, { settings.apiKey })
    val matches = MatchEngine(db, taxonomy)
    val jobs = JobRepository(context, db, taxonomy, settings, http, backend, matches)
    val resumes = ResumeRepository(context, db, taxonomy, files, matches)
    val applications = ApplicationRepository(db)
    val notifier = Notifier(context, db.notifications())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Irreversibly deletes all local data: DB, encrypted resumes, secrets, keys, scheduled work. */
    fun wipeEverything() {
        WorkScheduler.cancelAll(context)
        db.close()
        context.deleteDatabase(AppDatabase.NAME)
        files.wipe()
        settings.wipe()
        DatabaseKey.delete(context)
        KeystoreCrypto.deleteKey()
    }
}

class CipherPathApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()
        container.scope.launch {
            container.jobs.seedCompanies()
            WorkScheduler.schedule(this@CipherPathApp, container.settings.state.value)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()
}
