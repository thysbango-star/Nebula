package com.nebula.assistant.docs

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Tiny helper for writing Office Open XML packages. */
internal class OoxmlPackage {
    private val parts = LinkedHashMap<String, ByteArray>()

    fun put(path: String, xml: String) { parts[path] = xml.toByteArray(Charsets.UTF_8) }

    fun writeTo(out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            // [Content_Types].xml must come first for some readers.
            val order = parts.keys.sortedBy { if (it == "[Content_Types].xml") 0 else 1 }
            for (p in order) {
                zip.putNextEntry(ZipEntry(p))
                zip.write(parts.getValue(p))
                zip.closeEntry()
            }
        }
    }

    fun bytes(): ByteArray = ByteArrayOutputStream().also { writeTo(it) }.toByteArray()

    companion object {
        const val XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

        fun coreProps(title: String, author: String, created: String): String = XML_HEAD +
            "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" " +
            "xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
            "<dc:title>${Xml.esc(title)}</dc:title><dc:creator>${Xml.esc(author)}</dc:creator>" +
            "<cp:lastModifiedBy>Nebula</cp:lastModifiedBy>" +
            "<dcterms:created xsi:type=\"dcterms:W3CDTF\">$created</dcterms:created>" +
            "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">$created</dcterms:modified></cp:coreProperties>"

        fun appProps(app: String = "Nebula"): String = XML_HEAD +
            "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\" " +
            "xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\"><Application>$app</Application></Properties>"

        fun nowIso(): String {
            val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            f.timeZone = java.util.TimeZone.getTimeZone("UTC")
            return f.format(java.util.Date())
        }

        const val REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
        const val R_DOC = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    }
}
