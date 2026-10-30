package com.cipherpath.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cipherpath.CipherPathApp
import com.cipherpath.data.AppSettings
import java.time.Duration
import java.util.concurrent.TimeUnit

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as CipherPathApp).container
        val trigger = inputData.getString("trigger") ?: "scheduled"
        val out = try { c.jobs.sync(trigger) } catch (e: Exception) {
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
        val s = c.settings.state.value
        if (out.newJobIds.isNotEmpty() && s.notifyNewJobs) {
            val newJobs = c.db.jobs().getMany(out.newJobIds.take(200)).filter { it.duplicateOf == null }
            if (newJobs.isNotEmpty()) {
                val top = newJobs.sortedByDescending { it.bestMatchScore ?: -1 }.take(3)
                c.notifier.notify(Notifier.CH_NEW, "new_jobs", "${newJobs.size} new matching roles",
                    top.joinToString("\n") { "• ${it.title} — ${it.companyName}" + (it.bestMatchScore?.let { m -> " ($m%)" } ?: "") },
                    route = "dashboard")
            }
        }
        if (s.notifyHighMatch && out.newJobIds.isNotEmpty()) {
            val ids = out.newJobIds.toSet()
            val high = c.db.jobs().newHighMatch(System.currentTimeMillis() - Duration.ofDays(2).toMillis(), s.highMatchThreshold)
                .filter { it.id in ids }.sortedByDescending { it.bestMatchScore }.take(5)
            for (j in high) c.notifier.notify(Notifier.CH_MATCH, "high_match", "${j.bestMatchScore}% match: ${j.title}",
                "${j.companyName} · ${j.location}", route = "job/${j.id}", jobId = j.id)
        }
        if (out.errors.isNotEmpty() && out.newJobIds.isEmpty() && out.updated == 0 && trigger == "manual") {
            c.notifier.notify(Notifier.CH_DIGEST, "sync_error", "Sync finished with errors", out.errors.take(5).joinToString("\n"), route = "settings", post = false)
        }
        return Result.success()
    }
}

class DigestWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as CipherPathApp).container
        val s = c.settings.state.value
        if (!s.notifyDigest) return Result.success()
        val since = System.currentTimeMillis() - Duration.ofDays(1).toMillis()
        val jobs = c.db.jobs().allActive().filter { it.firstSeenAt >= since }
        if (jobs.isEmpty()) return Result.success()
        val prefRemote = if (s.preferRemote) jobs.filter { it.workMode == "remote" } else jobs
        val prefIndia = if (s.preferIndia) prefRemote.filter { it.country == "IN" || it.workMode == "remote" } else prefRemote
        val picks = prefIndia.sortedByDescending { it.bestMatchScore ?: -1 }.take(5)
        if (picks.isEmpty()) return Result.success()
        c.notifier.notify(Notifier.CH_DIGEST, "digest", "Today's digest: ${jobs.size} new roles",
            picks.joinToString("\n") { "• ${it.title} — ${it.companyName}" + (it.bestMatchScore?.let { m -> " · $m%" } ?: "") },
            route = "dashboard")
        return Result.success()
    }
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as CipherPathApp).container
        if (!c.settings.state.value.notifyReminders) return Result.success()
        val now = System.currentTimeMillis()
        val dao = c.db.applications()
        for (iv in dao.interviewsDue(now, now + Duration.ofHours(26).toMillis())) {
            val app = dao.get(iv.applicationId) ?: continue
            c.notifier.notify(Notifier.CH_REMIND, "reminder", "Interview soon: ${app.company}",
                "${iv.round} — ${app.title}", route = "application/${app.id}", appId = app.id)
            dao.markInterviewReminded(iv.id)
        }
        for (a in dao.followUpsDue(now)) {
            c.notifier.notify(Notifier.CH_REMIND, "reminder", "Follow up with ${a.company}", a.title, route = "application/${a.id}", appId = a.id)
            dao.update(a.copy(followUpAt = null, updatedAt = now))
        }
        for (a in dao.deadlinesSoon(now, now + Duration.ofDays(2).toMillis())) {
            c.notifier.notify(Notifier.CH_REMIND, "reminder", "Deadline approaching: ${a.company}", "Apply for ${a.title} before the deadline.",
                route = "application/${a.id}", appId = a.id)
        }
        return Result.success()
    }
}

object WorkScheduler {
    private const val SYNC = "periodic_sync"
    private const val DIGEST = "daily_digest"
    private const val REMIND = "reminders"
    const val MANUAL = "manual_sync"

    fun schedule(context: Context, s: AppSettings, replace: Boolean = false) {
        val wm = WorkManager.getInstance(context)
        val net = Constraints.Builder()
            .setRequiredNetworkType(if (s.syncWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val policy = if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP
        wm.enqueueUniquePeriodicWork(SYNC, policy,
            PeriodicWorkRequestBuilder<SyncWorker>(s.syncIntervalHours.toLong(), TimeUnit.HOURS)
                .setConstraints(net)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build())
        wm.enqueueUniquePeriodicWork(DIGEST, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DigestWorker>(24, TimeUnit.HOURS).setInitialDelay(hoursUntil(8), TimeUnit.MINUTES).build())
        wm.enqueueUniquePeriodicWork(REMIND, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderWorker>(6, TimeUnit.HOURS).build())
    }

    fun syncNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(MANUAL, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf("trigger" to "manual"))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build())
    }

    /** Minutes until the next local [hour]:00, for the morning digest. */
    private fun hoursUntil(hour: Int): Long {
        val now = java.time.LocalDateTime.now()
        var next = now.toLocalDate().atTime(hour, 0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next).toMinutes()
    }

    fun cancelAll(context: Context) = WorkManager.getInstance(context).cancelAllWork()
}
