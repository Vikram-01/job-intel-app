package com.cipherpath.analysis

import kotlinx.serialization.Serializable

@Serializable
data class AtsFinding(val check: String, val severity: String, val message: String, val fix: String = "")

@Serializable
data class AtsResult(
    val score: Int,
    val breakdown: Map<String, Int>,
    val findings: List<AtsFinding>,
    val matchedKeywords: List<String>,
    val missingKeywords: List<String>,
    val disclaimer: String = AtsChecker.DISCLAIMER,
)

/** Heuristic ATS compatibility estimate; same logic as backend/app/ats.py. */
object AtsChecker {
    const val DISCLAIMER =
        "Estimated compatibility from CipherPath's own transparent heuristics. Employer ATS products " +
            "(Workday, Greenhouse, Lever, iCIMS, Taleo, etc.) use their own parsing and ranking, and many " +
            "rank by recruiter search rather than a score. Treat this as guidance, not a prediction."

    val WEIGHTS = linkedMapOf(
        "keywords" to 0.35, "sections" to 0.15, "parsing" to 0.15, "achievements" to 0.10,
        "title" to 0.10, "experience" to 0.10, "readability" to 0.05,
    )

    val LABELS = mapOf(
        "keywords" to "Keyword match", "sections" to "Section completeness", "parsing" to "Parsing safety",
        "achievements" to "Quantified achievements", "title" to "Job-title relevance",
        "experience" to "Experience alignment", "readability" to "Readability",
    )

