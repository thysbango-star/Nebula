package com.nebula.assistant.docs

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.Deflater

/**
 * Writes a real PDF (A4) with the standard Helvetica fonts: title, headings, wrapped paragraphs,
 * bullet/numbered lists, tables that break across pages, quotes and "page n / N" footers.
 * Pure Kotlin, so it behaves the same everywhere.
 */
object PdfWriter {

    private const val PW = 595f
    private const val PH = 842f
    private const val M = 56f
    private const val BOTTOM = 70f

    private enum class F(val res: String, val widths: IntArray) { R("F1", HELV), B("F2", HELV_B), I("F3", HELV) }

    private class Page { val ops = StringBuilder() }

    fun write(spec: DocSpec, out: OutputStream) {
        val accent = rgb(Xml.cleanHex(spec.accent))
        val pages = mutableListOf(Page())
        var y = PH - M
        fun page() = pages.last()
        fun newPage() { pages += Page(); y = PH - M }
        fun need(h: Float) { if (y - h < BOTTOM) newPage() }

        fun textAt(x: Float, yy: Float, s: String, f: F, size: Float, color: FloatArray) {
            page().ops.append("BT /${f.res} ${n(size)} Tf ${n(color[0])} ${n(color[1])} ${n(color[2])} rg ${n(x)} ${n(yy)} Td (${enc(s)}) Tj ET\n")
        }
        fun rect(x: Float, yy: Float, w: Float, h: Float, color: FloatArray) {
            page().ops.append("${n(color[0])} ${n(color[1])} ${n(color[2])} rg ${n(x)} ${n(yy)} ${n(w)} ${n(h)} re f\n")
        }
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: FloatArray, w: Float = 0.6f) {
            page().ops.append("${n(color[0])} ${n(color[1])} ${n(color[2])} RG ${n(w)} w ${n(x1)} ${n(y1)} m ${n(x2)} ${n(y2)} l S\n")
        }
        /** Writes wrapped text; returns nothing, advances y. */
        fun para(s: String, f: F, size: Float, color: FloatArray, x: Float = M, width: Float = PW - 2 * M, lead: Float = size * 1.45f, after: Float = size * 0.6f) {
            for (ln in wrap(Xml.plain(s), f, size, width)) {
                need(lead)
                y -= lead
                textAt(x, y + (lead - size) * 0.35f, ln, f, size, color)
            }
            y -= after
        }

        val ink = rgb("2B2724"); val soft = rgb("6E6760"); val faint = rgb("A39C95"); val rule = rgb("DDD8D2")

        // Title block
        para(spec.title, F.B, 26f, rgb("1E1B18"), lead = 32f, after = 4f)
        if (spec.subtitle.isNotBlank()) para(spec.subtitle, F.R, 13f, soft, after = 6f)
        need(16f); y -= 6f
        rect(M, y, 64f, 3f, accent); y -= 18f

