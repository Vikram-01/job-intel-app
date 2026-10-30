package com.cipherpath.data.net

import java.net.URLEncoder

data class DeepLink(val source: String, val label: String, val url: String, val reason: String)

/**
 * Sites without a permitted public job-search API are NOT ingested. We open their own search
 * page with the keywords pre-filled; the user browses under that site's terms. Only the search
 * keywords and location are included — never resume or personal data.
 */
object DeepLinks {
    val SOURCES = linkedMapOf(
        "linkedin" to ("LinkedIn Jobs" to "Job Posting API is partner-only and for posting, not search."),
        "naukri" to ("Naukri" to "No public job-search API; RMS API is for recruiters."),
        "indeed" to ("Indeed" to "Publisher job-search API has been retired; current APIs are employer-side."),
        "wellfound" to ("Wellfound" to "No public job-search API."),
        "instahyre" to ("Instahyre" to "No public job-search API."),
        "foundit" to ("Foundit" to "No public job-search API."),
        "cutshort" to ("Cutshort" to "No public job-search API."),
        "glassdoor" to ("Glassdoor" to "Public API discontinued."),
        "google" to ("Google Jobs" to "No consumer search API (Cloud Talent Solution indexes only your own jobs)."),
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun encPath(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    fun slugify(s: String) = s.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
        .trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString("-")

    fun build(query: String, location: String = "", remote: Boolean = false): List<DeepLink> {
        val q = query.trim().take(120)
        val loc = location.trim().take(80)
        val urls = linkedMapOf(
            "linkedin" to "https://www.linkedin.com/jobs/search/?keywords=${enc(q)}&location=${enc(loc)}" + if (remote) "&f_WT=2" else "",
            "naukri" to "https://www.naukri.com/${slugify(q).ifEmpty { "security" }}-jobs" + if (loc.isNotEmpty()) "-in-${slugify(loc)}" else "",
            "indeed" to "https://in.indeed.com/jobs?q=${enc(q)}&l=${enc(if (remote && loc.isEmpty()) "remote" else loc)}",
            "wellfound" to "https://wellfound.com/role/${slugify(q).ifEmpty { "security-engineer" }}" + if (remote) "/remote" else "",
            "instahyre" to "https://www.instahyre.com/search-jobs/?designation=${enc(q)}&location=${enc(loc)}",
            "foundit" to "https://www.foundit.in/srp/results?query=${enc(q)}&locations=${enc(loc)}",
            "cutshort" to "https://cutshort.io/jobs/${slugify(q).ifEmpty { "security" }}-jobs",
            "glassdoor" to "https://www.glassdoor.co.in/Job/jobs.htm?sc.keyword=${enc(q)}&locKeyword=${enc(loc)}",
            "google" to "https://www.google.com/search?ibp=htl;jobs&q=" + encPath("$q jobs $loc".trim()),
        )
        return urls.map { (k, v) -> DeepLink(k, SOURCES[k]!!.first, v, SOURCES[k]!!.second) }
    }
}
