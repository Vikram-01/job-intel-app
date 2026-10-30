package com.cipherpath.data.repo

import com.cipherpath.analysis.Taxonomy
import com.cipherpath.data.db.JobEntity
import com.cipherpath.data.net.RawJob
import java.security.MessageDigest

/** Pure ingestion rules shared by direct and backend sync; mirrors backend/app/ingest.py. */
object Ingest {
    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    fun dedupKey(company: String, title: String, location: String): String {
        var t = norm(title).replace(Regex("\\b(sr|snr)\\b"), "senior")
        t = t.replace(Regex("\\s*\\(?(remote|hybrid)\\)?$"), "")
        val loc = norm(location.split("/")[0].split(";")[0])
        return sha256("${norm(company)}|$t|$loc")
    }

    fun contentHash(r: RawJob): String = sha256("${r.title}|${r.location}|${r.description}")

    fun jobId(source: String, sourceJobId: String) = "$source:$sourceJobId"

    fun isTarget(title: String, tx: Taxonomy, groups: Set<String>): Boolean {
        val (role, group) = tx.classifyRole(title)
        return role != null && group in groups
    }

    fun toEntity(r: RawJob, tx: Taxonomy, now: Long, previous: JobEntity?): JobEntity {
        val desc = r.description
        val (role, group) = tx.classifyRole(r.title)
        var smin = r.salaryMin
        var smax = r.salaryMax
        var cur = r.salaryCurrency
        if (smin == null) tx.parseSalary(desc).let { (a, b, c) -> smin = a; smax = b; cur = c }
        val (req, pref) = tx.splitRequiredPreferred(desc)
        return JobEntity(
            id = jobId(r.source, r.sourceJobId),
            source = r.source,
            sourceJobId = r.sourceJobId,
            companyName = r.companyName,
            title = r.title,
            location = r.location,
            country = tx.classifyCountry(r.location),
            workMode = tx.classifyWorkMode(r.location, desc, r.workplaceHint),
            employmentType = r.employmentType,
            seniority = tx.classifySeniority(r.title),
            roleCategory = role,
            roleGroup = group,
            department = r.department,
            description = desc,
            skillsCsv = tx.extractSkills(desc).joinToString(","),
            reqSkillsCsv = req.joinToString(","),
            prefSkillsCsv = pref.joinToString(","),
            minYears = tx.minYearsRequired(desc),
            salaryMin = smin,
            salaryMax = smax,
            salaryCurrency = cur,
            applyUrl = r.applyUrl,
            postedAt = r.postedAt,
            firstSeenAt = previous?.firstSeenAt ?: now,
            lastSeenAt = now,
            updatedAt = now,
            contentHash = contentHash(r),
            dedupKey = dedupKey(r.companyName, r.title, r.location),
            missedSyncs = 0,
            isActive = true,
            duplicateOf = previous?.duplicateOf,
            bookmarked = previous?.bookmarked ?: false,
            viewedAt = previous?.viewedAt,
            bestMatchScore = previous?.bestMatchScore,
            bestResumeId = previous?.bestResumeId,
        )
    }

    /** Returns (jobId -> canonicalId or null) for active jobs ordered by firstSeenAt. */
    fun duplicates(activeOrdered: List<JobEntity>): Map<String, String?> {
        val canonical = HashMap<String, String>()
        val out = LinkedHashMap<String, String?>()
        for (j in activeOrdered) {
            val c = canonical[j.dedupKey]
            if (c != null) out[j.id] = c else { canonical[j.dedupKey] = j.id; out[j.id] = null }
        }
        return out
    }

    fun csv(s: String): List<String> = if (s.isBlank()) emptyList() else s.split(",")
}
