package com.cipherpath.analysis

import android.content.Context
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

class ResumeRejected(message: String) : Exception(message)

/**
 * Extracts text from PDF/DOCX resumes with defensive validation:
 * size cap, magic-byte type detection, zip-bomb limits (counted on actual inflated bytes),
 * macro rejection, encrypted-PDF rejection, page cap. Never executes embedded content.
 */
class ResumeExtractor(private val context: Context) {

    companion object {
        const val MAX_BYTES = 5 * 1024 * 1024
        const val MAX_PAGES = 15

        fun sniff(data: ByteArray): String {
            if (data.size >= 5 && String(data, 0, 5, Charsets.ISO_8859_1) == "%PDF-") return "pdf"
            if (data.size >= 4 && data[0] == 0x50.toByte() && data[1] == 0x4B.toByte() && data[2] == 3.toByte() && data[3] == 4.toByte()) return "docx"
            throw ResumeRejected("Only PDF and DOCX files are supported.")
        }

        /** Reads at most [limit]+1 bytes so oversized inputs are detected without loading them fully. */
        fun readCapped(input: InputStream, limit: Int = MAX_BYTES): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > limit) throw ResumeRejected("File is larger than ${limit / (1024 * 1024)} MB.")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    @Volatile private var pdfInit = false

    fun extract(data: ByteArray): Pair<String, FileFacts> {
        if (data.isEmpty()) throw ResumeRejected("The file is empty.")
        if (data.size > MAX_BYTES) throw ResumeRejected("File is larger than 5 MB.")
        return when (sniff(data)) {
            "pdf" -> extractPdf(data)
            else -> DocxReader.extract(data)
        }
    }

    private fun extractPdf(data: ByteArray): Pair<String, FileFacts> {
        if (!pdfInit) { PDFBoxResourceLoader.init(context.applicationContext); pdfInit = true }
        val doc = try {
            PDDocument.load(data)
        } catch (e: InvalidPasswordException) {
            throw ResumeRejected("Password-protected PDFs are not supported.")
        } catch (e: Exception) {
            throw ResumeRejected("This PDF could not be read.")
        }
        doc.use { d ->
            if (d.isEncrypted) throw ResumeRejected("Encrypted PDFs are not supported.")
            val pages = d.numberOfPages
            if (pages > MAX_PAGES) throw ResumeRejected("PDF has more than $MAX_PAGES pages.")
            val text = PDFTextStripper().getText(d)
            var images = 0
            for (page in d.pages) {
                val res = page.resources ?: continue
                for (name in res.xObjectNames) {
                    runCatching { if (res.getXObject(name) is PDImageXObject) images++ }
                }
            }
            return TextUtil.clean(text) to FileFacts("pdf", pages = pages, images = images)
        }
    }
}

object DocxReader {
    private const val MAX_ZIP_ENTRIES = 500
    private const val MAX_ZIP_INFLATED = 50L * 1024 * 1024

    fun extract(data: ByteArray): Pair<String, FileFacts> {
        var entries = 0
        var inflated = 0L
        var body: ByteArray? = null
        val headers = mutableListOf<ByteArray>()
        var sawContentTypes = false
        ZipInputStream(ByteArrayInputStream(data)).use { zin ->
            while (true) {
                val next: java.util.zip.ZipEntry? = try {
                    zin.nextEntry
                } catch (ex: Exception) {
                    throw ResumeRejected("Corrupt DOCX file.")
                }
                val e = next ?: break
                entries++
                if (entries > MAX_ZIP_ENTRIES) throw ResumeRejected("DOCX has too many parts.")
                val name = e.name
                if (name.startsWith("/") || name.contains("..")) throw ResumeRejected("DOCX contains unsafe paths.")
                if (name.lowercase().endsWith("vbaproject.bin")) throw ResumeRejected("Macro-enabled documents are not accepted.")
                if (name == "[Content_Types].xml") sawContentTypes = true
                val wanted = name == "word/document.xml" || Regex("word/header\\d*\\.xml").matches(name)
                val out = if (wanted) ByteArrayOutputStream() else null
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = zin.read(buf)
                    if (n < 0) break
                    inflated += n
                    if (inflated > MAX_ZIP_INFLATED) throw ResumeRejected("DOCX expands too large.")
                    out?.write(buf, 0, n)
                }
                if (out != null) {
                    if (name == "word/document.xml") body = out.toByteArray() else headers += out.toByteArray()
                }
            }
        }
        val doc = body
        if (doc == null || !sawContentTypes) throw ResumeRejected("Not a Word (.docx) document.")
        val head = headers.joinToString("\n") { parse(it).text }
        val main = parse(doc)
        return TextUtil.clean(listOf(head, main.text).filter { it.isNotBlank() }.joinToString("\n")) to
            FileFacts("docx", tables = main.tables, images = main.images, textBoxes = main.textBoxes)
    }

    private class Parsed(val text: String, val tables: Int, val images: Int, val textBoxes: Int)

    private fun parse(xml: ByteArray): Parsed {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) // no DTDs -> no XXE
        parser.setInput(ByteArrayInputStream(xml), "UTF-8")
        val sb = StringBuilder()
        var tables = 0
        var images = 0
        var boxes = 0
        var inText = false
        var inCell = false
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "w:t" -> inText = true
                    "w:tab" -> sb.append('\t')
                    "w:br" -> sb.append('\n')
                    "w:tbl" -> tables++
                    "w:tc" -> inCell = true
                    "w:drawing", "w:pict" -> images++
                    "w:txbxContent" -> boxes++
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "w:t" -> inText = false
                    "w:p" -> sb.append(if (inCell) " " else "\n")
                    "w:tc" -> { inCell = false; sb.append(" | ") }
                    "w:tr" -> sb.append('\n')
                }
                XmlPullParser.TEXT -> if (inText) sb.append(parser.text)
                XmlPullParser.END_DOCUMENT -> return Parsed(sb.toString(), tables, images, boxes)
            }
        }
    }
}
