package com.cipherpath

import com.cipherpath.analysis.DocxReader
import com.cipherpath.analysis.ResumeExtractor
import com.cipherpath.analysis.ResumeRejected
import com.cipherpath.analysis.TextUtil
import com.cipherpath.data.Exporter
import com.cipherpath.data.net.DeepLinks
import com.cipherpath.data.net.RawJob
import com.cipherpath.data.net.SafeHttp
import com.cipherpath.data.net.UnsafeRequest
import com.cipherpath.data.repo.Ingest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SecurityTest {
    private val http = SafeHttp()

    @Test
    fun rejectsUnsafeUrls() {
        listOf(
            "http://boards-api.greenhouse.io/v1/boards/x/jobs",
            "https://evil.example.com/x",
            "https://169.254.169.254/latest/meta-data/",
            "https://u:p@api.lever.co/v0/postings/x",
            "https://api.lever.co:8443/v0/postings/x",
            "https://boards-api.greenhouse.io.evil.com/x",
        ).forEach { u -> assertThrows(u, UnsafeRequest::class.java) { http.validate(u.toHttpUrl()) } }
        http.validate("https://api.ashbyhq.com/posting-api/job-board/openai".toHttpUrl())
    }

    @Test
    fun backendHostIsOnlyAllowedWhenConfigured() {
        val withBackend = SafeHttp { "api.example.org" }
        withBackend.validate("https://api.example.org/v1/jobs".toHttpUrl())
        assertThrows(UnsafeRequest::class.java) { http.validate("https://api.example.org/v1/jobs".toHttpUrl()) }
    }

    @Test
    fun slugValidation() {
        listOf("../admin", "a/b", "a?b", "a#b", "", "a b", "%2e%2e", "x".repeat(101), "a..b").forEach {
            assertThrows(it, UnsafeRequest::class.java) { SafeHttp.safeSlug(it) }
        }
        assertEquals("Acme_Corp-1.io", SafeHttp.safeSlug("Acme_Corp-1.io"))
    }

    @Test
    fun privateAddressesAreNotPublic() {
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0",
            "::1", "fd00::1", "fe80::1", "::ffff:127.0.0.1", "224.0.0.1").forEach {
            assertFalse(it, SafeHttp.isPublic(InetAddress.getByName(it)))
        }
        listOf("104.16.1.1", "8.8.8.8", "2606:4700::1111").forEach { assertTrue(it, SafeHttp.isPublic(InetAddress.getByName(it))) }
    }

    @Test
    fun csvFormulaInjectionNeutralised() {
        assertEquals("\"'=HYPERLINK(\"\"http://x\"\")\"", Exporter.csvCell("=HYPERLINK(\"http://x\")"))
        assertEquals("\"'+1\"", Exporter.csvCell("+1"))
        assertEquals("\"'@SUM(A1)\"", Exporter.csvCell("@SUM(A1)"))
        assertEquals("\"Acme, Inc\"", Exporter.csvCell("Acme, Inc"))
    }

    @Test
    fun htmlIsStripped() {
        val t = TextUtil.htmlToText("&lt;p&gt;Hello &lt;b&gt;world&lt;/b&gt;&lt;/p&gt;&lt;script&gt;alert(1)&lt;/script&gt;&lt;ul&gt;&lt;li&gt;Python&lt;/li&gt;&lt;/ul&gt;")
        assertFalse(t.contains("<")); assertFalse(t.contains("alert")); assertTrue(t.contains("• Python"))
    }

    @Test
    fun deepLinksAreHttpsAndEncoded() {
        val links = DeepLinks.build("appsec <script>&x=1", "Pune", remote = true)
        assertEquals(9, links.size)
        links.forEach { assertTrue(it.url.startsWith("https://")); assertFalse(it.url.contains("<script>")); assertFalse(it.url.contains("&x=1")) }
    }

    @Test
    fun fileTypeSniffing() {
        assertEquals("pdf", ResumeExtractor.sniff("%PDF-1.7".toByteArray()))
        assertThrows(ResumeRejected::class.java) { ResumeExtractor.sniff("MZ\u0090\u0000".toByteArray()) }
        assertThrows(ResumeRejected::class.java) { ResumeExtractor.readCapped(ByteArrayInputStream(ByteArray(100)), 50) }
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return bos.toByteArray()
    }

    @Test
    fun docxDefences() {
        val ct = "[Content_Types].xml" to "<Types/>".toByteArray()
        val body = "word/document.xml" to "<w:document/>".toByteArray()
        assertThrows(ResumeRejected::class.java) { DocxReader.extract(zip(ct, body, "word/vbaProject.bin" to byteArrayOf(1))) }
        assertThrows(ResumeRejected::class.java) { DocxReader.extract(zip(ct, body, "../../evil" to byteArrayOf(1))) }
        assertThrows(ResumeRejected::class.java) { DocxReader.extract(zip(ct, body, "word/bomb.bin" to ByteArray(60 * 1024 * 1024))) }
        assertThrows(ResumeRejected::class.java) { DocxReader.extract(zip(ct, "x.xml" to byteArrayOf(1))) }
    }
}

