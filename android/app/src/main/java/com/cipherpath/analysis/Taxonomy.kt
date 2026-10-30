package com.cipherpath.analysis

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SkillDef(val name: String, val cat: String, val aliases: List<String> = emptyList())

@Serializable
data class RoleDef(val id: String, val label: String, val group: String, val keywords: List<String>)

@Serializable
data class SeniorityDef(val level: String, val keywords: List<String>)

@Serializable
data class TaxonomyData(
    val version: Int,
    val skills: List<SkillDef>,
    val roles: List<RoleDef>,
    val certifications: Map<String, List<String>>,
    val learning: Map<String, String>,
    val seniority: List<SeniorityDef>,
)

/**
 * Deterministic text classification shared with the backend (backend/app/taxonomy.py),
 * both driven by shared/taxonomy.json. Pure Kotlin: no Android dependencies.
 */
class Taxonomy(val data: TaxonomyData) {

    private class CompiledSkill(val name: String, val cat: String, val patterns: List<Regex>)

    private val compiled: List<CompiledSkill> = data.skills.map { s ->
        val pats = mutableListOf<Regex>()
        for (t in listOf(s.name) + s.aliases) {
            if (t.length <= 2 && t == s.name) {
                when (t) {
                    "C" -> pats += Regex("(?<![a-z0-9])c(?=\\s*(?:,|/|and|\\bor\\b)\\s*(?:c\\+\\+|python|rust|go|java))")
                    "Go" -> pats += Regex("(?<![a-z0-9])go(?=\\s*(?:,|/)\\s*(?:python|rust|java|c\\+\\+|kotlin))|(?<=python, )go\\b|(?<=python/)go\\b")
                }
                continue
            }
            pats += termPattern(t)
        }
        CompiledSkill(s.name, s.cat, pats)
    }

    val skillCategory: Map<String, String> = data.skills.associate { it.name to it.cat }
    val roleById: Map<String, RoleDef> = data.roles.associateBy { it.id }

    fun extractSkills(text: String): List<String> {
        val low = text.lowercase()
        return compiled.filter { sk -> sk.patterns.any { it.containsMatchIn(low) } }.map { it.name }
    }

    fun splitRequiredPreferred(text: String): Pair<List<String>, List<String>> {
        val req = LinkedHashSet<String>()
        val pref = LinkedHashSet<String>()
        var mode = "req"
        for (line in text.lines()) {
            val s = line.trim()
            if (s.isEmpty()) continue
            val heading = s.length < 80 && !s.startsWith("•")
            if (heading && PREF_HDR.containsMatchIn(s)) mode = "pref"
            else if (heading && REQ_HDR.containsMatchIn(s)) mode = "req"
            val linePref = mode == "pref" || INLINE_PREF.containsMatchIn(s)
            for (sk in extractSkills(s)) if (linePref) pref += sk else req += sk
        }
        return req.toList() to pref.filter { it !in req }
    }

    /** Returns (roleId, group) or (null, null) if the title is outside the target domains. */
    fun classifyRole(title: String): Pair<String?, String?> {
        val t = title.lowercase()
        for (r in data.roles) if (r.keywords.any { t.contains(it) }) return r.id to r.group
        return null to null
    }

    fun classifySeniority(title: String): String {
        val t = " ${title.lowercase()} "
        for (lvl in SENIORITY_ORDER) {
            val def = data.seniority.firstOrNull { it.level == lvl } ?: continue
            if (def.keywords.any { t.contains(it) }) return lvl
        }
        return "unknown"
    }

    fun classifyWorkMode(location: String, description: String = "", hint: String? = null): String {
        hint?.lowercase()?.let { h ->
            if ("remote" in h) return "remote"
            if ("hybrid" in h) return "hybrid"
            if ("site" in h || "office" in h) return "onsite"
        }
        if (HYBRID.containsMatchIn(location)) return "hybrid"
        if (REMOTE.containsMatchIn(location)) return "remote"
        if (ONSITE.containsMatchIn(location)) return "onsite"
        val head = description.take(1500)
        if (HYBRID.containsMatchIn(head)) return "hybrid"
        if (FULLY_REMOTE.containsMatchIn(head)) return "remote"
        return if (location.isNotBlank()) "onsite" else "unknown"
    }

    fun classifyCountry(location: String): String? = when {
        INDIA.containsMatchIn(location) -> "IN"
        US.containsMatchIn(location) -> "US"
        UK.containsMatchIn(location) -> "GB"
        else -> null
    }

