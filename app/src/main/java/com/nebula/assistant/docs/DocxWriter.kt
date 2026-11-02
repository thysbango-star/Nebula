package com.nebula.assistant.docs

import java.io.OutputStream

/** Writes a real .docx: title page header, styled headings, lists, tables, quotes, page numbers. */
object DocxWriter {

    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    fun write(spec: DocSpec, out: OutputStream) {
        val accent = Xml.cleanHex(spec.accent)
        val pkg = OoxmlPackage()
        val numbered = mutableListOf<Int>() // num ids for numbered lists (each restarts at 1)

        val body = StringBuilder()
        body.append(p("Title", spec.title))
        if (spec.subtitle.isNotBlank()) body.append(p("Subtitle", spec.subtitle))

        for (b in spec.blocks) when (b) {
            is Block.Heading -> body.append(p("Heading${b.level.coerceIn(1, 3)}", b.text))
            is Block.Paragraph -> b.text.split("\n").filter { it.isNotBlank() }.forEach { body.append(p(null, it)) }
            is Block.Bullets -> {
                val numId = if (b.numbered) (3 + numbered.size).also { numbered += it } else 1
                b.items.forEach { body.append(listItem(it, numId)) }
            }
            is Block.Table -> body.append(table(b, accent))
            is Block.Quote -> body.append(p("Quote", b.text))
            Block.PageBreak -> body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
        }

        val document = OoxmlPackage.XML_HEAD +
            "<w:document xmlns:w=\"$W\" xmlns:r=\"${OoxmlPackage.R_DOC}\"><w:body>$body" +
            "<w:sectPr><w:footerReference w:type=\"default\" r:id=\"rId4\"/>" +
            "<w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
            "<w:pgMar w:top=\"1300\" w:right=\"1300\" w:bottom=\"1300\" w:left=\"1300\" w:header=\"708\" w:footer=\"600\" w:gutter=\"0\"/>" +
            "</w:sectPr></w:body></w:document>"

        pkg.put("[Content_Types].xml", OoxmlPackage.XML_HEAD +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
            "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>" +
            "<Override PartName=\"/word/settings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml\"/>" +
            "<Override PartName=\"/word/footer1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml\"/>" +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
            "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>" +
            "</Types>")
        pkg.put("_rels/.rels", OoxmlPackage.XML_HEAD +
            "<Relationships xmlns=\"${OoxmlPackage.REL_NS}\">" +
            "<Relationship Id=\"rId1\" Type=\"${OoxmlPackage.R_DOC}/officeDocument\" Target=\"word/document.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
            "<Relationship Id=\"rId3\" Type=\"${OoxmlPackage.R_DOC}/extended-properties\" Target=\"docProps/app.xml\"/>" +
            "</Relationships>")
        pkg.put("word/_rels/document.xml.rels", OoxmlPackage.XML_HEAD +
            "<Relationships xmlns=\"${OoxmlPackage.REL_NS}\">" +
            "<Relationship Id=\"rId1\" Type=\"${OoxmlPackage.R_DOC}/styles\" Target=\"styles.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"${OoxmlPackage.R_DOC}/numbering\" Target=\"numbering.xml\"/>" +
            "<Relationship Id=\"rId3\" Type=\"${OoxmlPackage.R_DOC}/settings\" Target=\"settings.xml\"/>" +
            "<Relationship Id=\"rId4\" Type=\"${OoxmlPackage.R_DOC}/footer\" Target=\"footer1.xml\"/>" +
            "</Relationships>")
        pkg.put("word/document.xml", document)
        pkg.put("word/styles.xml", styles(accent))
        pkg.put("word/numbering.xml", numbering(numbered))
        pkg.put("word/settings.xml", OoxmlPackage.XML_HEAD +
            "<w:settings xmlns:w=\"$W\"><w:defaultTabStop w:val=\"720\"/><w:compat><w:compatSetting w:name=\"compatibilityMode\" " +
            "w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"15\"/></w:compat></w:settings>")
        pkg.put("word/footer1.xml", OoxmlPackage.XML_HEAD +
            "<w:ftr xmlns:w=\"$W\"><w:p><w:pPr><w:jc w:val=\"right\"/></w:pPr>" +
            "<w:r><w:rPr><w:color w:val=\"9A948E\"/><w:sz w:val=\"18\"/></w:rPr><w:t xml:space=\"preserve\">${Xml.esc(spec.title)}  ·  </w:t></w:r>" +
            "<w:r><w:rPr><w:color w:val=\"9A948E\"/><w:sz w:val=\"18\"/></w:rPr><w:fldChar w:fldCharType=\"begin\"/></w:r>" +
            "<w:r><w:rPr><w:color w:val=\"9A948E\"/><w:sz w:val=\"18\"/></w:rPr><w:instrText xml:space=\"preserve\"> PAGE </w:instrText></w:r>" +
            "<w:r><w:rPr><w:color w:val=\"9A948E\"/><w:sz w:val=\"18\"/></w:rPr><w:fldChar w:fldCharType=\"separate\"/></w:r>" +
            "<w:r><w:rPr><w:color w:val=\"9A948E\"/><w:sz w:val=\"18\"/></w:rPr><w:t>1</w:t></w:r>" +
            "<w:r><w:rPr><w:color w:val=\"9A948E\"/><w:sz w:val=\"18\"/></w:rPr><w:fldChar w:fldCharType=\"end\"/></w:r>" +
            "</w:p></w:ftr>")
        val now = OoxmlPackage.nowIso()
        pkg.put("docProps/core.xml", OoxmlPackage.coreProps(spec.title, spec.author, now))
        pkg.put("docProps/app.xml", OoxmlPackage.appProps())
        pkg.writeTo(out)
    }