class IngestTest {
    private val tx = Shared.tx

    @Test
    fun dedupKeyNormalises() {
        val a = Ingest.dedupKey("Acme Inc", "Sr. Security Engineer", "Bengaluru, India")
        assertEquals(a, Ingest.dedupKey("acme inc", "Senior Security Engineer (Remote)", "Bengaluru, India / Pune"))
        assertNotEquals(a, Ingest.dedupKey("Acme Inc", "Security Engineer", "Bengaluru, India"))
    }

    @Test
    fun toEntityEnrichesAndPreservesUserState() {
        val raw = RawJob("greenhouse", "1", "Acme", "Senior Cloud Security Engineer", "https://x.example/1",
            location = "Hyderabad, India",
            description = "Requirements\n• 6+ years of experience\n• AWS and Terraform\nNice to have\n• Kubernetes\nPay: ₹30L - ₹45L")
        val e1 = Ingest.toEntity(raw, tx, 1000, null)
        assertEquals("cloudsec", e1.roleCategory); assertEquals("cyber", e1.roleGroup)
        assertEquals("senior", e1.seniority); assertEquals("IN", e1.country); assertEquals(6, e1.minYears)
        assertEquals(3_000_000.0, e1.salaryMin!!, 0.1); assertEquals("INR", e1.salaryCurrency)
        assertTrue("AWS" in e1.reqSkillsCsv && "Terraform" in e1.reqSkillsCsv); assertEquals("Kubernetes", e1.prefSkillsCsv)
        val e2 = Ingest.toEntity(raw.copy(description = raw.description + "\nPython"), tx, 2000, e1.copy(bookmarked = true, viewedAt = 5))
        assertEquals(1000, e2.firstSeenAt); assertTrue(e2.bookmarked); assertEquals(5L, e2.viewedAt)
        assertNotEquals(e1.contentHash, e2.contentHash)
    }

    @Test
    fun duplicatesKeepEarliest() {
        val base = Ingest.toEntity(RawJob("greenhouse", "1", "Acme", "Security Engineer", "https://a/1", "Pune"), tx, 1, null)
        val dup = Ingest.toEntity(RawJob("ashby", "z", "Acme", "Security Engineer", "https://b/z", "Pune"), tx, 2, null)
        val other = Ingest.toEntity(RawJob("lever", "q", "Acme", "IAM Engineer", "https://c/q", "Pune"), tx, 3, null)
        val d = Ingest.duplicates(listOf(base, dup, other))
        assertNull(d[base.id]); assertEquals(base.id, d[dup.id]); assertNull(d[other.id])
    }

    @Test
    fun targetFiltering() {
        assertTrue(Ingest.isTarget("Forward Deployed Engineer", tx, setOf("fde")))
        assertFalse(Ingest.isTarget("Forward Deployed Engineer", tx, setOf("cyber")))
        assertFalse(Ingest.isTarget("Account Executive", tx, setOf("cyber", "fde")))
    }
}
