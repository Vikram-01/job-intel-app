package com.cipherpath.data.repo

import android.content.Context
import androidx.room.withTransaction
import com.cipherpath.analysis.Taxonomy
import com.cipherpath.data.Settings
import com.cipherpath.data.SettingsStore
import com.cipherpath.data.db.AppDatabase
import com.cipherpath.data.db.CompanyEntity
import com.cipherpath.data.db.JobEntity
import com.cipherpath.data.db.JobFilter
import com.cipherpath.data.db.SyncRunEntity
import com.cipherpath.data.net.BackendClient
import com.cipherpath.data.net.BackendJob
import com.cipherpath.data.net.SafeHttp
import com.cipherpath.data.net.connectorFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

@Serializable
private data class SeedCompany(val name: String, val ats: String, val slug: String, val careers: String? = null)

@Serializable
private data class SeedFile(val companies: List<SeedCompany>)

data class SyncOutcome(
    val newJobIds: List<String>,
    val updated: Int,
    val expired: Int,
    val errors: List<String>,
    val mode: String,
)

class JobRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val tx: Taxonomy,
    private val settings: SettingsStore,
    private val http: SafeHttp,
    private val backend: BackendClient,
    private val matches: MatchEngine,
) {
    private val syncLock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    fun search(f: JobFilter, limit: Int, offset: Int): Flow<List<JobEntity>> = db.jobs().search(
        f.query?.trim()?.take(100)?.ifBlank { null }, f.roleGroup, f.roleCategory, f.country, f.workMode,
        f.seniority, f.company, f.skill, f.minSalary, f.postedAfter, f.minMatch, f.bookmarkedOnly, f.sort, limit, offset,
    )

    fun observe(id: String) = db.jobs().observe(id)
    suspend fun get(id: String) = db.jobs().get(id)
    suspend fun setBookmarked(id: String, v: Boolean) = db.jobs().setBookmarked(id, v)
    suspend fun markViewed(id: String) = db.jobs().markViewed(id, System.currentTimeMillis())

    suspend fun seedCompanies() = withContext(Dispatchers.IO) {
        val text = context.assets.open("companies.json").bufferedReader().use { it.readText() }
        val seed = json.decodeFromString(SeedFile.serializer(), text)
        db.companies().insertIgnore(seed.companies.mapNotNull { c ->
            if (!SafeHttp.SLUG.matches(c.slug) || c.ats !in Settings.SUPPORTED_ATS) null
            else CompanyEntity(name = c.name, ats = c.ats, slug = c.slug, careersUrl = c.careers?.takeIf { it.startsWith("https://") })
        })
    }

    /** Adds a user-specified board after validating ATS + slug (no arbitrary hosts → no SSRF). */
    suspend fun addCompany(name: String, ats: String, slug: String, careersUrl: String?): Result<Unit> = runCatching {
        require(name.isNotBlank() && name.length <= 200) { "Enter a company name" }
        require(ats in Settings.SUPPORTED_ATS) { "Unsupported ATS" }
        require(SafeHttp.SLUG.matches(slug.trim()) && !slug.contains("..")) { "Board ID may contain only letters, digits, '.', '_' and '-'" }
        require(careersUrl.isNullOrBlank() || careersUrl.startsWith("https://")) { "Careers URL must start with https://" }
        val ids = db.companies().insertIgnore(listOf(CompanyEntity(name = name.trim(), ats = ats, slug = slug.trim(),
            careersUrl = careersUrl?.ifBlank { null }, userAdded = true)))
        require(ids.firstOrNull() != -1L) { "That board is already in your list" }
    }

    suspend fun sync(trigger: String): SyncOutcome = syncLock.withLock {
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val s = settings.state.value
            val out = if (s.syncMode == "backend" && backend.configured()) syncBackend(started) else syncDirect(started)
            db.syncRuns().insert(SyncRunEntity(
                startedAt = started, finishedAt = System.currentTimeMillis(), trigger = trigger, mode = out.mode,
                newJobs = out.newJobIds.size, updatedJobs = out.updated, expiredJobs = out.expired,
                errors = out.errors.joinToString("\n").take(8000),
            ))
            db.jobs().purgeExpired(started - 30L * 24 * 3600 * 1000)
            out
        }
    }

    private suspend fun syncDirect(now: Long): SyncOutcome {
        if (db.companies().enabled().isEmpty()) seedCompanies()
        val groups = settings.state.value.roleGroups.ifEmpty { setOf("cyber", "fde") }
        val newIds = mutableListOf<String>()
        var updated = 0
        var expired = 0
        val errors = mutableListOf<String>()
        for (c in db.companies().enabled()) {
            val conn = connectorFor(c.ats, http)
            val raws = try {
                conn.list(c.slug, c.name).filter { it.title.isNotBlank() && Ingest.isTarget(it.title, tx, groups) }
            } catch (e: Exception) {
                val msg = "${c.name} (${c.ats}): ${e.javaClass.simpleName}: ${e.message?.take(140)}"
                errors += msg
                db.companies().update(c.copy(lastStatus = msg.take(200)))
                continue
            }
            val existing = db.jobs().forBoard(c.ats, c.name).associateBy { it.sourceJobId }
            val seen = HashSet<String>()
            val batch = mutableListOf<JobEntity>()
            for (raw0 in raws) {
                seen += raw0.sourceJobId
                val prev = existing[raw0.sourceJobId]
                var raw = raw0
                if (prev != null && prev.isActive && raw.description.isNotEmpty() && Ingest.contentHash(raw) == prev.contentHash) {
                    batch += prev.copy(lastSeenAt = now, missedSyncs = 0)
                    continue
                }
                if (raw.description.isEmpty()) {
                    raw = try { conn.details(c.slug, raw) } catch (e: Exception) {
                        errors += "${c.name}/${raw.sourceJobId}: details ${e.javaClass.simpleName}"; continue
                    }
                    if (prev != null && prev.isActive && Ingest.contentHash(raw) == prev.contentHash) {
                        batch += prev.copy(lastSeenAt = now, missedSyncs = 0); continue
                    }
                }
                batch += Ingest.toEntity(raw, tx, now, prev)
                if (prev == null) newIds += Ingest.jobId(raw.source, raw.sourceJobId) else updated++
            }
            for ((sid, j) in existing) if (sid !in seen && j.isActive) {
                val missed = j.missedSyncs + 1
                val stillActive = missed < EXPIRE_AFTER_MISSES
                if (!stillActive) expired++
                batch += j.copy(missedSyncs = missed, isActive = stillActive)
            }
            db.withTransaction {
                db.jobs().upsert(batch)
                db.companies().update(c.copy(lastStatus = "OK · ${raws.size} relevant postings", lastSyncedAt = now))
            }
        }
        dedup()
        matches.recomputeAll()
        return SyncOutcome(newIds, updated, expired, errors, "direct")
    }

    private suspend fun syncBackend(now: Long): SyncOutcome {
        val since = settings.state.value.lastBackendSync
        val (items, serverTime) = try { backend.changedSince(since) } catch (e: Exception) {
            return SyncOutcome(emptyList(), 0, 0, listOf("Backend: ${e.javaClass.simpleName}: ${e.message?.take(160)}"), "backend")
        }
        val newIds = mutableListOf<String>()
        var updated = 0
        var expired = 0
        val ids = items.map { Ingest.jobId(it.source, it.source_job_id) }
        val prevMap = ids.chunked(500).flatMap { db.jobs().getMany(it) }.associateBy { it.id }
        val batch = items.mapNotNull { b ->
            if (!b.apply_url.startsWith("https://")) return@mapNotNull null
            val id = Ingest.jobId(b.source, b.source_job_id)
            val prev = prevMap[id]
            if (prev == null && b.is_active) newIds += id
            else if (prev != null && prev.isActive && !b.is_active) expired++
            else if (prev != null) updated++
            fromBackend(b, prev, now)
        }
        db.jobs().upsert(batch)
        settings.update { it.copy(lastBackendSync = serverTime) }
        dedup()
        matches.recomputeAll()
        return SyncOutcome(newIds, updated, expired, emptyList(), "backend")
    }

    private fun fromBackend(b: BackendJob, prev: JobEntity?, now: Long): JobEntity {
        val (req, pref) = tx.splitRequiredPreferred(b.description_text)
        val first = BackendClient.parseTime(b.first_seen_at) ?: now
        return JobEntity(
            id = Ingest.jobId(b.source, b.source_job_id), source = b.source, sourceJobId = b.source_job_id,
            companyName = b.company_name, title = b.title, location = b.location, country = b.country,
            workMode = b.work_mode, employmentType = b.employment_type, seniority = b.seniority,
            roleCategory = b.role_category, roleGroup = b.role_group, department = b.department,
            description = b.description_text, skillsCsv = b.skills_csv,
            reqSkillsCsv = req.joinToString(","), prefSkillsCsv = pref.joinToString(","),
            minYears = b.min_years, salaryMin = b.salary_min, salaryMax = b.salary_max, salaryCurrency = b.salary_currency,
            applyUrl = b.apply_url, postedAt = BackendClient.parseTime(b.posted_at),
            firstSeenAt = prev?.firstSeenAt ?: first, lastSeenAt = now,
            updatedAt = BackendClient.parseTime(b.updated_at) ?: now,
            contentHash = b.updated_at, dedupKey = Ingest.dedupKey(b.company_name, b.title, b.location),
            isActive = b.is_active, duplicateOf = prev?.duplicateOf,
            bookmarked = prev?.bookmarked ?: false, viewedAt = prev?.viewedAt,
            bestMatchScore = prev?.bestMatchScore, bestResumeId = prev?.bestResumeId,
        )
    }

    private suspend fun dedup() {
        val active = db.jobs().activeOrdered()
        val dupes = Ingest.duplicates(active)
        val byId = active.associateBy { it.id }
        db.withTransaction {
            for ((id, canon) in dupes) if (byId[id]?.duplicateOf != canon) db.jobs().setDuplicate(id, canon)
        }
    }

    fun requestBackendSync() = runCatching { backend.triggerSync() }

    companion object {
        const val EXPIRE_AFTER_MISSES = 2
        fun isoNow(): String = Instant.now().toString()
    }
}
