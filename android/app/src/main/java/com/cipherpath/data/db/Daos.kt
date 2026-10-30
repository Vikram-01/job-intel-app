package com.cipherpath.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class JobFilter(
    val query: String? = null,
    val roleGroup: String? = null,
    val roleCategory: String? = null,
    val country: String? = null,
    val workMode: String? = null,
    val seniority: String? = null,
    val company: String? = null,
    val skill: String? = null,
    val minSalary: Double? = null,
    val postedAfter: Long? = null,
    val minMatch: Int? = null,
    val bookmarkedOnly: Boolean = false,
    val sort: String = "newest", // newest | match | salary | company | posted
)

data class CountRow(val key: String?, val n: Int)

@Dao
interface JobDao {
    // Every user-controlled value is a bound parameter; sort is selected via CASE, never concatenated.
    @Query(
        """
        SELECT * FROM jobs
        WHERE isActive = 1 AND duplicateOf IS NULL
          AND (:q IS NULL OR title LIKE '%' || :q || '%' OR companyName LIKE '%' || :q || '%' OR skillsCsv LIKE '%' || :q || '%' OR location LIKE '%' || :q || '%')
          AND (:roleGroup IS NULL OR roleGroup = :roleGroup)
          AND (:roleCategory IS NULL OR roleCategory = :roleCategory)
          AND (:country IS NULL OR country = :country)
          AND (:workMode IS NULL OR workMode = :workMode)
          AND (:seniority IS NULL OR seniority = :seniority)
          AND (:company IS NULL OR companyName = :company)
          AND (:skill IS NULL OR (',' || skillsCsv || ',') LIKE '%,' || :skill || ',%')
          AND (:minSalary IS NULL OR salaryMax >= :minSalary)
          AND (:postedAfter IS NULL OR COALESCE(postedAt, firstSeenAt) >= :postedAfter)
          AND (:minMatch IS NULL OR bestMatchScore >= :minMatch)
          AND (:bookmarkedOnly = 0 OR bookmarked = 1)
        ORDER BY
          CASE WHEN :sort = 'match' THEN COALESCE(bestMatchScore, -1) END DESC,
          CASE WHEN :sort = 'salary' THEN COALESCE(salaryMax, -1) END DESC,
          CASE WHEN :sort = 'company' THEN companyName END COLLATE NOCASE ASC,
          CASE WHEN :sort = 'posted' THEN COALESCE(postedAt, firstSeenAt) END DESC,
          firstSeenAt DESC, id
        LIMIT :limit OFFSET :offset
        """
    )
    fun search(
        q: String?, roleGroup: String?, roleCategory: String?, country: String?, workMode: String?,
        seniority: String?, company: String?, skill: String?, minSalary: Double?, postedAfter: Long?,
        minMatch: Int?, bookmarkedOnly: Boolean, sort: String, limit: Int, offset: Int,
    ): Flow<List<JobEntity>>

    @Query("SELECT * FROM jobs WHERE id = :id")
    fun observe(id: String): Flow<JobEntity?>

    @Query("SELECT * FROM jobs WHERE id = :id")
    suspend fun get(id: String): JobEntity?

    @Query("SELECT * FROM jobs WHERE id IN (:ids)")
    suspend fun getMany(ids: List<String>): List<JobEntity>

    @Query("SELECT * FROM jobs WHERE source = :source AND companyName = :company")
    suspend fun forBoard(source: String, company: String): List<JobEntity>

    @Query("SELECT * FROM jobs WHERE isActive = 1 AND duplicateOf IS NULL")
    suspend fun allActive(): List<JobEntity>

    @Query("SELECT * FROM jobs WHERE isActive = 1 ORDER BY firstSeenAt, id")
    suspend fun activeOrdered(): List<JobEntity>

    @Upsert
    suspend fun upsert(jobs: List<JobEntity>)

    @Query("UPDATE jobs SET bookmarked = :value WHERE id = :id")
    suspend fun setBookmarked(id: String, value: Boolean)

    @Query("UPDATE jobs SET viewedAt = :at WHERE id = :id AND viewedAt IS NULL")
    suspend fun markViewed(id: String, at: Long)

    @Query("UPDATE jobs SET duplicateOf = :canonical WHERE id = :id")
    suspend fun setDuplicate(id: String, canonical: String?)

    @Query("UPDATE jobs SET bestMatchScore = :score, bestResumeId = :resumeId WHERE id = :id")
    suspend fun setBestMatch(id: String, score: Int?, resumeId: String?)