    // ---------------------------------------------------------------- runs with **bold** and *italic*

    private fun runs(text: String, extra: String = ""): String {
        val sb = StringBuilder()
        val re = Regex("\\*\\*(.+?)\\*\\*|\\*(.+?)\\*")
        var last = 0
        for (m in re.findAll(text)) {
            if (m.range.first > last) sb.append(run(text.substring(last, m.range.first), extra))
            if (m.groupValues[1].isNotEmpty()) sb.append(run(m.groupValues[1], extra + "<w:b/>"))
            else sb.append(run(m.groupValues[2], extra + "<w:i/>"))
            last = m.range.last + 1
        }
        if (last < text.length) sb.append(run(text.substring(last), extra))
        return sb.toString()
    }

    private fun run(t: String, rPr: String) =
        "<w:r>${if (rPr.isNotEmpty()) "<w:rPr>$rPr</w:rPr>" else ""}<w:t xml:space=\"preserve\">${Xml.esc(t)}</w:t></w:r>"

    private fun p(style: String?, text: String): String =
        "<w:p>${if (style != null) "<w:pPr><w:pStyle w:val=\"$style\"/></w:pPr>" else ""}${runs(text.trim())}</w:p>"

    private fun listItem(text: String, numId: Int) =
        "<w:p><w:pPr><w:pStyle w:val=\"ListParagraph\"/><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"$numId\"/></w:numPr></w:pPr>${runs(text.trim())}</w:p>"

    private fun table(t: Block.Table, accent: String): String {
        val cols = maxOf(t.header.size, t.rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
        val total = 9300
        val w = total / cols
        val sb = StringBuilder()
        sb.append("<w:tbl><w:tblPr><w:tblW w:w=\"$total\" w:type=\"dxa\"/><w:tblBorders>")
        for (side in listOf("top", "bottom", "insideH")) sb.append("<w:$side w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"DDD8D2\"/>")
        sb.append("</w:tblBorders><w:tblCellMar><w:top w:w=\"90\" w:type=\"dxa\"/><w:left w:w=\"120\" w:type=\"dxa\"/>" +
            "<w:bottom w:w=\"90\" w:type=\"dxa\"/><w:right w:w=\"120\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr><w:tblGrid>")
        repeat(cols) { sb.append("<w:gridCol w:w=\"$w\"/>") }
        sb.append("</w:tblGrid>")
        fun row(cells: List<String>, header: Boolean, shade: String?) {
            sb.append("<w:tr>")
            if (header) sb.append("<w:trPr><w:tblHeader/></w:trPr>")
            for (i in 0 until cols) {
                val c = cells.getOrNull(i).orEmpty()
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"$w\" w:type=\"dxa\"/>")
                if (shade != null) sb.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$shade\"/>")
                sb.append("</w:tcPr><w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\"/></w:pPr>")
                sb.append(if (header) run(Xml.plain(c), "<w:b/><w:color w:val=\"FFFFFF\"/>") else runs(c))
                sb.append("</w:p></w:tc>")
            }
            sb.append("</w:tr>")
        }
        if (t.header.isNotEmpty()) row(t.header, true, accent)
        t.rows.forEachIndexed { i, r -> row(r, false, if (i % 2 == 1) Xml.tint(accent, 0.92) else null) }
        sb.append("</w:tbl><w:p/>")
        return sb.toString()
    }

    // ---------------------------------------------------------------- styles + numbering

    private fun styles(accent: String): String {
        fun font(size: Int, color: String, bold: Boolean = false, family: String = "Aptos") =
            "<w:rPr><w:rFonts w:ascii=\"$family\" w:hAnsi=\"$family\" w:cs=\"$family\"/>${if (bold) "<w:b/>" else ""}" +
                "<w:color w:val=\"$color\"/><w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/></w:rPr>"
        fun style(id: String, name: String, pPr: String, rPr: String, extra: String = "") =
            "<w:style w:type=\"paragraph\" w:styleId=\"$id\"><w:name w:val=\"$name\"/><w:basedOn w:val=\"Normal\"/>" +
                "<w:next w:val=\"Normal\"/>$extra<w:qFormat/>$pPr$rPr</w:style>"
        return OoxmlPackage.XML_HEAD + "<w:styles xmlns:w=\"$W\">" +
            "<w:docDefaults><w:rPrDefault>${font(22, "2B2724")}</w:rPrDefault>" +
            "<w:pPrDefault><w:pPr><w:spacing w:after=\"140\" w:line=\"300\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
            style("Title", "Title", "<w:pPr><w:spacing w:before=\"0\" w:after=\"80\"/></w:pPr>", font(56, "1E1B18", true, "Aptos Display")) +
            style("Subtitle", "Subtitle", "<w:pPr><w:spacing w:after=\"360\"/><w:pBdr><w:bottom w:val=\"single\" w:sz=\"12\" w:space=\"10\" w:color=\"$accent\"/></w:pBdr></w:pPr>", font(26, "6E6760")) +
            style("Heading1", "heading 1", "<w:pPr><w:keepNext/><w:spacing w:before=\"360\" w:after=\"120\"/><w:outlineLvl w:val=\"0\"/></w:pPr>", font(34, accent, true, "Aptos Display")) +
            style("Heading2", "heading 2", "<w:pPr><w:keepNext/><w:spacing w:before=\"260\" w:after=\"100\"/><w:outlineLvl w:val=\"1\"/></w:pPr>", font(28, "2B2724", true, "Aptos Display")) +
            style("Heading3", "heading 3", "<w:pPr><w:keepNext/><w:spacing w:before=\"200\" w:after=\"80\"/><w:outlineLvl w:val=\"2\"/></w:pPr>", font(24, "4A443F", true)) +
            style("ListParagraph", "List Paragraph", "<w:pPr><w:spacing w:after=\"60\"/><w:ind w:left=\"720\"/></w:pPr>", "") +
            style("Quote", "Quote", "<w:pPr><w:spacing w:before=\"160\" w:after=\"200\"/><w:ind w:left=\"560\"/>" +
                "<w:pBdr><w:left w:val=\"single\" w:sz=\"24\" w:space=\"12\" w:color=\"$accent\"/></w:pBdr></w:pPr>", "<w:rPr><w:i/><w:color w:val=\"5A534D\"/></w:rPr>") +
            "</w:styles>"
    }

    private fun numbering(numbered: List<Int>): String {
        val sb = StringBuilder(OoxmlPackage.XML_HEAD + "<w:numbering xmlns:w=\"$W\">")
        sb.append("<w:abstractNum w:abstractNumId=\"0\"><w:multiLevelType w:val=\"singleLevel\"/><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/>" +
            "<w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"•\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr></w:lvl></w:abstractNum>")
        sb.append("<w:abstractNum w:abstractNumId=\"1\"><w:multiLevelType w:val=\"singleLevel\"/><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/>" +
            "<w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%1.\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr></w:lvl></w:abstractNum>")
        sb.append("<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>")
        sb.append("<w:num w:numId=\"2\"><w:abstractNumId w:val=\"1\"/></w:num>")
        for (id in numbered) sb.append("<w:num w:numId=\"$id\"><w:abstractNumId w:val=\"1\"/><w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"1\"/></w:lvlOverride></w:num>")
        sb.append("</w:numbering>")
        return sb.toString()
    }
}
