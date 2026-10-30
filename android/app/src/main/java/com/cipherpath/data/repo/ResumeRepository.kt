package com.cipherpath.data.repo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.cipherpath.analysis.ResumeAnalysis
import com.cipherpath.analysis.ResumeDoc
import com.cipherpath.analysis.ResumeExtractor
import com.cipherpath.analysis.ResumeRejected
import com.cipherpath.analysis.Taxonomy
import com.cipherpath.data.db.AppDatabase
import com.cipherpath.data.db.ResumeEntity
import com.cipherpath.security.SecureFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID

class ResumeRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val tx: Taxonomy,
    private val files: SecureFileStore,
    private val matches: MatchEngine,
) {
    private val extractor = ResumeExtractor(context)
    private val json = Json { ignoreUnknownKeys = true }

    fun observeAll() = db.resumes().observeAll()
    suspend fun all() = db.resumes().all()

    fun doc(r: ResumeEntity): ResumeDoc = json.decodeFromString(ResumeDoc.serializer(), r.analysisJson)

    /**
     * Imports a resume picked via the Storage Access Framework. The original bytes are kept only
     * in Keystore-encrypted app-private storage; parsed data lives in the SQLCipher DB.
     * Nothing is uploaded anywhere.
     */
    suspend fun import(uri: Uri): Result<ResumeEntity> = withContext(Dispatchers.IO) {
        runCatching {
            if (db.resumes().count() >= 20) throw ResumeRejected("You can store up to 20 resumes.")
            val cr = context.contentResolver
            var name = "resume"
            var size = -1L
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)?.take(120) ?: name
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
            if (size > ResumeExtractor.MAX_BYTES) throw ResumeRejected("File is larger than 5 MB.")
            val bytes = cr.openInputStream(uri)?.use { ResumeExtractor.readCapped(it) }
                ?: throw ResumeRejected("Could not open the file.")
            val (text, facts) = extractor.extract(bytes)
            val doc = ResumeAnalysis.analyse(text, facts, tx)
            if (doc.text.length < 50) throw ResumeRejected("Almost no text could be read. Is this a scanned image? Export a text-based PDF or DOCX.")
            val id = UUID.randomUUID().toString()
            files.write(id, bytes)
            val first = db.resumes().count() == 0
            val entity = ResumeEntity(
                id = id,
                name = name.substringBeforeLast('.').ifBlank { "Resume" }.take(80),
                fileName = name,
                fileType = facts.fileType,
                sizeBytes = bytes.size.toLong(),
                importedAt = System.currentTimeMillis(),
                isDefault = first,
                skillsCsv = doc.skills.joinToString(","),
                yearsExperience = doc.yearsExperience,
                analysisJson = json.encodeToString(ResumeDoc.serializer(), doc),
            )
            db.resumes().insert(entity)
            matches.recomputeAll()
            entity
        }
    }

    suspend fun rename(r: ResumeEntity, name: String) =
        db.resumes().update(r.copy(name = name.trim().take(80).ifBlank { r.name }))

    suspend fun setDefault(id: String) = db.resumes().setDefault(id)

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        files.delete(id)
        db.resumes().delete(id)
        matches.recomputeAll()
    }
}