    @Query("SELECT COUNT(*) FROM jobs WHERE isActive = 1 AND duplicateOf IS NULL AND firstSeenAt >= :since")
    fun countNewSince(since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM jobs WHERE isActive = 1 AND duplicateOf IS NULL")
    fun countActive(): Flow<Int>

    @Query("SELECT * FROM jobs WHERE isActive = 1 AND duplicateOf IS NULL AND firstSeenAt >= :since ORDER BY COALESCE(bestMatchScore,-1) DESC, firstSeenAt DESC LIMIT :limit")
    fun newSince(since: Long, limit: Int): Flow<List<JobEntity>>

    @Query("SELECT * FROM jobs WHERE isActive = 1 AND duplicateOf IS NULL AND bestMatchScore >= :min ORDER BY bestMatchScore DESC, firstSeenAt DESC LIMIT :limit")
    fun highMatch(min: Int, limit: Int): Flow<List<JobEntity>>

    @Query("SELECT * FROM jobs WHERE isActive = 1 AND duplicateOf IS NULL AND firstSeenAt >= :since AND bestMatchScore >= :min")
    suspend fun newHighMatch(since: Long, min: Int): List<JobEntity>

    @Query("SELECT companyName AS `key`, COUNT(*) AS n FROM jobs WHERE isActive = 1 AND duplicateOf IS NULL GROUP BY companyName ORDER BY companyName COLLATE NOCASE")
    fun companyCounts(): Flow<List<CountRow>>

    @Query("SELECT roleCategory AS `key`, COUNT(*) AS n FROM jobs WHERE isActive = 1 AND duplicateOf IS NULL GROUP BY roleCategory ORDER BY n DESC")
    fun roleCounts(): Flow<List<CountRow>>

    @Query("SELECT * FROM jobs WHERE isActive = 1 AND duplicateOf IS NULL AND salaryMax IS NOT NULL")
    fun withSalary(): Flow<List<JobEntity>>

    @Query("SELECT * FROM jobs WHERE bookmarked = 1 OR bestMatchScore >= :min")
    suspend fun interesting(min: Int): List<JobEntity>

    @Query("DELETE FROM jobs WHERE isActive = 0 AND bookmarked = 0 AND lastSeenAt < :before AND id NOT IN (SELECT jobId FROM applications WHERE jobId IS NOT NULL)")
    suspend fun purgeExpired(before: Long): Int

    @Query("DELETE FROM jobs")
    suspend fun deleteAll()
}

@Dao
interface ResumeDao {
    @Query("SELECT * FROM resumes ORDER BY isDefault DESC, importedAt DESC")
    fun observeAll(): Flow<List<ResumeEntity>>

    @Query("SELECT * FROM resumes ORDER BY isDefault DESC, importedAt DESC")
    suspend fun all(): List<ResumeEntity>

    @Query("SELECT * FROM resumes WHERE id = :id")
    suspend fun get(id: String): ResumeEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(r: ResumeEntity)

    @Update
    suspend fun update(r: ResumeEntity)

    @Query("DELETE FROM resumes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE resumes SET isDefault = (id = :id)")
    suspend fun setDefault(id: String)

    @Query("SELECT COUNT(*) FROM resumes")
    suspend fun count(): Int
}

@Dao
interface MatchDao {
    @Upsert
    suspend fun upsert(m: List<MatchEntity>)

    @Query("SELECT * FROM matches WHERE jobId = :jobId ORDER BY score DESC")
    fun forJob(jobId: String): Flow<List<MatchEntity>>

    @Query("SELECT * FROM matches WHERE jobId = :jobId ORDER BY score DESC")
    suspend fun forJobNow(jobId: String): List<MatchEntity>

    @Query("SELECT jobId, resumeId, jobHash FROM matches")
    suspend fun keys(): List<MatchKey>

    // SQLite returns the row holding MAX(score) for bare columns in an aggregate query.
    @Query("SELECT jobId, resumeId, MAX(score) AS score FROM matches GROUP BY jobId")
    suspend fun bestPerJob(): List<BestMatch>

    @Query("SELECT * FROM matches WHERE resumeId = :resumeId")
    suspend fun forResume(resumeId: String): List<MatchEntity>

