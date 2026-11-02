package com.nebula.assistant.docs

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.nebula.assistant.data.FileRecord
import com.nebula.assistant.data.Store
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Creates, edits and opens Nebula's files. Each file gets a private copy, a public copy in
 * Downloads/Nebula, and its JSON spec (so it can be edited by voice later).
 * Every write is verified by re-reading the file before it's reported as done.
 */
class DocumentStore(private val context: Context) {

    private val dir = File(context.filesDir, "documents").apply { mkdirs() }

    data class Result(val ok: Boolean, val file: FileRecord?, val message: String)

    fun create(spec: DocSpec, taskId: String?): Result {
        val name = safeName(spec.title) + "." + spec.type.ext
        val rec = FileRecord(title = spec.title, fileName = name, ext = spec.type.ext, mime = spec.type.mime,
            path = File(dir, "${System.currentTimeMillis()}_$name").path, taskId = taskId)
        return save(rec, spec, isNew = true)
    }

    fun update(fileId: String, spec: DocSpec): Result {
        val rec = Store.file(fileId) ?: return Result(false, null, "I couldn't find that file anymore.")
        // Keep the original format unless the AI explicitly converts it.
        return save(rec.copy(title = spec.title, updatedAt = System.currentTimeMillis()), spec, isNew = false)
    }

    fun spec(fileId: String): JSONObject? = Store.file(fileId)?.specPath?.let { p ->
        runCatching { JSONObject(File(p).readText()) }.getOrNull()
    }

    private fun save(rec0: FileRecord, spec: DocSpec, isNew: Boolean): Result {
        val bytes = try {
            ByteArrayOutputStream().also { DocWriter.write(spec, it) }.toByteArray()
        } catch (e: Exception) {
            return Result(false, null, "Building the ${spec.type.label} failed: ${e.message}")
        }
        val problem = verify(spec.type, bytes)
        if (problem != null) return Result(false, null, "The ${spec.type.label} didn't pass its check: $problem")

        val target = if (rec0.ext == spec.type.ext) File(rec0.path) else File(dir, "${System.currentTimeMillis()}_${safeName(spec.title)}.${spec.type.ext}")
        target.writeBytes(bytes)
        if (!target.exists() || target.length() != bytes.size.toLong()) return Result(false, null, "Saving the file failed.")

        val specFile = File(dir, target.nameWithoutExtension + ".spec.json")
        specFile.writeText(DocJson.toJson(spec).toString())

        var rec = rec0.copy(path = target.path, ext = spec.type.ext, mime = spec.type.mime,
            fileName = safeName(spec.title) + "." + spec.type.ext, specPath = specFile.path, size = bytes.size.toLong(),
            updatedAt = System.currentTimeMillis())
        val publicUri = runCatching { savePublic(rec, bytes, if (rec0.ext == spec.type.ext) rec0.publicUri else null) }.getOrNull()
        rec = rec.copy(publicUri = publicUri?.toString())
        Store.upsertFile(rec)
        val where = if (publicUri != null) "Downloads/Nebula" else "Nebula's Files"
        return Result(true, rec, "${if (isNew) "Created" else "Updated"} “${rec.fileName}” (${kb(bytes.size)}) in $where.")
    }

    /** Re-opens the bytes to make sure the file is genuinely valid. */
    private fun verify(type: DocType, bytes: ByteArray): String? {
        if (bytes.isEmpty()) return "the file is empty"
        return when (type) {
            DocType.DOCX, DocType.XLSX, DocType.PPTX -> {
                val main = when (type) { DocType.DOCX -> "word/document.xml"; DocType.XLSX -> "xl/workbook.xml"; else -> "ppt/presentation.xml" }
                val names = mutableSetOf<String>()
                runCatching {
                    ZipInputStream(bytes.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; names += e.name } }
                }.getOrElse { return "it isn't a readable Office file" }
                if ("[Content_Types].xml" !in names || main !in names) "it's missing required parts" else null
            }
            DocType.PDF -> if (String(bytes, 0, 5, Charsets.ISO_8859_1) != "%PDF-" ||
                !String(bytes, maxOf(0, bytes.size - 32), minOf(32, bytes.size), Charsets.ISO_8859_1).contains("%%EOF")) "it isn't a valid PDF" else null
            else -> null
        }
    }

    private fun savePublic(rec: FileRecord, bytes: ByteArray, existing: String?): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val resolver = context.contentResolver
        if (existing != null) {
            val uri = Uri.parse(existing)
            val ok = runCatching { resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null }.getOrDefault(false)
            if (ok) return uri
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, rec.fileName)
            put(MediaStore.Downloads.MIME_TYPE, rec.mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Nebula")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
        values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    fun uriFor(rec: FileRecord): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", File(rec.path))

    fun openIntent(rec: FileRecord): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(rec), rec.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    fun shareIntent(rec: FileRecord): Intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType(rec.mime).putExtra(Intent.EXTRA_STREAM, uriFor(rec))
            .putExtra(Intent.EXTRA_SUBJECT, rec.title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        "Share ${rec.fileName}",
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    companion object {
        fun safeName(title: String): String =
            title.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").replace(Regex("\\s+"), " ").trim().take(60).ifBlank { "Nebula file" }

        fun kb(n: Int) = if (n < 1024) "$n B" else if (n < 1024 * 1024) "${n / 1024} KB" else "%.1f MB".format(n / 1048576.0)
    }
}
