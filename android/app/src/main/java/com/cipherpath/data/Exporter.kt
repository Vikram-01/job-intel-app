package com.cipherpath.data

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.cipherpath.data.db.ApplicationWithDetails
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Writes application history to user-chosen files (via SAF). Data leaves the app only here, by explicit user action. */
object Exporter {
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
    private val dtFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    private fun d(ms: Long?) = ms?.let { dateFmt.format(Instant.ofEpochMilli(it)) } ?: ""
    private fun dt(ms: Long?) = ms?.let { dtFmt.format(Instant.ofEpochMilli(it)) } ?: ""

    /** RFC 4180 quoting + spreadsheet formula-injection neutralisation. */
    fun csvCell(v: String): String {
        var s = v
        if (s.isNotEmpty() && s[0] in "=+-@\t\r") s = "'$s"
        return "\"" + s.replace("\"", "\"\"") + "\""
    }

    fun writeCsv(apps: List<ApplicationWithDetails>, out: OutputStream) {
        val header = listOf("Company", "Title", "Stage", "Location", "Applied", "Deadline", "Follow-up", "Recruiter",
            "Recruiter email", "Recruiter phone", "Expected salary", "Offer", "Offer currency", "Interviews", "Notes",
            "Apply URL", "History", "Created", "Updated")
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("﻿")
        w.write(header.joinToString(",") { csvCell(it) }); w.write("\r\n")
        for (x in apps) {
            val a = x.app
            val row = listOf(a.company, a.title, a.stage, a.location, d(a.appliedAt), d(a.deadlineAt), d(a.followUpAt),
                a.recruiterName, a.recruiterEmail, a.recruiterPhone, a.expectedSalary, a.offerAmount?.toString() ?: "",
                a.offerCurrency, x.interviews.sortedBy { it.at }.joinToString("; ") { "${dt(it.at)} ${it.round}" },
                a.notes, a.applyUrl ?: "", x.events.sortedBy { it.at }.joinToString("; ") { "${d(it.at)} ${it.detail}" },
                d(a.createdAt), d(a.updatedAt))
            w.write(row.joinToString(",") { csvCell(it) }); w.write("\r\n")
        }
        w.flush()
    }

    fun writePdf(apps: List<ApplicationWithDetails>, out: OutputStream) {
        val doc = PdfDocument()
        val pageW = 595
        val pageH = 842
        val margin = 40f
        val title = Paint().apply { textSize = 18f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true }
        val h = Paint().apply { textSize = 12f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true }
        val body = Paint().apply { textSize = 10f; isAntiAlias = true }
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
        var c = page.canvas
        var y = margin + 10

        fun newPage() {
            doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            c = page.canvas
            y = margin + 10
        }
        fun line(text: String, p: Paint, gap: Float = 4f) {
            val maxW = pageW - 2 * margin
            var rest = text
            while (rest.isNotEmpty()) {
                val n = p.breakText(rest, true, maxW, null).coerceAtLeast(1)
                var cut = n
                if (n < rest.length) { val sp = rest.lastIndexOf(' ', n); if (sp > 0) cut = sp }
                if (y + p.textSize > pageH - margin) newPage()
                c.drawText(rest.substring(0, cut).trim(), margin, y + p.textSize, p)
                y += p.textSize + gap
                rest = rest.substring(cut).trimStart()
            }
        }

        line("CipherPath — Application history", title, 8f)
        line("Exported ${dt(System.currentTimeMillis())} · ${apps.size} applications", body, 12f)
        for (x in apps.sortedByDescending { it.app.updatedAt }) {
            val a = x.app
            if (y > pageH - margin - 80) newPage()
            line("${a.company} — ${a.title}", h)
            line("Stage: ${a.stage}   Applied: ${d(a.appliedAt).ifEmpty { "—" }}   Location: ${a.location.ifEmpty { "—" }}", body)
            if (a.recruiterName.isNotBlank() || a.recruiterEmail.isNotBlank()) line("Recruiter: ${a.recruiterName} ${a.recruiterEmail} ${a.recruiterPhone}".trim(), body)
            if (a.offerAmount != null) line("Offer: ${a.offerCurrency} ${a.offerAmount} ${a.offerDetails}".trim(), body)
            x.interviews.sortedBy { it.at }.forEach { line("Interview: ${dt(it.at)} · ${it.round} ${it.mode}".trim(), body) }
            x.events.sortedBy { it.at }.forEach { line("• ${d(it.at)} ${it.detail}", body, 2f) }
            if (a.notes.isNotBlank()) line("Notes: ${a.notes.replace('\n', ' ').take(600)}", body)
            y += 10
        }
        doc.finishPage(page)
        doc.writeTo(out)
        doc.close()
    }
}
