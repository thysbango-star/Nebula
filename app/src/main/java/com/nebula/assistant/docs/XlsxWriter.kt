package com.nebula.assistant.docs

import java.io.OutputStream
import java.util.Calendar
import java.util.TimeZone

/**
 * Writes a real .xlsx: multiple sheets, formulas (recalculated on open), number/currency/percent/
 * date formats, a styled header, optional totals row, frozen header, filters, dropdown validation
 * and native Excel charts (column, bar, line, pie).
 */
object XlsxWriter {

    private const val S_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"

    // cellXfs indexes (see styles())
    private const val ST_HEADER = 1
    private val FMT_STYLE = mapOf("number" to 2, "currency" to 3, "percent" to 4, "date" to 5, "integer" to 6)
    private const val ST_TOTAL_LABEL = 7
    private val TOTAL_STYLE = mapOf("number" to 8, "currency" to 9, "percent" to 10, "integer" to 11, "text" to 7, "date" to 7)
    private val NUMERIC = setOf("number", "currency", "percent", "integer")

    fun write(spec: DocSpec, out: OutputStream) {
        val accent = Xml.cleanHex(spec.accent)
        val sheets = spec.sheets.ifEmpty { listOf(Sheet("Sheet1", listOf(Column("Notes")), listOf(listOf(spec.title)))) }
        val names = uniqueNames(sheets.map { it.name })
        val pkg = OoxmlPackage()
        val ct = StringBuilder(OoxmlPackage.XML_HEAD +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
            "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>")
        val wbRels = StringBuilder(OoxmlPackage.XML_HEAD + "<Relationships xmlns=\"${OoxmlPackage.REL_NS}\">")
        val wbSheets = StringBuilder()
        val definedNames = StringBuilder()
        var chartNo = 0

        sheets.forEachIndexed { i, sheet ->
            val n = i + 1
            val name = names[i]
            val built = buildSheet(sheet, name, spec.currency)
            var drawingRel = ""
            if (sheet.chart != null && built.dataRows > 0) {
                chartNo++
                drawingRel = "<drawing r:id=\"rId1\"/>"
                pkg.put("xl/worksheets/_rels/sheet$n.xml.rels", OoxmlPackage.XML_HEAD +
                    "<Relationships xmlns=\"${OoxmlPackage.REL_NS}\"><Relationship Id=\"rId1\" " +
                    "Type=\"${OoxmlPackage.R_DOC}/drawing\" Target=\"../drawings/drawing$chartNo.xml\"/></Relationships>")
                pkg.put("xl/drawings/drawing$chartNo.xml", drawing(sheet.columns.size))
                pkg.put("xl/drawings/_rels/drawing$chartNo.xml.rels", OoxmlPackage.XML_HEAD +
                    "<Relationships xmlns=\"${OoxmlPackage.REL_NS}\"><Relationship Id=\"rId1\" " +
                    "Type=\"${OoxmlPackage.R_DOC}/chart\" Target=\"../charts/chart$chartNo.xml\"/></Relationships>")
                pkg.put("xl/charts/chart$chartNo.xml", chart(sheet, name, built.dataRows, accent))
                ct.append("<Override PartName=\"/xl/drawings/drawing$chartNo.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/>")
                ct.append("<Override PartName=\"/xl/charts/chart$chartNo.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawingml.chart+xml\"/>")
            }
            pkg.put("xl/worksheets/sheet$n.xml", built.xml.replace("<!--DRAWING-->", drawingRel))
            ct.append("<Override PartName=\"/xl/worksheets/sheet$n.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
            wbRels.append("<Relationship Id=\"rId$n\" Type=\"${OoxmlPackage.R_DOC}/worksheet\" Target=\"worksheets/sheet$n.xml\"/>")
            wbSheets.append("<sheet name=\"${Xml.esc(name)}\" sheetId=\"$n\" r:id=\"rId$n\"/>")
            if (built.filterRef != null) {
                definedNames.append("<definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"$i\" hidden=\"1\">${Xml.esc(ref(name))}!${built.filterRef}</definedName>")
            }
        }
        wbRels.append("<Relationship Id=\"rId${sheets.size + 1}\" Type=\"${OoxmlPackage.R_DOC}/styles\" Target=\"styles.xml\"/></Relationships>")
        ct.append("</Types>")

        pkg.put("[Content_Types].xml", ct.toString())
        pkg.put("_rels/.rels", OoxmlPackage.XML_HEAD +
            "<Relationships xmlns=\"${OoxmlPackage.REL_NS}\">" +
            "<Relationship Id=\"rId1\" Type=\"${OoxmlPackage.R_DOC}/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
            "<Relationship Id=\"rId3\" Type=\"${OoxmlPackage.R_DOC}/extended-properties\" Target=\"docProps/app.xml\"/>" +
            "</Relationships>")
        pkg.put("xl/workbook.xml", OoxmlPackage.XML_HEAD +
            "<workbook xmlns=\"$S_NS\" xmlns:r=\"${OoxmlPackage.R_DOC}\"><bookViews><workbookView/></bookViews>" +
            "<sheets>$wbSheets</sheets>" + (if (definedNames.isNotEmpty()) "<definedNames>$definedNames</definedNames>" else "") +
            "<calcPr calcId=\"191029\" fullCalcOnLoad=\"1\"/></workbook>")
        pkg.put("xl/_rels/workbook.xml.rels", wbRels.toString())
        pkg.put("xl/styles.xml", styles(accent, spec.currency))
        pkg.put("docProps/core.xml", OoxmlPackage.coreProps(spec.title, spec.author, OoxmlPackage.nowIso()))
        pkg.put("docProps/app.xml", OoxmlPackage.appProps())
        pkg.writeTo(out)
    }

    // ---------------------------------------------------------------- sheet

    private class Built(val xml: String, val dataRows: Int, val filterRef: String?)

    private fun buildSheet(sheet: Sheet, name: String, currency: String): Built {
        val cols = sheet.columns.ifEmpty { listOf(Column("A")) }
        val nCols = maxOf(cols.size, sheet.rows.maxOfOrNull { it.size } ?: 0)
        val fmt = (0 until nCols).map { cols.getOrNull(it)?.format?.lowercase() ?: "text" }
        val data = sheet.rows
        val sb = StringBuilder()
        val lastRow = 1 + data.size + if (sheet.totals) 1 else 0

        sb.append(OoxmlPackage.XML_HEAD)
        sb.append("<worksheet xmlns=\"$S_NS\" xmlns:r=\"${OoxmlPackage.R_DOC}\">")
        sb.append("<dimension ref=\"A1:${col(nCols - 1)}${lastRow}\"/>")
        sb.append("<sheetViews><sheetView workbookViewId=\"0\"")
        if (sheet.freezeHeader) sb.append("><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>" +
            "<selection pane=\"bottomLeft\" activeCell=\"A2\" sqref=\"A2\"/></sheetView>") else sb.append("/>")
        sb.append("</sheetViews><sheetFormatPr defaultRowHeight=\"18\"/>")

        // Column widths: given, or estimated from the content.
        sb.append("<cols>")
        for (c in 0 until nCols) {
            val given = cols.getOrNull(c)?.width
            val longest = (listOf(cols.getOrNull(c)?.header.orEmpty()) + data.map { display(it.getOrNull(c)) })
                .maxOf { it.length }
            val w = (given ?: (longest * 1.15 + 3).coerceIn(9.0, 60.0)).coerceIn(4.0, 100.0)
            sb.append("<col min=\"${c + 1}\" max=\"${c + 1}\" width=\"${"%.1f".format(java.util.Locale.US, w)}\" customWidth=\"1\"/>")
        }
        sb.append("</cols><sheetData>")

        // Header
        sb.append("<row r=\"1\" ht=\"24\" customHeight=\"1\">")
        for (c in 0 until nCols) sb.append(strCell("${col(c)}1", cols.getOrNull(c)?.header ?: "", ST_HEADER))
        sb.append("</row>")

        // Data
        data.forEachIndexed { i, row ->
            val r = i + 2
            sb.append("<row r=\"$r\">")
            for (c in 0 until nCols) sb.append(cell("${col(c)}$r", row.getOrNull(c), fmt[c], FMT_STYLE[fmt[c]] ?: 0))
            sb.append("</row>")
        }

        // Totals
        if (sheet.totals && data.isNotEmpty()) {
            val r = data.size + 2
            sb.append("<row r=\"$r\" ht=\"22\" customHeight=\"1\">")
            for (c in 0 until nCols) {
                val ref = "${col(c)}$r"
                val style = TOTAL_STYLE[fmt[c]] ?: ST_TOTAL_LABEL
                when {
                    c == 0 && fmt[c] !in NUMERIC -> sb.append(strCell(ref, "Total", ST_TOTAL_LABEL))
                    fmt[c] in NUMERIC && fmt[c] != "percent" -> sb.append("<c r=\"$ref\" s=\"$style\"><f>SUM(${col(c)}2:${col(c)}${r - 1})</f></c>")
                    fmt[c] == "percent" -> sb.append("<c r=\"$ref\" s=\"$style\"><f>AVERAGE(${col(c)}2:${col(c)}${r - 1})</f></c>")
                    else -> sb.append("<c r=\"$ref\" s=\"$ST_TOTAL_LABEL\"/>")
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData>")

        val filterRef = if (data.isNotEmpty()) "\$A\$1:\$${col(nCols - 1)}\$${data.size + 1}" else null
        if (filterRef != null) sb.append("<autoFilter ref=\"A1:${col(nCols - 1)}${data.size + 1}\"/>")

        val vals = sheet.validations.filter { it.column in 0 until nCols && it.list.isNotEmpty() }
        if (vals.isNotEmpty()) {
            sb.append("<dataValidations count=\"${vals.size}\">")
            for (v in vals) {
                val list = v.list.joinToString(",") { it.replace(",", " ").replace("\"", "'") }.take(250)
                val c = col(v.column)
                sb.append("<dataValidation type=\"list\" allowBlank=\"1\" showInputMessage=\"1\" showErrorMessage=\"1\" " +
                    "sqref=\"${c}2:${c}${maxOf(200, data.size + 50)}\"><formula1>${Xml.esc("\"$list\"")}</formula1></dataValidation>")
            }
            sb.append("</dataValidations>")
        }
        sb.append("<pageMargins left=\"0.6\" right=\"0.6\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/>")
        sb.append("<pageSetup orientation=\"${if (nCols > 6) "landscape" else "portrait"}\" fitToWidth=\"1\" fitToHeight=\"0\"/>")
        sb.append("<!--DRAWING--></worksheet>")
        return Built(sb.toString(), data.size, filterRef)
    }

    private fun cell(ref: String, v: Any?, format: String, style: Int): String = when {
        v == null -> if (style != 0) "<c r=\"$ref\" s=\"$style\"/>" else ""
        v is Number -> numCell(ref, v.toDouble(), style)
        v is Boolean -> "<c r=\"$ref\" t=\"b\"${s(style)}><v>${if (v) 1 else 0}</v></c>"
        else -> {
            val t = v.toString().trim()
            when {
                t.startsWith("=") && t.length > 1 -> "<c r=\"$ref\"${s(style)}><f>${Xml.esc(t.substring(1))}</f></c>"
                format == "date" && dateSerial(t) != null -> numCell(ref, dateSerial(t)!!, style)
                format in NUMERIC && parseNumber(t, format) != null -> numCell(ref, parseNumber(t, format)!!, style)
                else -> strCell(ref, t, if (format in NUMERIC || format == "date") 0 else style)
            }
        }
    }

    private fun s(style: Int) = if (style != 0) " s=\"$style\"" else ""
    private fun numCell(ref: String, d: Double, style: Int): String {
        val txt = if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()
        return "<c r=\"$ref\"${s(style)}><v>$txt</v></c>"
    }
    private fun strCell(ref: String, text: String, style: Int) =
        "<c r=\"$ref\" t=\"inlineStr\"${s(style)}><is><t xml:space=\"preserve\">${Xml.esc(text)}</t></is></c>"

    /** "R 1 250,50" / "$1,250.50" / "12%" → number. */
    internal fun parseNumber(t: String, format: String): Double? {
        var x = t.replace(Regex("[\\s\\u00A0]"), "").replace(Regex("^[^0-9\\-+.,(]+"), "").replace(Regex("[^0-9.,%\\-]+$"), "")
        val pct = x.endsWith("%")
        x = x.removeSuffix("%")
        if (x.count { it == ',' } >= 1 && x.count { it == '.' } == 0 && Regex(",\\d{1,2}$").containsMatchIn(x)) {
            x = x.replace(",", ".") // decimal comma
        } else x = x.replace(",", "")
        val d = x.toDoubleOrNull() ?: return null
        return if (pct || (format == "percent" && d > 1.0)) d / 100.0 else d
    }

    /** ISO date (yyyy-mm-dd) → Excel serial date. */
    internal fun dateSerial(t: String): Double? {
        val m = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})").find(t) ?: return null
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear(); set(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt())
        }
        val epoch = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(1899, 11, 30) }
        return ((cal.timeInMillis - epoch.timeInMillis) / 86_400_000L).toDouble()
    }

    private fun display(v: Any?): String = when (v) {
        null -> ""
        is Double -> if (v == Math.floor(v)) v.toLong().toString() else "%.2f".format(java.util.Locale.US, v)
        else -> v.toString().let { if (it.startsWith("=")) "0000000000" else it }
    }

    internal fun col(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.insert(0, ('A' + r)); n = (n - 1) / 26 }
        return sb.toString()
    }

    private fun ref(sheetName: String) = "'" + sheetName.replace("'", "''") + "'"

    private fun uniqueNames(raw: List<String>): List<String> {
        val used = mutableSetOf<String>()
        return raw.mapIndexed { i, n ->
            var base = n.replace(Regex("[\\\\/?*\\[\\]:]"), " ").trim().ifBlank { "Sheet${i + 1}" }.take(31)
            var name = base; var k = 2
            while (!used.add(name.lowercase())) { name = base.take(28) + " $k"; k++ }
            name
        }
    }

    // ---------------------------------------------------------------- chart

    private fun drawing(nCols: Int): String = OoxmlPackage.XML_HEAD +
        "<xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" " +
        "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">" +
        "<xdr:twoCellAnchor editAs=\"oneCell\"><xdr:from><xdr:col>${nCols + 1}</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>1</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>" +
        "<xdr:to><xdr:col>${nCols + 9}</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>20</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>" +
        "<xdr:graphicFrame macro=\"\"><xdr:nvGraphicFramePr><xdr:cNvPr id=\"2\" name=\"Chart 1\"/><xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr>" +
        "<xdr:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/></xdr:xfrm>" +
        "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/chart\">" +
        "<c:chart xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" " +
        "xmlns:r=\"${OoxmlPackage.R_DOC}\" r:id=\"rId1\"/></a:graphicData></a:graphic></xdr:graphicFrame>" +
        "<xdr:clientData/></xdr:twoCellAnchor></xdr:wsDr>"

    private val PALETTE = listOf("E8531E", "F2A31B", "7C5CFF", "2BB3A3", "E5487F", "3D8BFD", "8A9A2B", "B5651D")

    private fun chart(sheet: Sheet, name: String, rows: Int, accent: String): String {
        val ch = sheet.chart!!
        val type = ch.type.lowercase()
        val nCols = maxOf(sheet.columns.size, sheet.rows.maxOfOrNull { it.size } ?: 0)
        val catCol = ch.categoryColumn.coerceIn(0, nCols - 1)
        val valCols = ch.valueColumns.filter { it in 0 until nCols && it != catCol }.ifEmpty {
            (0 until nCols).filter { c -> c != catCol && sheet.columns.getOrNull(c)?.format?.lowercase() in NUMERIC }.take(3)
        }.ifEmpty { listOf((catCol + 1).coerceAtMost(nCols - 1)) }
        val sref = ref(name)
        val first = 2; val last = rows + 1
        val colors = listOf(accent) + PALETTE.filter { it != accent }

        fun strCache(c: Int): String {
            val pts = (0 until rows).joinToString("") { i -> "<c:pt idx=\"$i\"><c:v>${Xml.esc(display(sheet.rows[i].getOrNull(c)))}</c:v></c:pt>" }
            return "<c:strCache><c:ptCount val=\"$rows\"/>$pts</c:strCache>"
        }
        fun numCache(c: Int): String {
            val fmt = sheet.columns.getOrNull(c)?.format?.lowercase() ?: "number"
            val pts = (0 until rows).mapNotNull { i ->
                val v = sheet.rows[i].getOrNull(c)
                val d = when (v) { is Number -> v.toDouble(); is String -> if (v.startsWith("=")) null else parseNumber(v, fmt); else -> null }
                d?.let { "<c:pt idx=\"$i\"><c:v>$it</c:v></c:pt>" }
            }.joinToString("")
            return "<c:numCache><c:formatCode>General</c:formatCode><c:ptCount val=\"$rows\"/>$pts</c:numCache>"
        }
        fun series(idx: Int, c: Int): String {
            val color = colors[idx % colors.size]
            val sb = StringBuilder("<c:ser><c:idx val=\"$idx\"/><c:order val=\"$idx\"/>")
            sb.append("<c:tx><c:strRef><c:f>${Xml.esc("$sref!\$${col(c)}\$1")}</c:f><c:strCache><c:ptCount val=\"1\"/>" +
                "<c:pt idx=\"0\"><c:v>${Xml.esc(sheet.columns.getOrNull(c)?.header ?: "Series ${idx + 1}")}</c:v></c:pt></c:strCache></c:strRef></c:tx>")
            when (type) {
                "line" -> sb.append("<c:spPr><a:ln w=\"28575\" cap=\"rnd\"><a:solidFill><a:srgbClr val=\"$color\"/></a:solidFill><a:round/></a:ln></c:spPr>" +
                    "<c:marker><c:symbol val=\"circle\"/><c:size val=\"6\"/><c:spPr><a:solidFill><a:srgbClr val=\"$color\"/></a:solidFill></c:spPr></c:marker>")
                "pie" -> {
                    sb.append("<c:spPr><a:ln w=\"12700\"><a:solidFill><a:srgbClr val=\"FFFFFF\"/></a:solidFill></a:ln></c:spPr>")
                    for (p in 0 until rows) sb.append("<c:dPt><c:idx val=\"$p\"/><c:bubble3D val=\"0\"/><c:spPr><a:solidFill><a:srgbClr val=\"${colors[p % colors.size]}\"/></a:solidFill>" +
                        "<a:ln w=\"12700\"><a:solidFill><a:srgbClr val=\"FFFFFF\"/></a:solidFill></a:ln></c:spPr></c:dPt>")
                    sb.append("<c:dLbls><c:showLegendKey val=\"0\"/><c:showVal val=\"0\"/><c:showCatName val=\"0\"/><c:showSerName val=\"0\"/>" +
                        "<c:showPercent val=\"1\"/><c:showBubbleSize val=\"0\"/><c:showLeaderLines val=\"1\"/></c:dLbls>")
                }
                else -> sb.append("<c:spPr><a:solidFill><a:srgbClr val=\"$color\"/></a:solidFill></c:spPr><c:invertIfNegative val=\"0\"/>")
            }
            sb.append("<c:cat><c:strRef><c:f>${Xml.esc("$sref!\$${col(catCol)}\$$first:\$${col(catCol)}\$$last")}</c:f>${strCache(catCol)}</c:strRef></c:cat>")
            sb.append("<c:val><c:numRef><c:f>${Xml.esc("$sref!\$${col(c)}\$$first:\$${col(c)}\$$last")}</c:f>${numCache(c)}</c:numRef></c:val>")
            if (type == "line") sb.append("<c:smooth val=\"0\"/>")
            sb.append("</c:ser>")
            return sb.toString()
        }

        val title = ch.title.ifBlank { sheet.columns.getOrNull(valCols.first())?.header ?: sheet.name }
        val axes = "<c:catAx><c:axId val=\"500001\"/><c:scaling><c:orientation val=\"minMax\"/></c:scaling><c:delete val=\"0\"/>" +
            "<c:axPos val=\"${if (type == "bar") "l" else "b"}\"/><c:numFmt formatCode=\"General\" sourceLinked=\"1\"/><c:majorTickMark val=\"none\"/><c:minorTickMark val=\"none\"/>" +
            "<c:tickLblPos val=\"nextTo\"/><c:spPr><a:ln w=\"9525\"><a:solidFill><a:srgbClr val=\"D9D4CE\"/></a:solidFill></a:ln></c:spPr>" +
            "<c:crossAx val=\"500002\"/><c:crosses val=\"autoZero\"/><c:auto val=\"1\"/><c:lblAlgn val=\"ctr\"/><c:lblOffset val=\"100\"/><c:noMultiLvlLbl val=\"0\"/></c:catAx>" +
            "<c:valAx><c:axId val=\"500002\"/><c:scaling><c:orientation val=\"minMax\"/></c:scaling><c:delete val=\"0\"/>" +
            "<c:axPos val=\"${if (type == "bar") "b" else "l"}\"/><c:majorGridlines><c:spPr><a:ln w=\"9525\"><a:solidFill><a:srgbClr val=\"EEEAE6\"/></a:solidFill></a:ln></c:spPr></c:majorGridlines>" +
            "<c:numFmt formatCode=\"General\" sourceLinked=\"1\"/><c:majorTickMark val=\"none\"/><c:minorTickMark val=\"none\"/><c:tickLblPos val=\"nextTo\"/>" +
            "<c:spPr><a:ln><a:noFill/></a:ln></c:spPr><c:crossAx val=\"500001\"/><c:crosses val=\"autoZero\"/><c:crossBetween val=\"between\"/></c:valAx>"
        val plot = when (type) {
            "pie" -> "<c:pieChart><c:varyColors val=\"1\"/>${series(0, valCols.first())}<c:firstSliceAng val=\"0\"/></c:pieChart>"
            "line" -> "<c:lineChart><c:grouping val=\"standard\"/><c:varyColors val=\"0\"/>" +
                valCols.mapIndexed { i, c -> series(i, c) }.joinToString("") +
                "<c:marker val=\"1\"/><c:axId val=\"500001\"/><c:axId val=\"500002\"/></c:lineChart>$axes"
            else -> "<c:barChart><c:barDir val=\"${if (type == "bar") "bar" else "col"}\"/><c:grouping val=\"clustered\"/><c:varyColors val=\"0\"/>" +
                valCols.mapIndexed { i, c -> series(i, c) }.joinToString("") +
                "<c:gapWidth val=\"70\"/><c:axId val=\"500001\"/><c:axId val=\"500002\"/></c:barChart>$axes"
        }
        return OoxmlPackage.XML_HEAD +
            "<c:chartSpace xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" " +
            "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"${OoxmlPackage.R_DOC}\">" +
            "<c:roundedCorners val=\"0\"/><c:chart><c:title><c:tx><c:rich><a:bodyPr/><a:p><a:pPr><a:defRPr sz=\"1400\" b=\"1\"/></a:pPr>" +
            "<a:r><a:rPr lang=\"en-US\" sz=\"1400\" b=\"1\"/><a:t>${Xml.esc(title)}</a:t></a:r></a:p></c:rich></c:tx><c:overlay val=\"0\"/></c:title>" +
            "<c:autoTitleDeleted val=\"0\"/><c:plotArea><c:layout/>$plot</c:plotArea>" +
            "<c:legend><c:legendPos val=\"b\"/><c:overlay val=\"0\"/></c:legend><c:plotVisOnly val=\"1\"/><c:dispBlanksAs val=\"gap\"/></c:chart>" +
            "<c:spPr><a:solidFill><a:srgbClr val=\"FFFFFF\"/></a:solidFill><a:ln><a:noFill/></a:ln></c:spPr></c:chartSpace>"
    }

    // ---------------------------------------------------------------- styles

    private fun styles(accent: String, currency: String): String {
        val cur = Xml.esc("\"${currency.replace("\"", "")} \"#,##0.00;[Red]-\"${currency.replace("\"", "")} \"#,##0.00")
        val light = Xml.tint(accent, 0.88)
        return OoxmlPackage.XML_HEAD + "<styleSheet xmlns=\"$S_NS\">" +
            "<numFmts count=\"4\"><numFmt numFmtId=\"164\" formatCode=\"#,##0.00\"/><numFmt numFmtId=\"165\" formatCode=\"$cur\"/>" +
            "<numFmt numFmtId=\"166\" formatCode=\"0.0%\"/><numFmt numFmtId=\"167\" formatCode=\"yyyy-mm-dd\"/></numFmts>" +
            "<fonts count=\"3\"><font><sz val=\"11\"/><color rgb=\"FF2B2724\"/><name val=\"Aptos\"/><family val=\"2\"/></font>" +
            "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Aptos\"/><family val=\"2\"/></font>" +
            "<font><b/><sz val=\"11\"/><color rgb=\"FF1E1B18\"/><name val=\"Aptos\"/><family val=\"2\"/></font></fonts>" +
            "<fills count=\"4\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>" +
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF$accent\"/><bgColor indexed=\"64\"/></patternFill></fill>" +
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF$light\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>" +
            "<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border>" +
            "<border><left/><right/><top style=\"medium\"><color rgb=\"FF$accent\"/></top><bottom/><diagonal/></border></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"12\">" +
            "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyAlignment=\"1\"><alignment vertical=\"center\"/></xf>" +
            "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
            "<xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
            "<xf numFmtId=\"166\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
            "<xf numFmtId=\"167\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
            "<xf numFmtId=\"3\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
            "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"3\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"/>" +
            "<xf numFmtId=\"164\" fontId=\"2\" fillId=\"3\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"/>" +
            "<xf numFmtId=\"165\" fontId=\"2\" fillId=\"3\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"/>" +
            "<xf numFmtId=\"166\" fontId=\"2\" fillId=\"3\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"/>" +
            "<xf numFmtId=\"3\" fontId=\"2\" fillId=\"3\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"/>" +
            "</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>"
    }
}
