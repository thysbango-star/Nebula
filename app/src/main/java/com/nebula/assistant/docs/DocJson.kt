package com.nebula.assistant.docs

import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts between the AI's JSON document spec and [DocSpec]. Lenient on input (the model
 * sometimes uses slightly different keys) and exact on output (saved next to each file for editing).
 */
object DocJson {

    /** The schema the AI is told to use (kept short to save tokens). */
    const val SCHEMA_HINT = """Document spec JSON:
{"type":"docx|xlsx|pptx|pdf|csv|txt|md|html","title":"...","subtitle":"optional",
 "blocks":[{"h1":"..."},{"h2":"..."},{"h3":"..."},{"p":"paragraph, **bold** allowed"},{"bullets":["..."]},{"numbered":["..."]},
           {"table":{"header":["A","B"],"rows":[["1","2"]]}},{"quote":"..."},{"pagebreak":true}],      <- docx, pdf, txt, md, html
 "sheets":[{"name":"Sheet","columns":[{"header":"Item","format":"text|number|currency|percent|date|integer","width":14}],
            "rows":[["Rent",8500,"=B2*12"]],"totals":true,
            "chart":{"type":"column|bar|line|pie","title":"...","categoryColumn":0,"valueColumns":[1]},
            "validations":[{"column":2,"list":["Yes","No"]}]}],                                           <- xlsx, csv
 "slides":[{"layout":"title|bullets|two_column|section|quote|closing","title":"...","subtitle":"...","bullets":["..."],
            "leftTitle":"...","left":["..."],"rightTitle":"...","right":["..."]}]}                        <- pptx
Use real formulas (=SUM(B2:B9), =B2*C2) in spreadsheets, dates as yyyy-mm-dd, 6-12 slides for decks, and substantial content."""

