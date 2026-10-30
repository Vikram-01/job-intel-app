package com.cipherpath.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "jobs",
    indices = [
        Index("dedupKey"), Index("isActive", "firstSeenAt"), Index("roleCategory"),
        Index("companyName"), Index("bookmarked"),
    ],
)
data class JobEntity(
    @PrimaryKey val id: String, // "$source:$sourceJobId"
    val source: String,
    val sourceJobId: String,
    val companyName: String,
    val title: String,
    val location: String,
    val country: String?,
    val workMode: String,
    val employmentType: String?,
    val seniority: String,
    val roleCategory: String?,
    val roleGroup: String?,
    val department: String?,
    val description: String,
    val skillsCsv: String,
    val reqSkillsCsv: String = "",
    val prefSkillsCsv: String = "",
    val minYears: Int?,
    val salaryMin: Double?,
    val salaryMax: Double?,
    val salaryCurrency: String?,
    val applyUrl: String,
    val postedAt: Long?,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val updatedAt: Long,
    val contentHash: String,
    val dedupKey: String,
    val missedSyncs: Int = 0,
    val isActive: Boolean = true,
    val duplicateOf: String? = null,
    val bookmarked: Boolean = false,
    val viewedAt: Long? = null,
    val bestMatchScore: Int? = null,
    val bestResumeId: String? = null,
)

@Entity(tableName = "resumes")
data class ResumeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val fileName: String,
    val fileType: String,
    val sizeBytes: Long,
    val importedAt: Long,
    val isDefault: Boolean,
    val skillsCsv: String,
    val yearsExperience: Double,
    val analysisJson: String, // serialized ResumeDoc (DB is encrypted)
)

@Entity(
    tableName = "matches",
    primaryKeys = ["jobId", "resumeId"],
    foreignKeys = [
        ForeignKey(entity = JobEntity::class, parentColumns = ["id"], childColumns = ["jobId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ResumeEntity::class, parentColumns = ["id"], childColumns = ["resumeId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("resumeId")],
)
data class MatchEntity(
    val jobId: String,
    val resumeId: String,
    val score: Int,
    val resultJson: String,
    val jobHash: String,
    val computedAt: Long,
)

@Entity(tableName = "companies", indices = [Index(value = ["ats", "slug"], unique = true)])
data class CompanyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val ats: String,
    val slug: String,
    val careersUrl: String?,
    val enabled: Boolean = true,
    val lastStatus: String? = null,
    val lastSyncedAt: Long? = null,
    val userAdded: Boolean = false,
)

object Stage {
    const val SAVED = "Saved"
    const val APPLIED = "Applied"
    const val ASSESSMENT = "Assessment"
    const val INTERVIEW = "Interview"
    const val TECHNICAL = "Technical Round"
    const val HR = "HR"
    const val OFFER = "Offer"
    const val REJECTED = "Rejected"
    val ALL = listOf(SAVED, APPLIED, ASSESSMENT, INTERVIEW, TECHNICAL, HR, OFFER, REJECTED)
}

@Entity(tableName = "applications", indices = [Index("stage"), Index("jobId")])
data class ApplicationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: String?,
    val company: String,
    val title: String,
    val applyUrl: String?,
    val location: String = "",
    val stage: String = Stage.SAVED,
    val appliedAt: Long? = null,
    val deadlineAt: Long? = null,
    val followUpAt: Long? = null,
    val recruiterName: String = "",
    val recruiterEmail: String = "",
    val recruiterPhone: String = "",
    val notes: String = "",
    val expectedSalary: String = "",
    val offerAmount: Double? = null,
    val offerCurrency: String = "",
    val offerDetails: String = "",
    val resumeId: String? = null,
    val jdSnapshot: String = "",
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "interviews",
    foreignKeys = [ForeignKey(entity = ApplicationEntity::class, parentColumns = ["id"], childColumns = ["applicationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("applicationId"), Index("at")],
)
data class InterviewEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val applicationId: Long,
    val at: Long,
    val round: String,
    val mode: String = "",
    val notes: String = "",
    val reminded: Boolean = false,
)

@Entity(
    tableName = "application_events",
    foreignKeys = [ForeignKey(entity = ApplicationEntity::class, parentColumns = ["id"], childColumns = ["applicationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("applicationId")],
)
data class ApplicationEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val applicationId: Long,
    val at: Long,
    val type: String,
    val detail: String,
)

@Entity(tableName = "notifications", indices = [Index("at")])
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val kind: String, // new_jobs | high_match | digest | reminder | sync_error
    val title: String,
    val body: String,
    val jobId: String? = null,
    val applicationId: Long? = null,
    val read: Boolean = false,
)

@Entity(tableName = "sync_runs")
data class SyncRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val finishedAt: Long,
    val trigger: String,
    val mode: String,
    val newJobs: Int,
    val updatedJobs: Int,
    val expiredJobs: Int,
    val errors: String,
)
