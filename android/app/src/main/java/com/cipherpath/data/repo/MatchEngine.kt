package com.cipherpath.data.repo

import com.cipherpath.analysis.Matcher
import com.cipherpath.analysis.MatchResult
import com.cipherpath.analysis.ResumeDoc
import com.cipherpath.analysis.Taxonomy
import com.cipherpath.data.db.AppDatabase
import com.cipherpath.data.db.JobEntity
import com.cipherpath.data.db.MatchEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Computes resume×job matches incrementally (only when a job's content or the resume changed). */
class MatchEngine(private val db: AppDatabase, private val tx: Taxonomy) {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    fun decodeResume(s: String): ResumeDoc = json.decodeFromString(ResumeDoc.serializer(), s)
    fun decodeMatch(s: String): MatchResult = json.decodeFromString(MatchResult.serializer(), s)

    fun compute(resume: ResumeDoc, job: JobEntity): MatchResult =
        Matcher.match(resume, job.title, job.description, job.minYears, tx,
            Ingest.csv(job.reqSkillsCsv) to Ingest.csv(job.prefSkillsCsv))

    suspend fun recomputeAll(force: Boolean = false) = lock.withLock {
        withContext(Dispatchers.Default) {
            val resumes = db.resumes().all().map { it.id to decodeResume(it.analysisJson) }
            val jobs = db.jobs().allActive()
            if (resumes.isEmpty()) {
                jobs.filter { it.bestMatchScore != null }.forEach { db.jobs().setBestMatch(it.id, null, null) }
                return@withContext
            }
            val existing = db.matches().keys().associateBy({ it.jobId to it.resumeId }, { it.jobHash })
            val now = System.currentTimeMillis()
            val batch = mutableListOf<MatchEntity>()
            for (job in jobs) {
                for ((rid, doc) in resumes) {
                    if (!force && existing[job.id to rid] == job.contentHash) continue
                    val r = compute(doc, job)
                    batch += MatchEntity(job.id, rid, r.score, json.encodeToString(MatchResult.serializer(), r), job.contentHash, now)
                }
                if (batch.size >= 300) { db.matches().upsert(batch.toList()); batch.clear() }
            }
            if (batch.isNotEmpty()) db.matches().upsert(batch)
            val best = db.matches().bestPerJob().associateBy { it.jobId }
            for (job in jobs) {
                val b = best[job.id]
                if (b?.score != job.bestMatchScore || b?.resumeId != job.bestResumeId)
                    db.jobs().setBestMatch(job.id, b?.score, b?.resumeId)
            }
        }
    }
}
