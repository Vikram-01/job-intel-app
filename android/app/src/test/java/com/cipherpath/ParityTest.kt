package com.cipherpath

import com.cipherpath.analysis.AtsChecker
import com.cipherpath.analysis.FileFacts
import com.cipherpath.analysis.Matcher
import com.cipherpath.analysis.ResumeAnalysis
import com.cipherpath.analysis.Taxonomy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.math.abs

/** Shared-file helpers: unit tests run with the module dir (android/app) as working dir. */
object Shared {
    val dir: File = listOf(File("../../shared"), File("../shared"), File("shared")).first { File(it, "taxonomy.json").exists() }
    val tx: Taxonomy by lazy { Taxonomy.parse(File(dir, "taxonomy.json").readText()) }
    val parity: JsonObject by lazy { Json.parseToJsonElement(File(dir, "fixtures/parity.json").readText()).jsonObject }
}

class ParityTest {
    private val tx = Shared.tx
    private val p = Shared.parity
    private val today = LocalDate.parse(p["today"]!!.jsonPrimitive.content)

    private fun strs(a: JsonArray) = a.map { it.jsonPrimitive.content }

    @Test
    fun resumeAnalysisMatchesPython() {
        for (c in p["cases"]!!.jsonArray.map { it.jsonObject }) {
            val doc = ResumeAnalysis.analyse(c["resume"]!!.jsonPrimitive.content, FileFacts(), tx, today)
            assertEquals("skills", strs(c["resume_skills"]!!.jsonArray), doc.skills)
            assertEquals("years", c["years"]!!.jsonPrimitive.double, doc.yearsExperience, 0.001)
            assertEquals("titles", strs(c["titles"]!!.jsonArray), doc.titles)
            assertEquals("bullets", c["bullets"]!!.jsonPrimitive.int, doc.bullets)
            assertEquals("quantified", c["quantified"]!!.jsonPrimitive.int, doc.quantifiedBullets)
        }
    }

    @Test
    fun jdSplitAndScoresMatchPython() {
        for (c in p["cases"]!!.jsonArray.map { it.jsonObject }) {
            val jd = c["jd"]!!.jsonPrimitive.content
            val title = c["title"]!!.jsonPrimitive.content
            val (req, pref) = tx.splitRequiredPreferred(jd)
            assertEquals(strs(c["req"]!!.jsonArray), req)
            assertEquals(strs(c["pref"]!!.jsonArray), pref)
            assertEquals(c["min_years"]!!.jsonPrimitive.intOrNull, tx.minYearsRequired(jd))
            val doc = ResumeAnalysis.analyse(c["resume"]!!.jsonPrimitive.content, FileFacts(), tx, today)
            val m = Matcher.match(doc, title, jd, null, tx)
            // Python rounds half-to-even, Kotlin half-up: allow ±1.
            assertTrue("match ${m.score} vs ${c["match_score"]}", abs(m.score - c["match_score"]!!.jsonPrimitive.int) <= 1)
            val a = AtsChecker.check(doc, title, jd, null, tx)
            assertTrue("ats ${a.score} vs ${c["ats_score"]}", abs(a.score - c["ats_score"]!!.jsonPrimitive.int) <= 1)
            for ((k, v) in c["ats_breakdown"]!!.jsonObject) {
                assertTrue("ats.$k", abs((a.breakdown[k] ?: -99) - v.jsonPrimitive.int) <= 1)
            }
            // Score must be reproducible from the published breakdown.
            val appl = m.components.filter { it.score != null }
            val recomputed = Math.round(100 * appl.sumOf { it.weight * it.score!! } / appl.sumOf { it.weight }).toInt()
            assertEquals(recomputed, m.score)
        }
    }

    @Test
    fun classificationMatchesPython() {
        for (t in p["titles"]!!.jsonArray.map { it.jsonObject }) {
            val title = t["title"]!!.jsonPrimitive.content
            val (role, group) = tx.classifyRole(title)
            assertEquals(title, t["role"]!!.jsonPrimitive.contentOrNull, role)
            assertEquals(title, t["group"]!!.jsonPrimitive.contentOrNull, group)
            assertEquals(title, t["seniority"]!!.jsonPrimitive.content, tx.classifySeniority(title))
        }
        for (s in p["salaries"]!!.jsonArray.map { it.jsonObject }) {
            val r = s["result"]!!.jsonArray
            val (a, b, cur) = tx.parseSalary(s["text"]!!.jsonPrimitive.content)
            assertEquals(r[0].jsonPrimitive.doubleOrNull, a)
            assertEquals(r[1].jsonPrimitive.doubleOrNull, b)
            assertEquals(r[2].jsonPrimitive.contentOrNull, cur)
        }
        for (l in p["locations"]!!.jsonArray.map { it.jsonObject }) {
            val loc = l["location"]!!.jsonPrimitive.content
            val desc = l["desc"]!!.jsonPrimitive.content
            assertEquals(l["country"]!!.jsonPrimitive.contentOrNull, tx.classifyCountry(loc))
            assertEquals(l["mode"]!!.jsonPrimitive.content, tx.classifyWorkMode(loc, desc))
        }
    }

    @Test
    fun unrelatedExperienceDoesNotInflateMatch() {
        val chef = "Jane\njane@x.com\nExperience\nChef, Bistro — 2015 – 2020\n• Cooked 200 meals a day\nSkills\nCooking"
        val jd = p["cases"]!!.jsonArray[0].jsonObject["jd"]!!.jsonPrimitive.content
        val m = Matcher.match(ResumeAnalysis.analyse(chef, FileFacts(), tx, today), "Senior Application Security Engineer", jd, null, tx)
        assertTrue("score ${m.score}", m.score < 25)
    }
}
