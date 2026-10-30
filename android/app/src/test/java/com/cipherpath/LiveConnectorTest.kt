package com.cipherpath

import com.cipherpath.data.net.SafeHttp
import com.cipherpath.data.net.connectorFor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Hits the REAL public ATS APIs with the Kotlin connectors (the code the APK runs).
 * Skipped unless LIVE_CONNECTORS=1 (set in CI). Writes build/connector-report-android.md.
 * Fails only if an entire connector type has zero working boards.
 */
class LiveConnectorTest {
    @Test
    fun allSeedBoards() {
        assumeTrue(System.getenv("LIVE_CONNECTORS") == "1")
        val http = SafeHttp()
        val companies = Json.parseToJsonElement(File(Shared.dir, "companies.json").readText()).jsonObject["companies"]!!.jsonArray
        val ok = HashMap<String, Int>()
        val rows = mutableListOf("| ATS | Company | Slug | Result |", "|---|---|---|---|")
        val atsTypes = HashSet<String>()
        for (c in companies.map { it.jsonObject }) {
            val ats = c["ats"]!!.jsonPrimitive.content
            val slug = c["slug"]!!.jsonPrimitive.content
            val name = c["name"]!!.jsonPrimitive.content
            atsTypes += ats
            val result = try {
                val conn = connectorFor(ats, http)
                val jobs = conn.list(slug, name)
                val rel = jobs.filter { Shared.tx.classifyRole(it.title).first != null }
                var detail = ""
                if (rel.isNotEmpty() && rel[0].description.isEmpty()) {
                    detail = if (conn.details(slug, rel[0]).description.isNotEmpty()) " (details ok)" else " (details EMPTY)"
                }
                require(jobs.all { it.applyUrl.startsWith("https://") }) { "non-https apply URL" }
                ok[ats] = (ok[ats] ?: 0) + 1
                "OK: ${jobs.size} postings, ${rel.size} in scope$detail"
            } catch (e: Exception) {
                "FAIL: ${e.javaClass.simpleName}: ${e.message?.take(120)}"
            }
            println("$ats\t$slug\t$result")
            rows += "| $ats | $name | `$slug` | $result |"
        }
        File("build").mkdirs()
        File("build/connector-report-android.md").writeText(rows.joinToString("\n") + "\n")
        val broken = atsTypes.filter { (ok[it] ?: 0) == 0 }
        assertTrue("Broken connectors: $broken", broken.isEmpty())
    }
}
