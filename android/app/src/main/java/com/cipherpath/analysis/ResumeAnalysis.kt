package com.cipherpath.analysis

import kotlinx.serialization.Serializable
import java.time.LocalDate

/** Structural facts about the source file, filled by ResumeExtractor. */
@Serializable
data class FileFacts(
    val fileType: String = "text",
    val pages: Int? = null,
    val tables: Int = 0,
    val images: Int = 0,
    val textBoxes: Int = 0,
)

@Serializable
data class ResumeDoc(
    val text: String,
    val facts: FileFacts = FileFacts(),
    val sections: Map<String, String> = emptyMap(),
    val skills: List<String> = emptyList(),
    val yearsExperience: Double = 0.0,
    val titles: List<String> = emptyList(),
    val hasEmail: Boolean = false,
    val hasPhone: Boolean = false,
    val hasLinkedin: Boolean = false,
    val bullets: Int = 0,
    val quantifiedBullets: Int = 0,
)

object ResumeAnalysis {

    private val SECTION_ALIASES = linkedMapOf(
        "summary" to listOf("summary", "profile", "professional summary", "about me", "objective"),
        "experience" to listOf("experience", "work experience", "professional experience", "employment", "work history"),
        "education" to listOf("education", "academic", "qualifications"),
        "skills" to listOf("skills", "technical skills", "core competencies", "technologies", "tools"),
        "certifications" to listOf("certifications", "certificates", "licenses"),
        "projects" to listOf("projects", "personal projects", "key projects"),
        "achievements" to listOf("achievements", "awards", "publications", "cves", "talks"),
    )

    fun splitSections(text: String): Map<String, String> {
        val out = LinkedHashMap<String, MutableList<String>>()
        var current = "header"
        for (line in text.lines()) {
            val s = line.trim().trimEnd(':').lowercase()
            var hit: String? = null
            if (s.isNotEmpty() && s.length <= 40) {
                for ((key, aliases) in SECTION_ALIASES) {
                    if (aliases.any { s == it || s.startsWith("$it ") }) { hit = key; break }
                }
            }
            if (hit != null) {
                current = hit
                out.getOrPut(current) { mutableListOf() }
            } else {
                out.getOrPut(current) { mutableListOf() }.add(line)
            }
        }
        return out.mapValues { it.value.joinToString("\n").trim() }
    }

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val RANGE = Regex(
        "(?<m1>[A-Za-z]{3,9}\\.?\\s+)?(?<y1>(?:19|20)\\d{2})\\s*(?:-|–|—|to)\\s*" +
            "(?:(?<m2>[A-Za-z]{3,9}\\.?\\s+)?(?<y2>(?:19|20)\\d{2})|(?<now>present|current|now|till date|ongoing))",
        RegexOption.IGNORE_CASE,
    )

    private fun month(tok: String?, default: Int): Int {
        if (tok.isNullOrBlank()) return default
        val i = MONTHS.indexOf(tok.trim().lowercase().take(3))
        return if (i >= 0) i + 1 else default
    }

    /** Sum of merged, non-overlapping date ranges (in years, 1 decimal). */
    fun estimateYears(experience: String, today: LocalDate = LocalDate.now()): Double {
        val spans = RANGE.findAll(experience).mapNotNull { m ->
            val y1 = m.groups["y1"]!!.value.toInt()
            val m1 = month(m.groups["m1"]?.value, 1)
            val (y2, m2) = if (m.groups["now"] != null) today.year to today.monthValue
            else m.groups["y2"]!!.value.toInt() to month(m.groups["m2"]?.value, 12)
            val s = y1 * 12 + m1
            val e = y2 * 12 + m2
            if (e >= s && y1 >= 1970) s to e else null
        }.sortedBy { it.first }.toList()
        var total = 0
        var cur: Pair<Int, Int>? = null
        for ((s, e) in spans) {
            val c = cur
            if (c != null && s <= c.second) cur = c.first to maxOf(c.second, e)
            else {
                if (c != null) total += c.second - c.first + 1
                cur = s to e
            }
        }
        cur?.let { total += it.second - it.first + 1 }
        return Math.round(total / 12.0 * 10) / 10.0
    }