    fun minYearsRequired(text: String): Int? {
        val src = text.take(20000)
        val vals = YEARS.findAll(src).mapNotNull { m ->
            val ctx = src.substring(m.range.last + 1, minOf(src.length, m.range.last + 61)).lowercase()
            val v = m.groupValues[1].toInt()
            if (("experience" in ctx || "exp" in ctx || ctx.take(4).contains("of ")) && v in 1..25) v else null
        }.toList()
        return vals.minOrNull()
    }

    /** Only explicit ranges in the posting; never estimated. */
    fun parseSalary(text: String): Triple<Double?, Double?, String?> {
        val m = SALARY.find(text) ?: return Triple(null, null, null)
        val cur = CURRENCY[m.groups["cur"]!!.value] ?: return Triple(null, null, null)
        val bk = m.groups["bk"]?.value
        val lo = num(m.groups["a"]!!.value, m.groups["ak"]?.value ?: bk)
        val hi = num(m.groups["b"]!!.value, bk)
        if (lo <= 0 || hi < lo || hi > 50_000_000) return Triple(null, null, null)
        return Triple(lo, hi, cur)
    }

    private fun num(v: String, unit: String?): Double {
        var n = v.replace(",", "").toDouble()
        when (unit) {
            "k", "K" -> n *= 1_000
            "L", "lakh", "LPA" -> n *= 100_000
        }
        return n
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(jsonText: String): Taxonomy = Taxonomy(json.decodeFromString(TaxonomyData.serializer(), jsonText))

        private fun termPattern(term: String): Regex =
            Regex("(?<![a-z0-9])" + Regex.escape(term.lowercase()) + "(?![a-z0-9+#])")

        private val SENIORITY_ORDER = listOf("intern", "manager", "principal", "architect", "lead", "senior", "mid", "junior", "entry")
        private val REQ_HDR = Regex("(requirements|what you('|’)ll need|you have|you bring|qualifications|must have|minimum|required|what we('|’)re looking for|about you)", RegexOption.IGNORE_CASE)
        private val PREF_HDR = Regex("(nice to have|preferred|bonus|plus|good to have|ideally|desired)", RegexOption.IGNORE_CASE)
        private val INLINE_PREF = Regex("\\b(a plus|is a bonus|nice to have|preferred)\\b", RegexOption.IGNORE_CASE)
        private val REMOTE = Regex("\\b(remote|work from home|wfh|anywhere)\\b", RegexOption.IGNORE_CASE)
        private val HYBRID = Regex("\\bhybrid\\b", RegexOption.IGNORE_CASE)
        private val ONSITE = Regex("\\b(on-?site|in[- ]office)\\b", RegexOption.IGNORE_CASE)
        private val FULLY_REMOTE = Regex("\\b(fully remote|100% remote|remote-first|remote position)\\b", RegexOption.IGNORE_CASE)
        private val INDIA = Regex("\\b(india|bengaluru|bangalore|hyderabad|pune|mumbai|delhi|new delhi|gurgaon|gurugram|noida|chennai|kolkata|ahmedabad|kochi|jaipur)\\b", RegexOption.IGNORE_CASE)
        private val US = Regex("\\b(united states|usa|u\\.s\\.|new york|san francisco|seattle|austin|boston|chicago|remote - us|remote, us|, ca\\b|, ny\\b|, wa\\b|, tx\\b)\\b", RegexOption.IGNORE_CASE)
        private val UK = Regex("\\b(united kingdom|london|uk)\\b", RegexOption.IGNORE_CASE)
        private val YEARS = Regex("(\\d{1,2})\\s*\\+?\\s*(?:-|to|–)?\\s*(?:\\d{1,2})?\\s*\\+?\\s*years?", RegexOption.IGNORE_CASE)
        private val SALARY = Regex(
            "(?<cur>\\$|USD|₹|INR|£|GBP|€|EUR)\\s?(?<a>\\d[\\d,]*(?:\\.\\d+)?)\\s?(?<ak>[kK]|L|lakh|LPA)?\\s*(?:-|–|to)\\s*" +
                "(?:\\$|USD|₹|INR|£|GBP|€|EUR)?\\s?(?<b>\\d[\\d,]*(?:\\.\\d+)?)\\s?(?<bk>[kK]|L|lakh|LPA)?"
        )
        private val CURRENCY = mapOf("$" to "USD", "USD" to "USD", "₹" to "INR", "INR" to "INR", "£" to "GBP", "GBP" to "GBP", "€" to "EUR", "EUR" to "EUR")
    }
}
