package com.nebula.assistant.docs

import java.io.OutputStream

/**
 * Writes a real 16:9 .pptx with designed layouts: title, section, bullets, two-column, quote, closing.
 * Every element is a real, editable PowerPoint shape/text box.
 */
object PptxWriter {

    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val CX = 12192000L
    private const val CY = 6858000L
    private const val INK = "1E1B18"
    private const val SOFT = "6E6760"
    private const val PAPER = "FAF8F6"

    fun write(spec: DocSpec, out: OutputStream) {
        val accent = Xml.cleanHex(spec.accent)
        val slides = spec.slides.ifEmpty { listOf(Slide("title", spec.title, spec.subtitle)) }
        val pkg = OoxmlPackage()
        val ct = StringBuilder(OoxmlPackage.XML_HEAD +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>" +
            "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>" +
            "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>" +
            "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>" +
            "<Override PartName=\"/ppt/presProps.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presProps+xml\"/>" +
            "<Override PartName=\"/ppt/viewProps.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.viewProps+xml\"/>" +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
            "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>")
        val presRels = StringBuilder(OoxmlPackage.XML_HEAD + "<Relationships xmlns=\"${OoxmlPackage.REL_NS}\">" +
            "<Relationship Id=\"rId1\" Type=\"${OoxmlPackage.R_DOC}/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>")
        val sldIds = StringBuilder()

        slides.forEachIndexed { i, s ->
            val n = i + 1
            pkg.put("ppt/slides/slide$n.xml", slide(s, n, slides.size, accent, spec.title))
            pkg.put("ppt/slides/_rels/slide$n.xml.rels", OoxmlPackage.XML_HEAD +
                "<Relationships xmlns=\"${OoxmlPackage.REL_NS}\"><Relationship Id=\"rId1\" " +
                "Type=\"${OoxmlPackage.R_DOC}/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/></Relationships>")
            ct.append("<Override PartName=\"/ppt/slides/slide$n.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>")
            presRels.append("<Relationship Id=\"rId${n + 1}\" Type=\"${OoxmlPackage.R_DOC}/slide\" Target=\"slides/slide$n.xml\"/>")
            sldIds.append("<p:sldId id=\"${255 + n}\" r:id=\"rId${n + 1}\"/>")
        }
        val k = slides.size + 2
        presRels.append("<Relationship Id=\"rId$k\" Type=\"${OoxmlPackage.R_DOC}/theme\" Target=\"theme/theme1.xml\"/>")
        presRels.append("<Relationship Id=\"rId${k + 1}\" Type=\"${OoxmlPackage.R_DOC}/presProps\" Target=\"presProps.xml\"/>")
        presRels.append("<Relationship Id=\"rId${k + 2}\" Type=\"${OoxmlPackage.R_DOC}/viewProps\" Target=\"viewProps.xml\"/>")
        presRels.append("</Relationships>")
        ct.append("</Types>")

        pkg.put("[Content_Types].xml", ct.toString())
        pkg.put("_rels/.rels", OoxmlPackage.XML_HEAD +
            "<Relationships xmlns=\"${OoxmlPackage.REL_NS}\">" +
            "<Relationship Id=\"rId1\" Type=\"${OoxmlPackage.R_DOC}/officeDocument\" Target=\"ppt/presentation.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
            "<Relationship Id=\"rId3\" Type=\"${OoxmlPackage.R_DOC}/extended-properties\" Target=\"docProps/app.xml\"/>" +
            "</Relationships>")
        pkg.put("ppt/presentation.xml", OoxmlPackage.XML_HEAD +
            "<p:presentation xmlns:a=\"$A\" xmlns:r=\"${OoxmlPackage.R_DOC}\" xmlns:p=\"$P\" saveSubsetFonts=\"1\">" +
            "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>" +
            "<p:sldIdLst>$sldIds</p:sldIdLst><p:sldSz cx=\"$CX\" cy=\"$CY\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/>" +
            "<p:defaultTextStyle><a:defPPr><a:defRPr lang=\"en-US\"/></a:defPPr></p:defaultTextStyle></p:presentation>")
        pkg.put("ppt/_rels/presentation.xml.rels", presRels.toString())
        pkg.put("ppt/presProps.xml", OoxmlPackage.XML_HEAD + "<p:presentationPr xmlns:a=\"$A\" xmlns:r=\"${OoxmlPackage.R_DOC}\" xmlns:p=\"$P\"/>")
        pkg.put("ppt/viewProps.xml", OoxmlPackage.XML_HEAD + "<p:viewPr xmlns:a=\"$A\" xmlns:r=\"${OoxmlPackage.R_DOC}\" xmlns:p=\"$P\">" +
            "<p:normalViewPr><p:restoredLeft sz=\"15620\"/><p:restoredTop sz=\"94660\"/></p:normalViewPr><p:gridSpacing cx=\"76200\" cy=\"76200\"/></p:viewPr>")
        pkg.put("ppt/slideMasters/slideMaster1.xml", master())
        pkg.put("ppt/slideMasters/_rels/slideMaster1.xml.rels", OoxmlPackage.XML_HEAD +
            "<Relationships xmlns=\"${OoxmlPackage.REL_NS}\">" +
            "<Relationship Id=\"rId1\" Type=\"${OoxmlPackage.R_DOC}/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"${OoxmlPackage.R_DOC}/theme\" Target=\"../theme/theme1.xml\"/></Relationships>")
        pkg.put("ppt/slideLayouts/slideLayout1.xml", OoxmlPackage.XML_HEAD +
            "<p:sldLayout xmlns:a=\"$A\" xmlns:r=\"${OoxmlPackage.R_DOC}\" xmlns:p=\"$P\" type=\"blank\" preserve=\"1\">" +
            "<p:cSld name=\"Blank\"><p:spTree>${grpHead()}</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>")
        pkg.put("ppt/slideLayouts/_rels/slideLayout1.xml.rels", OoxmlPackage.XML_HEAD +
            "<Relationships xmlns=\"${OoxmlPackage.REL_NS}\"><Relationship Id=\"rId1\" " +
            "Type=\"${OoxmlPackage.R_DOC}/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/></Relationships>")
        pkg.put("ppt/theme/theme1.xml", theme(accent))
        pkg.put("docProps/core.xml", OoxmlPackage.coreProps(spec.title, spec.author, OoxmlPackage.nowIso()))
        pkg.put("docProps/app.xml", OoxmlPackage.appProps())
        pkg.writeTo(out)
    }