        var numberedNo: Int
        for (b in spec.blocks) when (b) {
            is Block.Heading -> {
                val (size, color) = when (b.level) { 1 -> 18f to accent; 2 -> 14f to ink; else -> 12f to ink }
                need(size * 3f) // keep heading with what follows
                y -= size * 0.6f
                para(b.text, F.B, size, color, after = size * 0.35f)
            }
            is Block.Paragraph -> b.text.split("\n").filter { it.isNotBlank() }.forEach { para(it, F.R, 10.5f, ink) }
            is Block.Bullets -> {
                numberedNo = 0
                for (item in b.items) {
                    numberedNo++
                    val marker = if (b.numbered) "$numberedNo." else "•"
                    val lines = wrap(Xml.plain(item), F.R, 10.5f, PW - 2 * M - 18f)
                    lines.forEachIndexed { i, ln ->
                        need(15f); y -= 15f
                        if (i == 0) textAt(M + 2f, y + 3f, marker, if (b.numbered) F.B else F.R, 10.5f, if (b.numbered) accent else accent)
                        textAt(M + 18f, y + 3f, ln, F.R, 10.5f, ink)
                    }
                    y -= 3f
                }
                y -= 5f
            }
            is Block.Quote -> {
                val lines = wrap(Xml.plain(b.text), F.I, 11.5f, PW - 2 * M - 22f)
                need(lines.size * 17f + 10f)
                val top = y
                y -= 6f
                for (ln in lines) { y -= 17f; textAt(M + 16f, y + 4f, ln, F.I, 11.5f, soft) }
                rect(M, y, 3f, top - y, accent)
                y -= 14f
            }
            is Block.Table -> {
                val cols = maxOf(b.header.size, b.rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
                val tw = PW - 2 * M
                val cw = tw / cols
                val size = if (cols > 5) 8.5f else 9.5f
                fun drawRow(cells: List<String>, header: Boolean, shade: Boolean) {
                    val wrapped = (0 until cols).map { wrap(Xml.plain(cells.getOrNull(it).orEmpty()), if (header) F.B else F.R, size, cw - 10f) }
                    val h = wrapped.maxOf { it.size.coerceAtLeast(1) } * (size * 1.35f) + 9f
                    if (y - h < BOTTOM) newPage()
                    if (header) rect(M, y - h, tw, h, accent) else if (shade) rect(M, y - h, tw, h, tint(accent, 0.93f))
                    wrapped.forEachIndexed { c, lines ->
                        lines.forEachIndexed { li, ln ->
                            textAt(M + c * cw + 5f, y - 5f - (li + 1) * size * 1.35f + size * 0.3f, ln, if (header) F.B else F.R, size,
                                if (header) floatArrayOf(1f, 1f, 1f) else ink)
                        }
                    }
                    y -= h
                    if (!header) line(M, y, M + tw, y, rule)
                }
                need(40f)
                if (b.header.isNotEmpty()) drawRow(b.header, true, false)
                b.rows.forEachIndexed { i, r -> drawRow(r, false, i % 2 == 1) }
                y -= 14f
            }
            Block.PageBreak -> newPage()
        }

        // Footers now that the page count is known.
        pages.forEachIndexed { i, p ->
            p.ops.append("BT /F1 8.5 Tf ${n(faint[0])} ${n(faint[1])} ${n(faint[2])} rg ${n(M)} 34 Td (${enc(trunc(spec.title, 70))}) Tj ET\n")
            val label = "${i + 1} / ${pages.size}"
            p.ops.append("BT /F2 8.5 Tf ${n(faint[0])} ${n(faint[1])} ${n(faint[2])} rg ${n(PW - M - width(label, F.B, 8.5f))} 34 Td (${enc(label)}) Tj ET\n")
            p.ops.append("${n(rule[0])} ${n(rule[1])} ${n(rule[2])} RG 0.5 w ${n(M)} 48 m ${n(PW - M)} 48 l S\n")
        }

        writePdf(pages.map { it.ops.toString() }, spec.title, out)
    }

    // ---------------------------------------------------------------- PDF file structure

