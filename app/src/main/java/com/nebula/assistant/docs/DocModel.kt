package com.nebula.assistant.docs

/**
 * Nebula's document model. The AI fills one of these in (as JSON, see DocJson), and the
 * writers turn it into a real file. The spec is saved next to the file so it can be edited
 * later by voice ("add a column and calculate the totals").
 */
enum class DocType(val ext: String, val mime: String, val label: String) {
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word document"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Excel spreadsheet"),
    PPTX("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "PowerPoint presentation"),
    PDF("pdf", "application/pdf", "PDF"),
    CSV("csv", "text/csv", "CSV file"),
    TXT("txt", "text/plain", "text file"),
    MD("md", "text/markdown", "Markdown file"),
    HTML("html", "text/html", "web page");

    companion object {
        fun parse(s: String): DocType? = entries.firstOrNull {
            it.ext.equals(s.trim().trimStart('.'), true) || it.name.equals(s.trim(), true)
        } ?: when (s.trim().lowercase()) {
            "word", "doc" -> DOCX
            "excel", "spreadsheet", "xls", "sheet" -> XLSX
            "powerpoint", "ppt", "slides", "presentation" -> PPTX
            "text" -> TXT
            "markdown" -> MD
            else -> null
        }
    }
}

/** Text blocks shared by DOCX, PDF, TXT, MD and HTML. */
sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Paragraph(val text: String) : Block
    data class Bullets(val items: List<String>, val numbered: Boolean = false) : Block
    data class Table(val header: List<String>, val rows: List<List<String>>) : Block
    data class Quote(val text: String) : Block
    data object PageBreak : Block
}

data class Column(
    val header: String,
    /** text | number | currency | percent | date | integer */
    val format: String = "text",
    val width: Double? = null,
)

data class Chart(
    /** bar | column | line | pie */
    val type: String = "column",
    val title: String = "",
    /** 0-based column holding the category labels. */
    val categoryColumn: Int = 0,
    /** 0-based columns holding the values (one series each). */
    val valueColumns: List<Int> = listOf(1),
)

data class Validation(val column: Int, val list: List<String>)

data class Sheet(
    val name: String,
    val columns: List<Column>,
    /** Cells: numbers, text, booleans, or formulas starting with "=". */
    val rows: List<List<Any?>>,
    /** Add a bold totals row that SUMs every numeric column. */
    val totals: Boolean = false,
    val chart: Chart? = null,
    val validations: List<Validation> = emptyList(),
    val freezeHeader: Boolean = true,
)

data class Slide(
    /** title | bullets | two_column | section | quote | closing */
    val layout: String = "bullets",
    val title: String = "",
    val subtitle: String = "",
    val bullets: List<String> = emptyList(),
    val left: List<String> = emptyList(),
    val right: List<String> = emptyList(),
    val leftTitle: String = "",
    val rightTitle: String = "",
    val notes: String = "",
)

data class DocSpec(
    val type: DocType,
    val title: String,
    val subtitle: String = "",
    val author: String = "Nebula",
    val blocks: List<Block> = emptyList(),
    val sheets: List<Sheet> = emptyList(),
    val slides: List<Slide> = emptyList(),
    /** Accent colour as hex without # (e.g. "E8531E"). */
    val accent: String = "E8531E",
    /** Currency symbol for "currency" columns (set from the phone's locale). */
    val currency: String = "$",
)

/** XML escaping + small helpers shared by the writers. */
internal object Xml {
    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> if (c.code >= 0x20 || c == '\n' || c == '\t' || c == '\r') sb.append(c)
            }
        }
        return sb.toString()
    }

    fun cleanHex(hex: String, fallback: String = "E8531E"): String {
        val h = hex.trim().trimStart('#').uppercase()
        return if (h.length == 6 && h.all { it in "0123456789ABCDEF" }) h else fallback
    }

    /** Lighter tint of a hex colour (0 = same, 1 = white). */
    fun tint(hex: String, amount: Double): String {
        val h = cleanHex(hex)
        val parts = (0 until 3).map { h.substring(it * 2, it * 2 + 2).toInt(16) }
        return parts.joinToString("") { c -> "%02X".format((c + (255 - c) * amount).toInt().coerceIn(0, 255)) }
    }

    /** Strips light markdown the AI sometimes adds (**bold**, `code`, leading #). */
    fun plain(s: String): String = s
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("__(.+?)__"), "$1")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("^#+\\s*"), "")
        .trim()
}