    @Query("DELETE FROM matches")
    suspend fun deleteAll()
}

data class BestMatch(val jobId: String, val resumeId: String, val score: Int)

data class MatchKey(val jobId: String, val resumeId: String, val jobHash: String)

@Dao
interface CompanyDao {
    @Query("SELECT * FROM companies ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<CompanyEntity>>

    @Query("SELECT * FROM companies WHERE enabled = 1")
    suspend fun enabled(): List<CompanyEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(c: List<CompanyEntity>): List<Long>

    @Update
    suspend fun update(c: CompanyEntity)

    @Query("UPDATE companies SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM companies WHERE id = :id AND userAdded = 1")
    suspend fun deleteUserAdded(id: Long)

    @Query("SELECT * FROM companies WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): CompanyEntity?
}

data class StageCount(val stage: String, val n: Int)

data class ApplicationWithDetails(
    @androidx.room.Embedded val app: ApplicationEntity,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "applicationId")
    val interviews: List<InterviewEntity>,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "applicationId")
    val events: List<ApplicationEventEntity>,
)

@Dao
interface ApplicationDao {
    @Query("SELECT * FROM applications ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ApplicationEntity>>

    @Query("SELECT * FROM applications ORDER BY createdAt")
    suspend fun all(): List<ApplicationEntity>

    @Transaction
    @Query("SELECT * FROM applications WHERE id = :id")
    fun observeDetails(id: Long): Flow<ApplicationWithDetails?>

    @Transaction
    @Query("SELECT * FROM applications ORDER BY createdAt")
    suspend fun allDetails(): List<ApplicationWithDetails>

    @Query("SELECT * FROM applications WHERE id = :id")
    suspend fun get(id: Long): ApplicationEntity?

    @Query("SELECT * FROM applications WHERE jobId = :jobId LIMIT 1")
    fun observeForJob(jobId: String): Flow<ApplicationEntity?>

    @Query("SELECT stage, COUNT(*) AS n FROM applications GROUP BY stage")
    fun stageCounts(): Flow<List<StageCount>>

    @Insert
    suspend fun insert(a: ApplicationEntity): Long

    @Update
    suspend fun update(a: ApplicationEntity)

    @Delete
    suspend fun delete(a: ApplicationEntity)

    @Insert
    suspend fun insertEvent(e: ApplicationEventEntity)

    @Insert
    suspend fun insertInterview(i: InterviewEntity): Long

    @Query("DELETE FROM interviews WHERE id = :id")
    suspend fun deleteInterview(id: Long)

    @Query("SELECT * FROM interviews WHERE at >= :from ORDER BY at LIMIT :limit")
    fun upcomingInterviews(from: Long, limit: Int): Flow<List<InterviewEntity>>

    @Query("SELECT * FROM interviews WHERE reminded = 0 AND at BETWEEN :from AND :to")
    suspend fun interviewsDue(from: Long, to: Long): List<InterviewEntity>

    @Query("UPDATE interviews SET reminded = 1 WHERE id = :id")
    suspend fun markInterviewReminded(id: Long)

    @Query("SELECT * FROM applications WHERE followUpAt IS NOT NULL AND followUpAt <= :before AND stage NOT IN ('Offer','Rejected')")
    suspend fun followUpsDue(before: Long): List<ApplicationEntity>

    @Query("SELECT * FROM applications WHERE deadlineAt IS NOT NULL AND deadlineAt BETWEEN :from AND :to AND stage = 'Saved'")
    suspend fun deadlinesSoon(from: Long, to: Long): List<ApplicationEntity>
}

@Dao
interface NotificationDao {
    @Query("SELECT * FROM notifications ORDER BY at DESC LIMIT 300")
    fun observeAll(): Flow<List<NotificationEntity>>

    @Query("SELECT COUNT(*) FROM notifications WHERE read = 0")
    fun unreadCount(): Flow<Int>

    @Insert
    suspend fun insert(n: NotificationEntity): Long

    @Query("UPDATE notifications SET read = 1")
    suspend fun markAllRead()

    @Query("DELETE FROM notifications")
    suspend fun clear()
}

@Dao
interface SyncRunDao {
    @Insert
    suspend fun insert(r: SyncRunEntity)

    @Query("SELECT * FROM sync_runs ORDER BY id DESC LIMIT 20")
    fun recent(): Flow<List<SyncRunEntity>>

    @Query("SELECT * FROM sync_runs ORDER BY id DESC LIMIT 1")
    fun last(): Flow<SyncRunEntity?>
}