    // ---------------------------------------------------------------- slides

    private fun slide(s: Slide, n: Int, total: Int, accent: String, deckTitle: String): String {
        val shapes = StringBuilder()
        var id = 2
        fun add(x: String) { shapes.append(x); id++ }
        val layout = s.layout.lowercase()
        val warm = Xml.cleanHex("F2A31B")
        var bg = "<a:solidFill><a:srgbClr val=\"$PAPER\"/></a:solidFill>"

        when (layout) {
            "title", "closing" -> {
                bg = "<a:gradFill rotWithShape=\"1\"><a:gsLst><a:gs pos=\"0\"><a:srgbClr val=\"$warm\"/></a:gs>" +
                    "<a:gs pos=\"55000\"><a:srgbClr val=\"$accent\"/></a:gs><a:gs pos=\"100000\"><a:srgbClr val=\"B8241B\"/></a:gs></a:gsLst>" +
                    "<a:lin ang=\"2700000\" scaled=\"0\"/></a:gradFill>"
                add(ellipse(id, -1600000, -2200000, 6200000, 6200000, "FFFFFF", 12))
                add(ellipse(id, 8600000, 3900000, 5200000, 5200000, "FFFFFF", 8))
                val titleSize = fit(s.title, 5400, 3600, 30)
                add(text(id, 900000, 2150000, CX - 1800000, 1700000, listOf(Run(s.title, titleSize, true, "FFFFFF")), anchor = "b"))
                val sub = s.subtitle.ifBlank { if (layout == "closing") "Thank you" else "" }
                if (sub.isNotBlank()) add(text(id, 900000, 3950000, CX - 1800000, 900000, listOf(Run(sub, 2000, false, "FFF3E8"))))
                add(rect(id, 900000, 1900000, 900000, 70000, "FFFFFF", 100))
            }
            "section" -> {
                bg = "<a:solidFill><a:srgbClr val=\"${Xml.tint(accent, 0.88)}\"/></a:solidFill>"
                add(text(id, 900000, 1500000, 3000000, 1600000, listOf(Run("%02d".format(n), 9600, true, accent))))
                add(text(id, 900000, 3200000, CX - 1800000, 1300000, listOf(Run(s.title, fit(s.title, 4400, 3200, 40), true, INK)), anchor = "t"))
                if (s.subtitle.isNotBlank()) add(text(id, 900000, 4500000, CX - 1800000, 800000, listOf(Run(s.subtitle, 1800, false, SOFT))))
            }
            "quote" -> {
                add(text(id, 900000, 900000, 2000000, 1800000, listOf(Run("“", 16000, true, accent))))
                add(text(id, 1500000, 2000000, CX - 3000000, 2600000, listOf(Run(s.title, fit(s.title, 3200, 2200, 120), false, INK)), anchor = "ctr"))
                if (s.subtitle.isNotBlank()) add(text(id, 1500000, 4800000, CX - 3000000, 600000, listOf(Run("— " + s.subtitle, 1600, true, SOFT))))
            }
            "two_column" -> {
                add(rect(id, 0, 0, 160000, CY, accent, 100))
                add(text(id, 700000, 450000, CX - 1400000, 1000000, listOf(Run(s.title, fit(s.title, 3200, 2400, 50), true, INK)), anchor = "b"))
                val w = (CX - 1400000 - 400000) / 2
                listOf(Triple(s.leftTitle, s.left, 700000L), Triple(s.rightTitle, s.right, 700000L + w + 400000)).forEach { (t, items, x) ->
                    add(round(id, x, 1700000, w, 4500000, "FFFFFF"))
                    var y = 1950000L
                    if (t.isNotBlank()) { add(text(id, x + 300000, y, w - 600000, 600000, listOf(Run(t, 2000, true, accent)))); y += 650000 }
                    add(bullets(id, x + 300000, y, w - 600000, 6000000L - y, items, accent))
                }
            }
            else -> { // bullets
                add(rect(id, 0, 0, 160000, CY, accent, 100))
                add(text(id, 700000, 450000, CX - 1400000, 1100000, listOf(Run(s.title, fit(s.title, 3400, 2400, 50), true, INK)), anchor = "b"))
                add(rect(id, 700000, 1650000, 700000, 50000, accent, 100))
                if (s.subtitle.isNotBlank()) add(text(id, 700000, 1780000, CX - 1400000, 500000, listOf(Run(s.subtitle, 1600, false, SOFT))))
                val top = if (s.subtitle.isNotBlank()) 2350000L else 1950000L
                add(bullets(id, 700000, top, CX - 1400000, CY - top - 700000, s.bullets, accent))
            }
        }
        if (layout !in setOf("title", "closing")) {
            add(text(id, 700000, CY - 520000, 6000000, 320000, listOf(Run(deckTitle, 1000, false, "A39C95"))))
            add(text(id, CX - 1700000, CY - 520000, 1000000, 320000, listOf(Run("$n / $total", 1000, true, "A39C95")), align = "r"))
        }
        return OoxmlPackage.XML_HEAD +
            "<p:sld xmlns:a=\"$A\" xmlns:r=\"${OoxmlPackage.R_DOC}\" xmlns:p=\"$P\"><p:cSld>" +
            "<p:bg><p:bgPr>$bg<a:effectLst/></p:bgPr></p:bg><p:spTree>${grpHead()}$shapes</p:spTree></p:cSld>" +
            "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"
    }