    private fun writePdf(contents: List<String>, title: String, out: OutputStream) {
        val buf = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        fun w(s: String) = buf.write(s.toByteArray(Charsets.ISO_8859_1))
        fun obj(body: String) { offsets += buf.size(); w("${offsets.size} 0 obj\n$body\nendobj\n") }
        fun objStream(dict: String, data: ByteArray) {
            offsets += buf.size()
            w("${offsets.size} 0 obj\n<< $dict /Length ${data.size} >>\nstream\n"); buf.write(data); w("\nendstream\nendobj\n")
        }
        w("%PDF-1.4\n%âãÏÓ\n")
        val nPages = contents.size
        // 1 catalog, 2 pages, 3-5 fonts, 6 info, then page/content pairs
        val firstPage = 7
        obj("<< /Type /Catalog /Pages 2 0 R >>")
        obj("<< /Type /Pages /Count $nPages /Kids [${(0 until nPages).joinToString(" ") { "${firstPage + it * 2} 0 R" }}] >>")
        obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>")
        obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Oblique /Encoding /WinAnsiEncoding >>")
        obj("<< /Title (${enc(title)}) /Creator (Nebula) /Producer (Nebula) >>")
        contents.forEachIndexed { i, c ->
            val contentId = firstPage + i * 2 + 1
            obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${n(PW)} ${n(PH)}] " +
                "/Resources << /Font << /F1 3 0 R /F2 4 0 R /F3 5 0 R >> >> /Contents $contentId 0 R >>")
            objStream("/Filter /FlateDecode", deflate(c.toByteArray(Charsets.ISO_8859_1)))
        }
        val xref = buf.size()
        w("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { w("%010d 00000 n \n".format(it)) }
        w("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R /Info 6 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.write(buf.toByteArray())
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION); d.setInput(data); d.finish()
        val out = ByteArrayOutputStream(); val tmp = ByteArray(8192)
        while (!d.finished()) { val k = d.deflate(tmp); out.write(tmp, 0, k) }
        d.end(); return out.toByteArray()
    }

    // ---------------------------------------------------------------- text helpers

    private fun n(f: Float): String {
        val r = Math.round(f * 100) / 100.0
        return if (r == Math.floor(r)) r.toLong().toString() else r.toString()
    }

    private fun trunc(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"

    /** Folds characters Helvetica can't show (subscripts, arrows, symbols) into close equivalents. */
    private fun fold(s: String): String {
        var t = s.replace("\u2192", "->").replace("\u2190", "<-").replace("\u2264", "<=").replace("\u2265", ">=")
            .replace("\u2248", "~").replace("\u2260", "!=").replace("\u2713", "v").replace("\u2714", "v")
        // NFKC turns subscripts/superscripts and ligatures into plain characters (CO\u2082 -> CO2).
        val sb = StringBuilder()
        for (ch in t) {
            if (winAnsiDirect(ch)) sb.append(ch)
            else {
                val k = java.text.Normalizer.normalize(ch.toString(), java.text.Normalizer.Form.NFKC)
                val d = java.text.Normalizer.normalize(k, java.text.Normalizer.Form.NFD).filter { it.code < 0x300 || it.code > 0x36F }
                sb.append(if (d.isNotEmpty() && d.all { winAnsiDirect(it) }) d else if (k.all { winAnsiDirect(it) }) k else "?")
            }
        }
        t = sb.toString()
        return t
    }

    private fun winAnsiDirect(c: Char) = c.code in 32..126 || SPECIAL.containsKey(c) || c.code in 0xA1..0xFF

    /** Maps text to WinAnsi bytes and escapes it for a PDF string literal. */
    private fun enc(raw: String): String {
        val s = fold(raw)
        val sb = StringBuilder()
        for (ch in s) {
            val code = winAnsi(ch)
            when {
                code == '('.code || code == ')'.code || code == '\\'.code -> sb.append('\\').append(code.toChar())
                code in 32..126 -> sb.append(code.toChar())
                else -> sb.append("\\").append(Integer.toOctalString(code).padStart(3, '0'))
            }
        }
        return sb.toString()
    }

    private val SPECIAL = mapOf(
        '€' to 0x80, '‚' to 0x82, '„' to 0x84, '…' to 0x85, '†' to 0x86, '‡' to 0x87,
        '‰' to 0x89, '‘' to 0x91, '’' to 0x92, '“' to 0x93, '”' to 0x94, '•' to 0x95,
        '–' to 0x96, '—' to 0x97, '™' to 0x99, ' ' to 0x20,
    )

    private fun winAnsi(c: Char): Int = when {
        c.code in 32..126 -> c.code
        SPECIAL.containsKey(c) -> SPECIAL.getValue(c)
        c.code in 0xA1..0xFF -> c.code
        c == '\t' -> 32
        c == '−' -> '-'.code
        c == '→' -> '>'.code
        c == '✓' || c == '✔' -> 'v'.code
        else -> '?'.code
    }

    private fun charWidth(c: Char, f: F): Int {
        val code = winAnsi(c)
        return if (code in 32..126) f.widths[code - 32] else when (code) { 0x95 -> 350; 0x96 -> 556; 0x97 -> 1000; 0x85 -> 1000; else -> 556 }
    }

    private fun width(s: String, f: F, size: Float) = fold(s).sumOf { charWidth(it, f) } * size / 1000f

    private fun wrap(text: String, f: F, size: Float, maxW: Float): List<String> {
        if (text.isBlank()) return listOf("")
        val out = mutableListOf<String>()
        var line = StringBuilder()
        for (word in text.split(Regex("\\s+"))) {
            if (word.isEmpty()) continue
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (width(candidate, f, size) <= maxW) { line = StringBuilder(candidate); continue }
            if (line.isNotEmpty()) out += line.toString()
            // Break very long words.
            var w = word
            while (width(w, f, size) > maxW && w.length > 1) {
                var k = w.length
                while (k > 1 && width(w.substring(0, k), f, size) > maxW) k--
                out += w.substring(0, k); w = w.substring(k)
            }
            line = StringBuilder(w)
        }
        if (line.isNotEmpty()) out += line.toString()
        return out
    }

    private fun rgb(hex: String) = floatArrayOf(
        hex.substring(0, 2).toInt(16) / 255f, hex.substring(2, 4).toInt(16) / 255f, hex.substring(4, 6).toInt(16) / 255f,
    )
    private fun tint(c: FloatArray, a: Float) = floatArrayOf(c[0] + (1 - c[0]) * a, c[1] + (1 - c[1]) * a, c[2] + (1 - c[2]) * a)

    // Helvetica / Helvetica-Bold advance widths for ASCII 32..126 (Adobe AFM).
    private val HELV = intArrayOf(
        278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278, 556, 556, 556, 556, 556, 556, 556, 556,
        556, 556, 278, 278, 584, 584, 584, 556, 1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556, 333, 556, 556, 500, 556, 556, 278, 556,
        556, 222, 222, 500, 222, 833, 556, 556, 556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584,
    )
    private val HELV_B = intArrayOf(
        278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278, 278, 556, 556, 556, 556, 556, 556, 556, 556,
        556, 556, 333, 333, 584, 584, 584, 611, 975, 722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722, 611, 833, 722, 778,
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 333, 278, 333, 584, 556, 333, 556, 611, 556, 611, 556, 333, 611,
        611, 278, 278, 556, 278, 889, 611, 611, 611, 611, 389, 556, 333, 611, 556, 778, 556, 556, 500, 389, 280, 389, 584,
    )
}
