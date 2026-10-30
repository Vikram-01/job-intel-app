package com.cipherpath.data.repo

import com.cipherpath.data.db.AppDatabase
import com.cipherpath.data.db.ApplicationEntity
import com.cipherpath.data.db.ApplicationEventEntity
import com.cipherpath.data.db.InterviewEntity
import com.cipherpath.data.db.JobEntity
import com.cipherpath.data.db.Stage

class ApplicationRepository(private val db: AppDatabase) {
    private val dao = db.applications()

    fun observeAll() = dao.observeAll()
    fun observeDetails(id: Long) = dao.observeDetails(id)
    fun observeForJob(jobId: String) = dao.observeForJob(jobId)
    fun stageCounts() = dao.stageCounts()
    fun upcomingInterviews() = dao.upcomingInterviews(System.currentTimeMillis(), 10)

    /** Creates a tracker card with a frozen snapshot of the JD (the posting may later disappear). */
    suspend fun createFromJob(job: JobEntity, resumeId: String?, stage: String = Stage.SAVED): Long {
        val now = System.currentTimeMillis()
        val id = dao.insert(ApplicationEntity(
            jobId = job.id, company = job.companyName, title = job.title, applyUrl = job.applyUrl,
            location = job.location, stage = stage, appliedAt = if (stage == Stage.APPLIED) now else null,
            resumeId = resumeId, jdSnapshot = job.description.take(60_000), createdAt = now, updatedAt = now,
        ))
        dao.insertEvent(ApplicationEventEntity(applicationId = id, at = now, type = "created", detail = "Added to $stage"))
        return id
    }

    suspend fun createManual(company: String, title: String, url: String?): Long {
        val now = System.currentTimeMillis()
        val safeUrl = url?.trim()?.takeIf { it.startsWith("https://") && it.length <= 1000 }
        val id = dao.insert(ApplicationEntity(jobId = null, company = company.trim().take(200), title = title.trim().take(300),
            applyUrl = safeUrl, createdAt = now, updatedAt = now))
        dao.insertEvent(ApplicationEventEntity(applicationId = id, at = now, type = "created", detail = "Added manually"))
        return id
    }

    suspend fun moveTo(id: Long, stage: String) {
        require(stage in Stage.ALL)
        val a = dao.get(id) ?: return
        if (a.stage == stage) return
        val now = System.currentTimeMillis()
        dao.update(a.copy(stage = stage, updatedAt = now,
            appliedAt = a.appliedAt ?: if (stage != Stage.SAVED && stage != Stage.REJECTED) now else null))
        dao.insertEvent(ApplicationEventEntity(applicationId = id, at = now, type = "stage", detail = "${a.stage} → $stage"))
    }

    suspend fun save(a: ApplicationEntity) {
        val prev = dao.get(a.id) ?: return
        val now = System.currentTimeMillis()
        val clean = a.copy(
            recruiterName = a.recruiterName.take(200), recruiterEmail = a.recruiterEmail.take(200),
            recruiterPhone = a.recruiterPhone.take(40), notes = a.notes.take(20_000),
            expectedSalary = a.expectedSalary.take(100), offerCurrency = a.offerCurrency.take(8),
            offerDetails = a.offerDetails.take(5000), updatedAt = now,
        )
        dao.update(clean)
        if (prev.offerAmount != clean.offerAmount && clean.offerAmount != null)
            dao.insertEvent(ApplicationEventEntity(applicationId = a.id, at = now, type = "offer", detail = "Offer recorded: ${clean.offerCurrency} ${clean.offerAmount}"))
        if (prev.notes != clean.notes)
            dao.insertEvent(ApplicationEventEntity(applicationId = a.id, at = now, type = "note", detail = "Notes updated"))
    }

    suspend fun addInterview(appId: Long, at: Long, round: String, mode: String, notes: String) {
        dao.insertInterview(InterviewEntity(applicationId = appId, at = at, round = round.take(100).ifBlank { "Interview" },
            mode = mode.take(100), notes = notes.take(5000)))
        dao.insertEvent(ApplicationEventEntity(applicationId = appId, at = System.currentTimeMillis(), type = "interview", detail = "Scheduled: ${round.take(100)}"))
    }

    suspend fun deleteInterview(id: Long) = dao.deleteInterview(id)
    suspend fun delete(a: ApplicationEntity) = dao.delete(a)
    suspend fun allDetails() = dao.allDetails()
}