    private val BULLET = Regex("^\\s*(?:[•\\-*▪●◦]|\\d+\\.)\\s+")
    private val QUANT = Regex("(\\d+(\\.\\d+)?\\s*%|\\$\\s?\\d|₹\\s?\\d|\\b\\d{2,}\\b|\\b\\d+x\\b|\\b\\d+\\+)")
    private val EMAIL = Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+")
    private val PHONE = Regex("(\\+?\\d[\\d\\s().-]{8,}\\d)")

    fun analyse(text: String, facts: FileFacts, tx: Taxonomy, today: LocalDate = LocalDate.now()): ResumeDoc {
        val clean = TextUtil.clean(text)
        val sections = splitSections(clean)
        val exp = sections["experience"].takeUnless { it.isNullOrBlank() } ?: clean
        val titles = exp.lines().take(200).map { it.trim() }
            .filter { it.length in 4..89 && tx.classifyRole(it).first != null }
            .distinct().take(10)
        var bullets = clean.lines().filter { BULLET.containsMatchIn(it) }
        if (bullets.isEmpty() && !sections["experience"].isNullOrBlank()) {
            bullets = sections["experience"]!!.lines().filter { it.trim().split(Regex("\\s+")).size >= 6 }
        }
        return ResumeDoc(
            text = clean,
            facts = facts,
            sections = sections,
            skills = tx.extractSkills(clean),
            yearsExperience = estimateYears(exp, today),
            titles = titles,
            hasEmail = EMAIL.containsMatchIn(clean),
            hasPhone = PHONE.containsMatchIn(clean),
            hasLinkedin = "linkedin.com/" in clean.lowercase(),
            bullets = bullets.size,
            quantifiedBullets = bullets.count { QUANT.containsMatchIn(it) },
        )
    }
}

object TextUtil {
    private val CONTROL = Regex("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]")
    private val SPACES = Regex("[ \\t]+")
    private val BLANKS = Regex("\\n\\s*\\n\\s*\\n+")
    private val TAG = Regex("<[^>]*>")
    private val BLOCK = Regex("</?(p|div|br|li|ul|ol|h[1-6]|tr|section)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val SCRIPT = Regex("<(script|style)\\b[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val ENTITIES = mapOf("&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&nbsp;" to " ")
    private val NUM_ENTITY = Regex("&#(x?[0-9a-fA-F]+);")

    fun clean(text: String, maxLen: Int = 60_000): String =
        text.replace(CONTROL, "").replace(' ', ' ').replace(SPACES, " ").replace(BLANKS, "\n\n").trim().take(maxLen)

    fun unescape(s: String): String {
        var out = s
        for ((k, v) in ENTITIES) out = out.replace(k, v)
        return out.replace(NUM_ENTITY) { m ->
            val g = m.groupValues[1]
            val cp = if (g.startsWith("x") || g.startsWith("X")) g.drop(1).toIntOrNull(16) else g.toIntOrNull()
            if (cp != null && cp in 32..0x10FFFF) String(Character.toChars(cp)) else ""
        }
    }

    /** Converts (possibly HTML-escaped) HTML to plain text. No markup survives. */
    fun htmlToText(raw: String?, maxLen: Int = 60_000): String {
        if (raw.isNullOrEmpty()) return ""
        var h = if (raw.take(2000).contains("&lt;")) unescape(raw) else raw
        h = h.replace(SCRIPT, " ")
        h = h.replace(Regex("<li\\b[^>]*>", RegexOption.IGNORE_CASE), "\n• ")
        h = h.replace(BLOCK, "\n")
        h = h.replace(TAG, "")
        return clean(unescape(h), maxLen)
    }

    fun short(v: Any?, maxLen: Int = 300): String =
        if (v == null) "" else clean(v.toString(), maxLen).replace('\n', ' ')
}
