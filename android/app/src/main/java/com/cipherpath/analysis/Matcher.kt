package com.cipherpath.analysis

import kotlinx.serialization.Serializable

@Serializable
data class MatchComponent(
    val key: String,
    val label: String,
    val weight: Double,
    val score: Double?, // 0..1, null = not applicable (weight redistributed)
    val detail: String,
)

@Serializable
data class MatchResult(
    val score: Int,
    val components: List<MatchComponent>,
    val matchedRequired: List<String>,
    val missingRequired: List<String>,
    val matchedPreferred: List<String>,
    val missingPreferred: List<String>,
    val resumeYears: Double,
    val relevantYears: Double,
    val relevance: Double,
    val requiredYears: Int?,
    val suggestions: List<String>,
)

/**
 * Explainable weighted match, identical in logic to backend/app/matching.py.
 * score = Σ wᵢ·sᵢ / Σ wᵢ over applicable components.
 */
object Matcher {
    val WEIGHTS = linkedMapOf(
        "required_skills" to 0.45,
        "preferred_skills" to 0.15,
        "experience" to 0.15,
        "role_relevance" to 0.15,
        "seniority" to 0.10,
    )
    val SENIORITY_YEARS = mapOf(
        "intern" to 0, "entry" to 0, "junior" to 1, "mid" to 3, "senior" to 5,
        "lead" to 7, "principal" to 10, "architect" to 8, "manager" to 7,
    )
    private val STOP = setOf("and", "or", "the", "of", "senior", "junior", "staff", "lead", "principal", "sr", "i", "ii", "iii",
        "engineer", "engineering", "manager", "-", "&", "remote", "hybrid")

    fun titleTokens(title: String): Set<String> =
        Regex("[a-z]+").findAll(title.lowercase()).map { it.value }.filter { it !in STOP && it.length > 2 }.toSet()

    private fun fmt(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else d.toString()

    fun match(
        resume: ResumeDoc, title: String, description: String, minYearsIn: Int?, tx: Taxonomy,
        precomputed: Pair<List<String>, List<String>>? = null,
    ): MatchResult {
        val (req, pref) = precomputed ?: tx.splitRequiredPreferred(description)
        val minYears = minYearsIn ?: tx.minYearsRequired(description)
        val have = resume.skills.toSet()
        val mReq = req.filter { it in have }
        val xReq = req.filter { it !in have }
        val mPref = pref.filter { it in have }
        val xPref = pref.filter { it !in have }
        val comps = mutableListOf<MatchComponent>()

        comps += MatchComponent("required_skills", "Required skills", WEIGHTS["required_skills"]!!,
            if (req.isNotEmpty()) mReq.size.toDouble() / req.size else null,
            if (req.isNotEmpty()) "${mReq.size} of ${req.size} required skills found" else "JD lists no recognisable required skills")
        comps += MatchComponent("preferred_skills", "Preferred skills", WEIGHTS["preferred_skills"]!!,
            if (pref.isNotEmpty()) mPref.size.toDouble() / pref.size else null,
            if (pref.isNotEmpty()) "${mPref.size} of ${pref.size} preferred skills found" else "JD lists no preferred skills")

        val role = tx.classifyRole(title).first
        val jt = titleTokens(title)
        var best = 0.0
        var bestTitle = ""
        for (rt in resume.titles + listOf((resume.sections["summary"] ?: "").take(200))) {
            val rRole = tx.classifyRole(rt).first
            val overlap = if (jt.isNotEmpty()) (jt intersect titleTokens(rt)).size.toDouble() / jt.size else 0.0
            val s = maxOf(overlap, if (role != null && rRole == role) 0.7 else 0.0)
            if (s > best) { best = s; bestTitle = rt }
        }
        best = Math.round(best * 1000) / 1000.0
        comps += MatchComponent("role_relevance", "Role relevance", WEIGHTS["role_relevance"]!!, best,
            if (bestTitle.isNotEmpty()) "Closest resume title: '${bestTitle.take(60)}'" else "No matching role titles found in resume")

        val reqCov = if (req.isNotEmpty()) mReq.size.toDouble() / req.size else 0.0
        val rf = Math.round(maxOf(best, reqCov) * 1000) / 1000.0
        val yrs = resume.yearsExperience
        val relYrs = Math.round(yrs * rf * 10) / 10.0
        val relNote = if (rf < 1) " (~${fmt(relYrs)} relevant after scaling by relevance ${Math.round(rf * 100)}%)" else ""
        comps += if (minYears != null && minYears > 0) {
            MatchComponent("experience", "Experience", WEIGHTS["experience"]!!,
                if (relYrs > 0) minOf(1.0, relYrs / minYears) else 0.0,
                "Resume shows ~${fmt(yrs)} yrs$relNote; JD asks for $minYears+")
        } else MatchComponent("experience", "Experience", WEIGHTS["experience"]!!, null, "JD states no minimum years")

        val level = tx.classifySeniority(title)
        val need = SENIORITY_YEARS[level]
        comps += if (need != null) {
            var s = if (need == 0) 1.0 else if (relYrs > 0) minOf(1.0, relYrs / need) else 0.0
            if (need > 0 && relYrs > need * 2.5 && level in setOf("junior", "entry")) s = 0.6
            MatchComponent("seniority", "Seniority fit", WEIGHTS["seniority"]!!, s,
                "Role level '$level' typically ~$need+ yrs; resume ~${fmt(relYrs)} relevant")
        } else MatchComponent("seniority", "Seniority fit", WEIGHTS["seniority"]!!, null, "Seniority not stated")

        val applicable = comps.filter { it.score != null }
        val wsum = applicable.sumOf { it.weight }
        val score = if (wsum > 0) Math.round(100 * applicable.sumOf { it.weight * it.score!! } / wsum).toInt() else 0

        val sugg = mutableListOf<String>()
        if (xReq.isNotEmpty()) sugg += "If you have them, add evidence for: ${xReq.take(8).joinToString(", ")} (in a bullet showing where you used each)."
        if (xPref.isNotEmpty()) sugg += "Preferred skills you could highlight: ${xPref.take(6).joinToString(", ")}."
        if (minYears != null && relYrs < minYears) sugg += "Experience appears below the $minYears+ yrs asked; make sure all relevant roles, internships and projects have clear date ranges."
        if (best < 0.5) sugg += "Tailor your summary/headline toward '$title'."
        return MatchResult(score, comps, mReq, xReq, mPref, xPref, yrs, relYrs, rf, minYears, sugg)
    }
}
