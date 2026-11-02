package com.nebula.assistant.docs

import java.io.OutputStream

/** CSV, TXT, Markdown and HTML writers. */
object TextWriters {

    fun csv(spec: DocSpec, out: OutputStream) {
        val sheet = spec.sheets.firstOrNull()
        val sb = StringBuilder()
        fun row(cells: List<Any?>) = cells.joinToString(",") { quote(it?.toString() ?: "") }
        if (sheet != null) {
            sb.append(row(sheet.columns.map { it.header })).append("\r\n")
            sheet.rows.forEach { sb.append(row(it)).append("\r\n") }
        } else {
            spec.blocks.filterIsInstance<Block.Table>().firstOrNull()?.let { t ->
                sb.append(row(t.header)).append("\r\n")
                t.rows.forEach { sb.append(row(it)).append("\r\n") }
            }
        }
        // BOM so Excel opens accented characters correctly.
        out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun quote(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun txt(spec: DocSpec, out: OutputStream) {
        val sb = StringBuilder(spec.title).append('\n').append("=".repeat(spec.title.length.coerceAtMost(60))).append("\n\n")
        if (spec.subtitle.isNotBlank()) sb.append(spec.subtitle).append("\n\n")
        for (b in spec.blocks) when (b) {
            is Block.Heading -> sb.append(Xml.plain(b.text).let { if (b.level == 1) it.uppercase() else it }).append("\n\n")
            is Block.Paragraph -> sb.append(Xml.plain(b.text)).append("\n\n")
            is Block.Bullets -> { b.items.forEachIndexed { i, it -> sb.append(if (b.numbered) "${i + 1}. " else "- ").append(Xml.plain(it)).append('\n') }; sb.append('\n') }
            is Block.Table -> {
                val all = listOf(b.header) + b.rows
                val cols = all.maxOf { it.size }
                val w = (0 until cols).map { c -> all.maxOf { it.getOrNull(c)?.length ?: 0 }.coerceAtMost(40) }
                all.forEachIndexed { ri, r ->
                    sb.append((0 until cols).joinToString("  ") { c -> (r.getOrNull(c) ?: "").take(40).padEnd(w[c]) }.trimEnd()).append('\n')
                    if (ri == 0 && b.header.isNotEmpty()) sb.append(w.joinToString("  ") { "-".repeat(it) }).append('\n')
                }
                sb.append('\n')
            }
            is Block.Quote -> sb.append("  \"").append(Xml.plain(b.text)).append("\"\n\n")
            Block.PageBreak -> sb.append("\n\n")
        }
        out.write(sb.toString().trimEnd().plus("\n").toByteArray(Charsets.UTF_8))
    }

    fun markdown(spec: DocSpec, out: OutputStream) {
        val sb = StringBuilder("# ${spec.title}\n\n")
        if (spec.subtitle.isNotBlank()) sb.append("_${spec.subtitle}_\n\n")
        for (b in spec.blocks) when (b) {
            is Block.Heading -> sb.append("#".repeat(b.level.coerceIn(1, 3) + 1)).append(' ').append(b.text).append("\n\n")
            is Block.Paragraph -> sb.append(b.text).append("\n\n")
            is Block.Bullets -> { b.items.forEachIndexed { i, it -> sb.append(if (b.numbered) "${i + 1}. " else "- ").append(it).append('\n') }; sb.append('\n') }
            is Block.Table -> {
                val cols = maxOf(b.header.size, b.rows.maxOfOrNull { it.size } ?: 0)
                fun r(c: List<String>) = "| " + (0 until cols).joinToString(" | ") { (c.getOrNull(it) ?: "").replace("|", "\\|") } + " |\n"
                sb.append(r(b.header)).append("|" + " --- |".repeat(cols) + "\n")
                b.rows.forEach { sb.append(r(it)) }
                sb.append('\n')
            }
            is Block.Quote -> sb.append("> ").append(b.text).append("\n\n")
            Block.PageBreak -> sb.append("---\n\n")
        }
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    fun html(spec: DocSpec, out: OutputStream) {
        val a = Xml.cleanHex(spec.accent)
        fun e(s: String) = Xml.esc(s).replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
        val body = StringBuilder()
        for (b in spec.blocks) when (b) {
            is Block.Heading -> body.append("<h${b.level.coerceIn(1, 3) + 1}>${e(b.text)}</h${b.level.coerceIn(1, 3) + 1}>")
            is Block.Paragraph -> body.append("<p>${e(b.text)}</p>")
            is Block.Bullets -> { val t = if (b.numbered) "ol" else "ul"; body.append("<$t>${b.items.joinToString("") { "<li>${e(it)}</li>" }}</$t>") }
            is Block.Table -> body.append("<table><thead><tr>${b.header.joinToString("") { "<th>${e(it)}</th>" }}</tr></thead><tbody>" +
                b.rows.joinToString("") { r -> "<tr>${r.joinToString("") { "<td>${e(it)}</td>" }}</tr>" } + "</tbody></table>")
            is Block.Quote -> body.append("<blockquote>${e(b.text)}</blockquote>")
            Block.PageBreak -> body.append("<hr>")
        }
        val html = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>${Xml.esc(spec.title)}</title><style>body{font:16px/1.6 system-ui,sans-serif;color:#2b2724;max-width:760px;margin:48px auto;padding:0 20px}" +
            "h1{font-size:2.2em;margin:0}h2{color:#$a}table{border-collapse:collapse;width:100%}th{background:#$a;color:#fff;text-align:left}" +
            "th,td{padding:8px 10px;border-bottom:1px solid #ddd8d2}blockquote{border-left:4px solid #$a;margin:16px 0;padding:4px 16px;color:#5a534d;font-style:italic}" +
            ".sub{color:#6e6760;border-bottom:3px solid #$a;padding-bottom:12px}</style></head><body>" +
            "<h1>${Xml.esc(spec.title)}</h1>" + (if (spec.subtitle.isNotBlank()) "<p class=\"sub\">${Xml.esc(spec.subtitle)}</p>" else "") +
            "$body</body></html>"
        out.write(html.toByteArray(Charsets.UTF_8))
    }
}

/** Single entry point: writes [spec] in its format. */
object DocWriter {
    fun write(spec: DocSpec, out: OutputStream) = when (spec.type) {
        DocType.DOCX -> DocxWriter.write(spec, out)
        DocType.XLSX -> XlsxWriter.write(spec, out)
        DocType.PPTX -> PptxWriter.write(spec, out)
        DocType.PDF -> PdfWriter.write(spec, out)
        DocType.CSV -> TextWriters.csv(spec, out)
        DocType.TXT -> TextWriters.txt(spec, out)
        DocType.MD -> TextWriters.markdown(spec, out)
        DocType.HTML -> TextWriters.html(spec, out)
    }
}
