package com.cipherpath.data.net

import com.cipherpath.analysis.TextUtil
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.OffsetDateTime

data class RawJob(
    val source: String,
    val sourceJobId: String,
    val companyName: String,
    val title: String,
    val applyUrl: String,
    val location: String = "",
    val description: String = "",
    val department: String? = null,
    val employmentType: String? = null,
    val workplaceHint: String? = null,
    val postedAt: Long? = null,
    val salaryMin: Double? = null,
    val salaryMax: Double? = null,
    val salaryCurrency: String? = null,
)

interface Connector {
    val ats: String
    fun list(slug: String, company: String): List<RawJob>
    fun details(slug: String, job: RawJob): RawJob = job
}

// --- small JSON helpers -----------------------------------------------------------
internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.arr(): JsonArray = (this as? JsonArray) ?: JsonArray(emptyList())
internal fun JsonObject?.str(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
internal fun JsonObject?.num(key: String): Double? = (this?.get(key) as? JsonPrimitive)?.doubleOrNull
internal fun JsonObject?.bool(key: String): Boolean? = (this?.get(key) as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject?.long(key: String): Long? = (this?.get(key) as? JsonPrimitive)?.longOrNull

internal fun httpsOrNull(u: String?): String? = u?.takeIf { it.startsWith("https://") && it.length <= 1000 }
internal fun isoMillis(s: String?): Long? = s?.let {
    runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
}

class GreenhouseConnector(private val http: SafeHttp) : Connector {
    override val ats = "greenhouse"
    override fun list(slug: String, company: String): List<RawJob> {
        val url = http.url("https://boards-api.greenhouse.io/v1/boards/${SafeHttp.safeSlug(slug)}/jobs?content=true")
        return http.getJson(url).obj()?.get("jobs").arr().mapNotNull { e ->
            val j = e.obj() ?: return@mapNotNull null
            val apply = httpsOrNull(j.str("absolute_url")) ?: return@mapNotNull null
            val id = j.str("id") ?: return@mapNotNull null
            RawJob(
                source = ats, sourceJobId = id, companyName = company,
                title = TextUtil.short(j.str("title")), applyUrl = apply,
                location = TextUtil.short(j["location"].obj().str("name")),
                description = TextUtil.htmlToText(j.str("content")),
                department = j["departments"].arr().firstOrNull().obj().str("name")?.let { TextUtil.short(it) },
                postedAt = isoMillis(j.str("first_published") ?: j.str("updated_at")),
            )
        }
    }
}

class LeverConnector(private val http: SafeHttp) : Connector {
    override val ats = "lever"
    override fun list(slug: String, company: String): List<RawJob> {
        val out = mutableListOf<RawJob>()
        var skip = 0
        while (true) {
            val url = http.url("https://api.lever.co/v0/postings/${SafeHttp.safeSlug(slug)}?mode=json&limit=100&skip=$skip")
            val page = http.getJson(url).arr()
            if (page.isEmpty()) break
            page.forEach { e -> toRaw(e.obj(), company)?.let(out::add) }
            if (page.size < 100 || skip > 5000) break
            skip += 100
        }
        return out
    }

    private fun toRaw(p: JsonObject?, company: String): RawJob? {
        p ?: return null
        val apply = httpsOrNull(p.str("hostedUrl")) ?: httpsOrNull(p.str("applyUrl")) ?: return null
        val id = p.str("id") ?: return null
        val cats = p["categories"].obj()
        val parts = mutableListOf(p.str("descriptionPlain") ?: TextUtil.htmlToText(p.str("description")))
        p["lists"].arr().forEach { l ->
            val lo = l.obj()
            parts += "\n${TextUtil.short(lo.str("text"))}\n${TextUtil.htmlToText(lo.str("content"))}"
        }
        parts += p.str("additionalPlain") ?: ""
        val sal = p["salaryRange"].obj()
        val smin = sal.num("min")
        return RawJob(
            source = ats, sourceJobId = id, companyName = company,
            title = TextUtil.short(p.str("text")), applyUrl = apply,
            location = TextUtil.short(cats.str("location")),
            description = TextUtil.clean(parts.filter { it.isNotBlank() }.joinToString("\n")),
            department = (cats.str("team") ?: cats.str("department"))?.let { TextUtil.short(it) },
            employmentType = cats.str("commitment")?.let { TextUtil.short(it) },
            workplaceHint = p.str("workplaceType"),
            postedAt = p.long("createdAt"),
            salaryMin = smin, salaryMax = sal.num("max"),
            salaryCurrency = if (smin != null) sal.str("currency")?.take(8) else null,
        )
    }
}

class AshbyConnector(private val http: SafeHttp) : Connector {
    override val ats = "ashby"
    override fun list(slug: String, company: String): List<RawJob> {
        val url = http.url("https://api.ashbyhq.com/posting-api/job-board/${SafeHttp.safeSlug(slug)}?includeCompensation=true")
        return http.getJson(url).obj()?.get("jobs").arr().mapNotNull { e ->
            val j = e.obj() ?: return@mapNotNull null
            if (j.bool("isListed") == false) return@mapNotNull null
            val apply = httpsOrNull(j.str("jobUrl")) ?: httpsOrNull(j.str("applyUrl")) ?: return@mapNotNull null
            val id = j.str("id") ?: return@mapNotNull null
            val locs = listOfNotNull(j.str("location")) + j["secondaryLocations"].arr().mapNotNull { it.obj().str("location") }
            var smin: Double? = null
            var smax: Double? = null
            var cur: String? = null
            j["compensation"].obj()?.get("summaryComponents").arr().firstOrNull { it.obj().str("compensationType") == "Salary" }?.obj()?.let {
                smin = it.num("minValue"); smax = it.num("maxValue"); cur = it.str("currencyCode")?.take(8)
            }
            RawJob(
                source = ats, sourceJobId = id, companyName = company,
                title = TextUtil.short(j.str("title")), applyUrl = apply,
                location = TextUtil.short(locs.filter { it.isNotBlank() }.joinToString(" / ")),
                description = TextUtil.clean(j.str("descriptionPlain") ?: TextUtil.htmlToText(j.str("descriptionHtml"))),
                department = (j.str("department") ?: j.str("team"))?.let { TextUtil.short(it) },
                employmentType = j.str("employmentType"),
                workplaceHint = j.str("workplaceType") ?: if (j.bool("isRemote") == true) "Remote" else null,
                postedAt = isoMillis(j.str("publishedAt")),
                salaryMin = smin, salaryMax = smax, salaryCurrency = cur,
            )
        }
    }
}

class SmartRecruitersConnector(private val http: SafeHttp) : Connector {
    override val ats = "smartrecruiters"
    override fun list(slug: String, company: String): List<RawJob> {
        val s = SafeHttp.safeSlug(slug)
        val out = mutableListOf<RawJob>()
        var offset = 0
        while (true) {
            val data = http.getJson(http.url("https://api.smartrecruiters.com/v1/companies/$s/postings?limit=100&offset=$offset")).obj()
            val content = data?.get("content").arr()
            content.forEach { e ->
                val p = e.obj() ?: return@forEach
                val id = p.str("id")?.takeIf { SafeHttp.SLUG.matches(it) } ?: return@forEach
                val loc = p["location"].obj()
                val hint = if (loc.bool("remote") == true) "remote" else if (loc.bool("hybrid") == true) "hybrid" else null
                out += RawJob(
                    source = ats, sourceJobId = id, companyName = company,
                    title = TextUtil.short(p.str("name")),
                    applyUrl = "https://jobs.smartrecruiters.com/$s/$id",
                    location = TextUtil.short(loc.str("fullLocation") ?: listOfNotNull(loc.str("city"), loc.str("country")).joinToString(", ")),
                    department = p["department"].obj().str("label"),
                    employmentType = p["typeOfEmployment"].obj().str("label"),
                    workplaceHint = hint,
                    postedAt = isoMillis(p.str("releasedDate")),
                )
            }
            offset += 100
            val total = data.long("totalFound") ?: 0
            if (content.isEmpty() || offset >= total || offset > 3000) break
        }
        return out
    }

    override fun details(slug: String, job: RawJob): RawJob {
        val d = http.getJson(http.url("https://api.smartrecruiters.com/v1/companies/${SafeHttp.safeSlug(slug)}/postings/${SafeHttp.safeSlug(job.sourceJobId)}")).obj()
        val secs = d?.get("jobAd").obj()?.get("sections").obj()
        val parts = listOf("jobDescription", "qualifications", "additionalInformation").mapNotNull { k ->
            val sec = secs?.get(k).obj()
            sec.str("text")?.let { "${TextUtil.short(sec.str("title"))}\n${TextUtil.htmlToText(it)}" }
        }
        return job.copy(
            description = TextUtil.clean(parts.joinToString("\n\n")),
            applyUrl = httpsOrNull(d.str("postingUrl")) ?: httpsOrNull(d.str("applyUrl")) ?: job.applyUrl,
        )
    }
}

fun connectorFor(ats: String, http: SafeHttp): Connector = when (ats) {
    "greenhouse" -> GreenhouseConnector(http)
    "lever" -> LeverConnector(http)
    "ashby" -> AshbyConnector(http)
    "smartrecruiters" -> SmartRecruitersConnector(http)
    else -> throw IllegalArgumentException("unsupported ATS: $ats")
}