    fun check(r: ResumeDoc, title: String, description: String, minYearsIn: Int?, tx: Taxonomy): AtsResult {
        val f = mutableListOf<AtsFinding>()
        val b = linkedMapOf<String, Double>()
        val low = r.text.lowercase()

        val (req, pref) = tx.splitRequiredPreferred(description)
        val kws = req + pref.filter { it !in req }
        val have = r.skills.toSet()
        val matched = kws.filter { it in have }
        val missing = kws.filter { it !in have }
        b["keywords"] = if (kws.isNotEmpty()) matched.size.toDouble() / kws.size else 1.0
        when {
            kws.isEmpty() -> f += AtsFinding("keywords", "info", "No recognisable technical keywords in this JD to compare.")
            missing.isNotEmpty() -> f += AtsFinding("keywords", if (b["keywords"]!! < 0.5) "fail" else "warn",
                "${matched.size}/${kws.size} JD keywords found. Missing: ${missing.take(12).joinToString(", ")}",
                "Use the JD's exact wording for skills you genuinely have, ideally in both Skills and Experience.")
            else -> f += AtsFinding("keywords", "pass", "All ${kws.size} JD keywords present.")
        }

        val needed = linkedMapOf("experience" to "Experience", "education" to "Education", "skills" to "Skills")
        val present = needed.keys.filter { !r.sections[it].isNullOrBlank() }
        val contact = r.hasEmail && r.hasPhone
        b["sections"] = (present.size + (if (contact) 1.0 else 0.0) + (if (!r.sections["summary"].isNullOrBlank()) 1.0 else 0.5)) / 5
        for ((k, label) in needed) if (k !in present)
            f += AtsFinding("sections", "warn", "No '$label' heading detected.", "Add a plain heading named '$label' so parsers can locate it.")
        if (!r.hasEmail) f += AtsFinding("sections", "fail", "No email address detected.", "Put your email in the body text, not a header image.")
        if (!r.hasPhone) f += AtsFinding("sections", "warn", "No phone number detected.")
        if (r.sections["summary"].isNullOrBlank())
            f += AtsFinding("sections", "info", "No summary section.", "A 2-3 line summary targeted to the role helps recruiters and keyword search.")

        var p = 1.0
        val words = r.text.split(Regex("\\s+")).count { it.isNotBlank() }
        if (words < 150) {
            p -= 0.6
            f += AtsFinding("parsing", "fail", "Only $words words of extractable text.",
                "The file may be image-based or use embedded graphics for text. Export from a text editor instead.")
        }
        if (r.facts.tables > 0) { p -= 0.2; f += AtsFinding("parsing", "warn", "${r.facts.tables} table(s) found.", "Some ATS read tables out of order; prefer simple lists.") }
        if (r.facts.textBoxes > 0) { p -= 0.25; f += AtsFinding("parsing", "warn", "${r.facts.textBoxes} text box(es) found.", "Text boxes are often skipped by parsers; move content into the body.") }
        if (r.facts.images > 0 && words < 300) { p -= 0.1; f += AtsFinding("parsing", "info", "Images present alongside little text.", "Avoid putting skills or contact info in images/icons.") }
        if (Regex("[\\uf000-\\uf8ff]").containsMatchIn(r.text)) { p -= 0.1; f += AtsFinding("parsing", "warn", "Private-use glyphs detected (icon fonts).", "Replace icon-font symbols with plain text labels.") }
        val pages = r.facts.pages
        if (pages != null && pages > 3) { p -= 0.1; f += AtsFinding("parsing", "info", "$pages pages.", "Two pages is usually enough unless the role is very senior or academic.") }
        b["parsing"] = maxOf(0.0, p)
        if (p >= 1.0) f += AtsFinding("parsing", "pass", "No common parsing risks detected.")

        if (r.bullets > 0) {
            val ratio = r.quantifiedBullets.toDouble() / r.bullets
            b["achievements"] = minOf(1.0, ratio / 0.4)
            val sev = if (ratio >= 0.4) "pass" else if (ratio >= 0.15) "warn" else "fail"
            f += AtsFinding("achievements", sev, "${r.quantifiedBullets}/${r.bullets} bullets include numbers.",
                if (sev == "pass") "" else "Quantify impact: vulns found/fixed, MTTR reduced, % coverage, customers onboarded.")
        } else {
            b["achievements"] = 0.3
            f += AtsFinding("achievements", "warn", "No bullet points detected.", "Use bullet points for responsibilities and results.")
        }

        val jt = Matcher.titleTokens(title)
        val hits = jt.count { it in low }
        b["title"] = if (jt.isNotEmpty()) hits.toDouble() / jt.size else 1.0
        f += if (jt.isNotEmpty() && b["title"]!! < 0.5)
            AtsFinding("title", "warn", "Few words from the job title '$title' appear in your resume.", "If accurate, mirror the target title in your headline or summary.")
        else AtsFinding("title", "pass", "Job title terms appear in your resume.")

        val minYears = minYearsIn ?: tx.minYearsRequired(description)
        if (minYears != null && minYears > 0) {
            val y = r.yearsExperience
            b["experience"] = if (y > 0) minOf(1.0, y / minYears) else 0.0
            f += when {
                y == 0.0 -> AtsFinding("experience", "warn", "Could not read any date ranges from your experience.", "Use a consistent format like 'Jan 2021 – Present' for each role.")
                y < minYears -> AtsFinding("experience", "warn", "~$y yrs detected vs $minYears+ required.")
                else -> AtsFinding("experience", "pass", "~$y yrs meets the $minYears+ asked.")
            }
        } else b["experience"] = 1.0

        val sentences = r.text.split(Regex("[.!?\\n]")).filter { it.trim().split(Regex("\\s+")).size > 3 }
        val avg = if (sentences.isNotEmpty()) sentences.sumOf { it.trim().split(Regex("\\s+")).size }.toDouble() / sentences.size else 0.0
        b["readability"] = if (avg <= 25) 1.0 else maxOf(0.3, 1 - (avg - 25) / 30)
        if (avg > 25) f += AtsFinding("readability", "info", "Average line length is ${avg.toInt()} words.", "Shorter bullets (under ~25 words) scan better.")

        val score = Math.round(100 * WEIGHTS.entries.sumOf { (k, w) -> w * (b[k] ?: 0.0) }).toInt()
        return AtsResult(score, b.mapValues { Math.round(it.value * 100).toInt() }, f, matched, missing)
    }
}
