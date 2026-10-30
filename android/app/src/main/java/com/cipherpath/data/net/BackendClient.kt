package com.cipherpath.data.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Instant
import java.time.OffsetDateTime

@Serializable
data class BackendJob(
    val id: Int,
    val source: String,
    val source_job_id: String,
    val company_name: String,
    val title: String,
    val location: String = "",
    val country: String? = null,
    val work_mode: String = "unknown",
    val employment_type: String? = null,
    val seniority: String = "unknown",
    val role_category: String? = null,
    val role_group: String? = null,
    val department: String? = null,
    val description_text: String = "",
    val skills_csv: String = "",
    val min_years: Int? = null,
    val salary_min: Double? = null,
    val salary_max: Double? = null,
    val salary_currency: String? = null,
    val apply_url: String,
    val posted_at: String? = null,
    val first_seen_at: String,
    val updated_at: String,
    val is_active: Boolean = true,
    val duplicate_of: Int? = null,
)

@Serializable
data class BackendPage(val items: List<BackendJob>, val page: Int, val page_size: Int, val total: Int, val server_time: String)

/**
 * Optional self-hosted FastAPI backend. The API key is read from the Keystore-encrypted
 * SecretStore and sent only to the configured https host. Resumes are never sent here.
 */
class BackendClient(private val http: SafeHttp, private val baseUrl: () -> String?, private val apiKey: () -> String?) {
    private val json = Json { ignoreUnknownKeys = true }

    fun configured(): Boolean = !baseUrl().isNullOrBlank() && !apiKey().isNullOrBlank()

    private fun headers() = mapOf("Authorization" to "Bearer ${apiKey() ?: throw UnsafeRequest("no API key")}")

    private fun base(): okhttp3.HttpUrl {
        val u = baseUrl()?.trim()?.trimEnd('/')?.toHttpUrlOrNull() ?: throw UnsafeRequest("backend URL not set")
        if (u.scheme != "https") throw UnsafeRequest("backend must use https")
        return u
    }

    fun health(): Boolean = runCatching {
        http.getJson(base().newBuilder().addPathSegment("health").build()); true
    }.getOrDefault(false)

    /** Pulls every job changed since [sinceIso] (including expirations), page by page. */
    fun changedSince(sinceIso: String?): Pair<List<BackendJob>, String?> {
        val all = mutableListOf<BackendJob>()
        var page = 1
        var serverTime: String? = null
        while (page <= 200) {
            val b = base().newBuilder().addPathSegments("v1/jobs")
                .addQueryParameter("include_inactive", "true")
                .addQueryParameter("include_duplicates", "true")
                .addQueryParameter("page", page.toString())
                .addQueryParameter("page_size", "200")
            sinceIso?.let { b.addQueryParameter("updated_since", it) }
            val p = json.decodeFromJsonElement(BackendPage.serializer(), http.getJson(b.build(), headers()))
            serverTime = serverTime ?: p.server_time
            all += p.items
            if (page * p.page_size >= p.total || p.items.isEmpty()) break
            page++
        }
        return all to serverTime
    }

    fun triggerSync() {
        http.postJson(base().newBuilder().addPathSegments("v1/sync").build(), "{}", headers())
    }

    companion object {
        fun parseTime(s: String?): Long? = s?.let {
            runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
                ?: runCatching { Instant.parse(it + if (it.endsWith("Z")) "" else "Z").toEpochMilli() }.getOrNull()
        }
    }
}