    fun parse(json: JSONObject, fallbackType: DocType? = null, currency: String = "$"): DocSpec {
        val type = DocType.parse(json.optString("type")) ?: fallbackType ?: DocType.DOCX
        return DocSpec(
            type = type,
            title = json.optString("title").ifBlank { "Untitled" },
            subtitle = json.optString("subtitle"),
            blocks = blocks(json.optJSONArray("blocks") ?: json.optJSONArray("content") ?: JSONArray()),
            sheets = sheets(json.optJSONArray("sheets")),
            slides = slides(json.optJSONArray("slides")),
            accent = json.optString("accent").ifBlank { "E8531E" },
            currency = json.optString("currency").ifBlank { currency },
        )
    }

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.opt(it)?.toString().orEmpty() }.filter { it.isNotBlank() }

    private fun blocks(a: JSONArray): List<Block> {
        val out = mutableListOf<Block>()
        for (i in 0 until a.length()) {
            val o = a.opt(i)
            if (o is String) { out += Block.Paragraph(o); continue }
            if (o !is JSONObject) continue
            when {
                o.has("h1") -> out += Block.Heading(1, o.optString("h1"))
                o.has("h2") -> out += Block.Heading(2, o.optString("h2"))
                o.has("h3") -> out += Block.Heading(3, o.optString("h3"))
                o.has("heading") -> out += Block.Heading(o.optInt("level", 1), o.optString("heading"))
                o.has("p") -> out += Block.Paragraph(o.optString("p"))
                o.has("paragraph") -> out += Block.Paragraph(o.optString("paragraph"))
                o.has("text") -> out += Block.Paragraph(o.optString("text"))
                o.has("bullets") -> out += Block.Bullets(strings(o.optJSONArray("bullets")))
                o.has("numbered") -> out += Block.Bullets(strings(o.optJSONArray("numbered")), numbered = true)
                o.has("list") -> out += Block.Bullets(strings(o.optJSONArray("list")), o.optBoolean("ordered"))
                o.has("table") -> o.optJSONObject("table")?.let { t ->
                    val rows = t.optJSONArray("rows") ?: JSONArray()
                    out += Block.Table(strings(t.optJSONArray("header")), (0 until rows.length()).map { r ->
                        val row = rows.optJSONArray(r) ?: JSONArray()
                        (0 until row.length()).map { c -> row.opt(c)?.toString().orEmpty() }
                    })
                }
                o.has("quote") -> out += Block.Quote(o.optString("quote"))
                o.optBoolean("pagebreak") -> out += Block.PageBreak
            }
        }
        return out
    }

    private fun sheets(a: JSONArray?): List<Sheet> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapIndexed { i, s ->
            val colsJ = s.optJSONArray("columns") ?: JSONArray()
            val columns = (0 until colsJ.length()).map { c ->
                when (val col = colsJ.opt(c)) {
                    is JSONObject -> Column(col.optString("header", col.optString("name")), col.optString("format", "text"),
                        if (col.has("width")) col.optDouble("width") else null)
                    else -> Column(col?.toString().orEmpty())
                }
            }
            val rowsJ = s.optJSONArray("rows") ?: JSONArray()
            val rows = (0 until rowsJ.length()).map { r ->
                val row = rowsJ.optJSONArray(r) ?: JSONArray()
                (0 until row.length()).map { c ->
                    when (val v = row.opt(c)) {
                        JSONObject.NULL, null -> null
                        is Int -> v.toDouble(); is Long -> v.toDouble(); is Double -> v; is Boolean -> v
                        else -> v.toString()
                    }
                }
            }
            val chart = s.optJSONObject("chart")?.let { c ->
                val vc = c.optJSONArray("valueColumns")
                Chart(c.optString("type", "column"), c.optString("title"), c.optInt("categoryColumn", 0),
                    if (vc != null) (0 until vc.length()).map { vc.optInt(it) } else listOf(1))
            }
            val valsJ = s.optJSONArray("validations") ?: JSONArray()
            val validations = (0 until valsJ.length()).mapNotNull { valsJ.optJSONObject(it) }.map {
                Validation(it.optInt("column"), strings(it.optJSONArray("list")))
            }
            Sheet(s.optString("name").ifBlank { "Sheet${i + 1}" }, columns, rows, s.optBoolean("totals"), chart, validations,
                s.optBoolean("freezeHeader", true))
        }
    }

    private fun slides(a: JSONArray?): List<Slide> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { s ->
            Slide(s.optString("layout", "bullets"), s.optString("title"), s.optString("subtitle"),
                strings(s.optJSONArray("bullets") ?: s.optJSONArray("points")),
                strings(s.optJSONArray("left")), strings(s.optJSONArray("right")),
                s.optString("leftTitle"), s.optString("rightTitle"), s.optString("notes"))
        }
    }

    // ---------------------------------------------------------------- back to JSON (saved for editing)

    fun toJson(d: DocSpec): JSONObject {
        val o = JSONObject().put("type", d.type.ext).put("title", d.title).put("subtitle", d.subtitle)
            .put("accent", d.accent).put("currency", d.currency)
        if (d.blocks.isNotEmpty()) o.put("blocks", JSONArray().apply {
            d.blocks.forEach { b ->
                put(when (b) {
                    is Block.Heading -> JSONObject().put("h${b.level.coerceIn(1, 3)}", b.text)
                    is Block.Paragraph -> JSONObject().put("p", b.text)
                    is Block.Bullets -> JSONObject().put(if (b.numbered) "numbered" else "bullets", JSONArray(b.items))
                    is Block.Table -> JSONObject().put("table", JSONObject().put("header", JSONArray(b.header))
                        .put("rows", JSONArray(b.rows.map { JSONArray(it) })))
                    is Block.Quote -> JSONObject().put("quote", b.text)
                    Block.PageBreak -> JSONObject().put("pagebreak", true)
                })
            }
        })
        if (d.sheets.isNotEmpty()) o.put("sheets", JSONArray().apply {
            d.sheets.forEach { s ->
                put(JSONObject().put("name", s.name)
                    .put("columns", JSONArray(s.columns.map { c -> JSONObject().put("header", c.header).put("format", c.format).also { j -> c.width?.let { j.put("width", it) } } }))
                    .put("rows", JSONArray(s.rows.map { r -> JSONArray(r.map { it ?: JSONObject.NULL }) }))
                    .put("totals", s.totals).put("freezeHeader", s.freezeHeader)
                    .also { j -> s.chart?.let { c -> j.put("chart", JSONObject().put("type", c.type).put("title", c.title).put("categoryColumn", c.categoryColumn).put("valueColumns", JSONArray(c.valueColumns))) } }
                    .also { j -> if (s.validations.isNotEmpty()) j.put("validations", JSONArray(s.validations.map { v -> JSONObject().put("column", v.column).put("list", JSONArray(v.list)) })) })
            }
        })
        if (d.slides.isNotEmpty()) o.put("slides", JSONArray().apply {
            d.slides.forEach { s ->
                put(JSONObject().put("layout", s.layout).put("title", s.title).put("subtitle", s.subtitle)
                    .put("bullets", JSONArray(s.bullets)).put("left", JSONArray(s.left)).put("right", JSONArray(s.right))
                    .put("leftTitle", s.leftTitle).put("rightTitle", s.rightTitle))
            }
        })
        return o
    }
}