    private data class Run(val text: String, val size: Int, val bold: Boolean, val color: String)

    /** Font size (hundredths of a point) that keeps long titles inside their box. */
    private fun fit(text: String, big: Int, small: Int, chars: Int): Int =
        if (text.length <= chars) big else maxOf(small, (big * chars / text.length.toDouble()).toInt())

    private fun grpHead() = "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
        "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"

    private fun xfrm(x: Long, y: Long, w: Long, h: Long) =
        "<a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$w\" cy=\"$h\"/></a:xfrm>"

    private fun fill(color: String, alphaPct: Int) =
        "<a:solidFill><a:srgbClr val=\"$color\">${if (alphaPct < 100) "<a:alpha val=\"${alphaPct * 1000}\"/>" else ""}</a:srgbClr></a:solidFill>"

    private fun shape(id: Int, name: String, geom: String, x: Long, y: Long, w: Long, h: Long, color: String, alpha: Int) =
        "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"$name $id\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr>${xfrm(x, y, w, h)}" +
            "<a:prstGeom prst=\"$geom\"><a:avLst/></a:prstGeom>${fill(color, alpha)}<a:ln><a:noFill/></a:ln></p:spPr>" +
            "<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang=\"en-US\"/></a:p></p:txBody></p:sp>"

    private fun rect(id: Int, x: Long, y: Long, w: Long, h: Long, c: String, a: Int) = shape(id, "Bar", "rect", x, y, w, h, c, a)
    private fun ellipse(id: Int, x: Long, y: Long, w: Long, h: Long, c: String, a: Int) = shape(id, "Glow", "ellipse", x, y, w, h, c, a)
    private fun round(id: Int, x: Long, y: Long, w: Long, h: Long, c: String) = shape(id, "Card", "roundRect", x, y, w, h, c, 100)

    private fun text(
        id: Int, x: Long, y: Long, w: Long, h: Long, runs: List<Run>, anchor: String = "t", align: String = "l",
    ): String {
        val paras = runs.joinToString("") { r ->
            "<a:p><a:pPr algn=\"$align\"/><a:r><a:rPr lang=\"en-US\" sz=\"${r.size}\" b=\"${if (r.bold) 1 else 0}\" dirty=\"0\">" +
                "<a:solidFill><a:srgbClr val=\"${r.color}\"/></a:solidFill><a:latin typeface=\"Aptos\"/></a:rPr>" +
                "<a:t>${Xml.esc(Xml.plain(r.text))}</a:t></a:r></a:p>"
        }
        return "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"Text $id\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>" +
            "<p:spPr>${xfrm(x, y, w, h)}<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>" +
            "<p:txBody><a:bodyPr wrap=\"square\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\" anchor=\"$anchor\"><a:normAutofit/></a:bodyPr><a:lstStyle/>$paras</p:txBody></p:sp>"
    }

    private fun bullets(id: Int, x: Long, y: Long, w: Long, h: Long, items: List<String>, accent: String): String {
        val list = items.ifEmpty { listOf(" ") }
        val totalChars = list.sumOf { it.length }
        val size = when { list.size <= 3 && totalChars < 160 -> 2400; list.size <= 5 && totalChars < 320 -> 2000; list.size <= 7 && totalChars < 500 -> 1700; else -> 1400 }
        val paras = list.joinToString("") { item ->
            "<a:p><a:pPr marL=\"342900\" indent=\"-342900\"><a:spcBef><a:spcPts val=\"${size / 2}\"/></a:spcBef>" +
                "<a:buClr><a:srgbClr val=\"$accent\"/></a:buClr><a:buFont typeface=\"Arial\"/><a:buChar char=\"•\"/></a:pPr>" +
                "<a:r><a:rPr lang=\"en-US\" sz=\"$size\" dirty=\"0\"><a:solidFill><a:srgbClr val=\"3A3430\"/></a:solidFill><a:latin typeface=\"Aptos\"/></a:rPr>" +
                "<a:t>${Xml.esc(Xml.plain(item))}</a:t></a:r></a:p>"
        }
        return "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"Bullets $id\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>" +
            "<p:spPr>${xfrm(x, y, w, h)}<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>" +
            "<p:txBody><a:bodyPr wrap=\"square\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\" anchor=\"t\"><a:normAutofit/></a:bodyPr><a:lstStyle/>$paras</p:txBody></p:sp>"
    }

    // ---------------------------------------------------------------- master + theme

    private fun master() = OoxmlPackage.XML_HEAD +
        "<p:sldMaster xmlns:a=\"$A\" xmlns:r=\"${OoxmlPackage.R_DOC}\" xmlns:p=\"$P\">" +
        "<p:cSld><p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg><p:spTree>${grpHead()}</p:spTree></p:cSld>" +
        "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" " +
        "accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
        "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst>" +
        "<p:txStyles><p:titleStyle><a:lvl1pPr><a:defRPr sz=\"4400\"><a:solidFill><a:schemeClr val=\"tx1\"/></a:solidFill>" +
        "<a:latin typeface=\"+mj-lt\"/></a:defRPr></a:lvl1pPr></p:titleStyle><p:bodyStyle><a:lvl1pPr><a:defRPr sz=\"2000\">" +
        "<a:solidFill><a:schemeClr val=\"tx1\"/></a:solidFill><a:latin typeface=\"+mn-lt\"/></a:defRPr></a:lvl1pPr></p:bodyStyle>" +
        "<p:otherStyle><a:lvl1pPr><a:defRPr sz=\"1800\"><a:solidFill><a:schemeClr val=\"tx1\"/></a:solidFill></a:defRPr></a:lvl1pPr></p:otherStyle></p:txStyles>" +
        "</p:sldMaster>"

    private fun theme(accent: String): String {
        fun c(n: String, v: String) = "<a:$n><a:srgbClr val=\"$v\"/></a:$n>"
        val solid = "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>"
        return OoxmlPackage.XML_HEAD +
            "<a:theme xmlns:a=\"$A\" name=\"Nebula\"><a:themeElements><a:clrScheme name=\"Nebula\">" +
            "<a:dk1><a:srgbClr val=\"$INK\"/></a:dk1><a:lt1><a:srgbClr val=\"FFFFFF\"/></a:lt1>" +
            c("dk2", "3A3430") + c("lt2", "F3EFEB") + c("accent1", accent) + c("accent2", "F2A31B") + c("accent3", "7C5CFF") +
            c("accent4", "2BB3A3") + c("accent5", "E5487F") + c("accent6", "3D8BFD") + c("hlink", "3D8BFD") + c("folHlink", "7C5CFF") +
            "</a:clrScheme><a:fontScheme name=\"Nebula\"><a:majorFont><a:latin typeface=\"Aptos Display\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont>" +
            "<a:minorFont><a:latin typeface=\"Aptos\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme>" +
            "<a:fmtScheme name=\"Nebula\"><a:fillStyleLst>$solid$solid$solid</a:fillStyleLst>" +
            "<a:lnStyleLst><a:ln w=\"6350\">$solid</a:ln><a:ln w=\"12700\">$solid</a:ln><a:ln w=\"19050\">$solid</a:ln></a:lnStyleLst>" +
            "<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>" +
            "<a:bgFillStyleLst>$solid$solid$solid</a:bgFillStyleLst></a:fmtScheme></a:themeElements>" +
            "<a:objectDefaults/><a:extraClrSchemeLst/></a:theme>"
    }
}
