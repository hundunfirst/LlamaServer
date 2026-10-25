package com.localllm.server.core

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.util.Calendar
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

/**
 * 本地文档文本抽取（纯本地，不联网、不依赖视觉模型）。
 *
 * 目标是「读懂文件」而不是「把字节变成字」，所以按格式保住结构：
 * - PDF：逐页抽文字并标出页码（可按页引用）；扫描件无文字层时明确提示换 OCR；
 * - .docx：段落按 Heading 样式还原成 markdown 标题、列表项加 `- `、表格还原成 markdown 表格；
 * - .xlsx：**所有 sheet**（按 workbook 顺序）、真实单元格值（共享字符串 / 内联 / 布尔 / 错误值）、
 *   公式（有缓存值时同时给出）、日期序列号还原成日期、按列对齐；
 * - .pptx：逐页幻灯片文字 + 表格 + 备注；
 * - .rtf：去掉控制字与字体/颜色表，保留段落与表格分隔；
 * - .doc / .xls / .ppt（Word 97-2003 那批二进制）：识别得出来但解析不了，给出可执行的提示。
 *
 * 判定格式**看文件头而不是扩展名**：改过后缀的 docx、伪装成 .docx 的老 .doc 都能落对分支。
 */
object DocExtract {

    /** [kind] 见 [kindOf]；[note] 是给用户/模型的补充说明（截断、无文字层、解析不了的原因） */
    data class Result(val kind: String, val text: String, val note: String? = null)

    private const val MAX_CHARS = 400_000
    private const val MAX_XLSX_ROWS = 2000
    private const val MAX_XLSX_COLS = 100
    private const val MAX_PDF_PAGES = 300

    private const val OLE_HINT = "这是 Word 97-2003 / Excel 97-2003 的旧二进制格式，本地没有解析器：" +
            "请在 Office 或 WPS 里另存为 .docx / .xlsx 再发。"

    // ---------------- 入口 ----------------

    /** 是不是需要抽取的文档格式（按扩展名/mime 判断；纯文本返回 false，调用方自己读） */
    fun isDocument(fileName: String, mime: String): Boolean = kindOf(fileName, mime) != null

    /** 抽取文本；不是它能处理的格式返回 null，调用方按纯文本处理 */
    fun extract(bytes: ByteArray, fileName: String, mime: String): Result? {
        if (bytes.isEmpty()) return null
        // 文件头优先：老 .doc 改成 .docx、docx 改成 .dat 都很常见
        if (isOle2(bytes)) {
            val kind = kindOf(fileName, mime) ?: "ole"
            return Result(kind, "", OLE_HINT)
        }
        if (startsWith(bytes, "%PDF")) return pdfResult(bytes)
        if (isZip(bytes)) return zipResult(bytes)
        if (startsWith(bytes, "{\\rtf")) return Result("rtf", rtfToText(String(bytes, Charsets.ISO_8859_1)))
        return when (kindOf(fileName, mime)) {
            "pdf" -> runCatching { pdfResult(bytes) }.getOrElse {
                Result("pdf", "", "PDF 解析失败：${it.message ?: "文件可能损坏"}")
            }
            "ole" -> Result("ole", "", OLE_HINT)
            "rtf" -> Result("rtf", rtfToText(String(bytes, Charsets.ISO_8859_1)))
            else -> null
        }
    }

    private fun kindOf(fileName: String, mime: String): String? {
        val n = fileName.lowercase(Locale.ROOT)
        val m = mime.lowercase(Locale.ROOT)
        return when {
            n.endsWith(".pdf") || m == "application/pdf" -> "pdf"
            n.endsWith(".docx") || m.endsWith("wordprocessingml.document") -> "docx"
            n.endsWith(".xlsx") || m.endsWith("spreadsheetml.sheet") -> "xlsx"
            n.endsWith(".pptx") || m.endsWith("presentationml.presentation") -> "pptx"
            n.endsWith(".rtf") || m == "application/rtf" || m == "text/rtf" -> "rtf"
            n.endsWith(".doc") || n.endsWith(".xls") || n.endsWith(".ppt") -> "ole"
            else -> null
        }
    }

    private fun isOle2(b: ByteArray): Boolean =
        b.size > 8 && b[0] == 0xD0.toByte() && b[1] == 0xCF.toByte() &&
                b[2] == 0x11.toByte() && b[3] == 0xE0.toByte()

    private fun isZip(b: ByteArray): Boolean =
        b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() &&
                b[2] == 3.toByte() && b[3] == 4.toByte()

    private fun startsWith(b: ByteArray, s: String): Boolean {
        val p = s.toByteArray(Charsets.ISO_8859_1)
        if (b.size < p.size) return false
        return p.indices.all { b[it] == p[it] }
    }

    // ---------------- zip 家族的公共读取 ----------------

    /** 把需要的条目一次读进内存（ZipInputStream 只能顺序走一遍，而 workbook 与 sheet 的先后不定） */
    private fun zipEntries(bytes: ByteArray, keep: (String) -> Boolean): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory && keep(e.name)) {
                    runCatching { out[e.name] = zip.readBytes() }
                }
                e = zip.nextEntry
            }
        }
        return out
    }

    private fun Map<String, ByteArray>.text(name: String): String =
        this[name]?.toString(Charsets.UTF_8) ?: ""

    private fun escape(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")

    private fun zipResult(bytes: ByteArray): Result {
        return runCatching {
            // 先只看条目名决定是哪一种 Office 文件
            val names = LinkedHashSet<String>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    names.add(e.name)
                    e = zip.nextEntry
                }
            }
            when {
                "word/document.xml" in names -> docxResult(bytes)
                "xl/workbook.xml" in names -> xlsxResult(bytes)
                "ppt/presentation.xml" in names -> pptxResult(bytes)
                else -> Result("zip", "", "压缩包里没有 Office 文档结构（可能是普通 zip 或已损坏）")
            }
        }.getOrElse { Result("zip", "", "解析失败：${it.message ?: "压缩包可能损坏"}") }
    }

    // ---------------- docx ----------------

    private fun docxResult(bytes: ByteArray): Result {
        val xml = zipEntries(bytes) { it == "word/document.xml" }.text("word/document.xml")
        if (xml.isBlank()) return Result("docx", "", "文档里没有 word/document.xml")
        val body = xml.substringAfter("<w:body>", xml)
        val sb = StringBuilder()
        // 一段一段按文档顺序走：段落和表格要保序，不能先抽完段落再拼表格
        var i = 0
        while (i < body.length) {
            val p = body.indexOf('<', i)
            if (p < 0) break
            when {
                body.startsWith("<w:tbl>", p) || body.startsWith("<w:tbl ", p) -> {
                    val end = body.indexOf("</w:tbl>", p)
                    val raw = if (end < 0) body.substring(p) else body.substring(p, end + 8)
                    sb.append(docxTable(raw)).append('\n')
                    i = if (end < 0) body.length else end + 8
                }
                body.startsWith("<w:p>", p) || body.startsWith("<w:p ", p) -> {
                    val end = body.indexOf("</w:p>", p)
                    val raw = if (end < 0) body.substring(p) else body.substring(p, end + 6)
                    val line = docxParagraph(raw)
                    if (line.isNotBlank()) sb.append(line).append('\n')
                    i = if (end < 0) body.length else end + 6
                }
                else -> i = p + 1
            }
        }
        val text = sb.toString().replace(Regex("\n{3,}"), "\n\n").trim().take(MAX_CHARS)
        return Result("docx", text, if (text.isBlank()) "文档里没有可提取的文字" else null)
    }

    private fun docxParagraph(raw: String): String {
        val body = raw.replace(Regex("<w:tab[^>]*/>"), "\t")
            .replace(Regex("<w:br[^>]*/>"), "\n")
        val text = escape(tagInnerTexts(body, "w:t")).trim()
        if (text.isEmpty()) return ""
        // 标题样式 → markdown 层级；列表项 → "- "
        val style = Regex("<w:pStyle w:val=\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: ""
        val heading = Regex("(?i)^heading\\s*([1-6])$").find(style)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("(?i)^标题\\s*([1-6])$").find(style)?.groupValues?.get(1)?.toIntOrNull()
        if (heading != null) return "#".repeat(heading) + " " + text
        val numbered = Regex("<w:numPr>").containsMatchIn(body)
        return if (numbered) "- $text" else text
    }

    private fun docxTable(raw: String): String {
        val rows = Regex("<w:tr[ >].*?</w:tr>", RegexOption.DOT_MATCHES_ALL)
            .findAll(raw).map { it.value }.toList()
        if (rows.isEmpty()) return ""
        val sb = StringBuilder()
        rows.forEachIndexed { idx, row ->
            val cells = Regex("<w:tc>.*?</w:tc>", RegexOption.DOT_MATCHES_ALL)
                .findAll(row).map { cell ->
                    escape(tagInnerTexts(cell.value, "w:t"))
                        .replace(Regex("\\s+"), " ").trim().ifEmpty { " " }
                }.toList()
            if (cells.isEmpty()) return@forEachIndexed
            sb.append("| ").append(cells.joinToString(" | ")).append(" |\n")
            if (idx == 0) {
                sb.append("|").append(" --- |".repeat(cells.size)).append('\n')
            }
        }
        return sb.toString().trimEnd()
    }

    /** 取某个前缀下所有 <tag>文字</tag> 拼起来（同一段里可能有多个 run） */
    private fun tagInnerTexts(xml: String, tag: String): String =
        Regex("<$tag(?:\\s[^>]*)?>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { it.groupValues[1] }.joinToString("")

    // ---------------- xlsx ----------------

    private fun xlsxResult(bytes: ByteArray): Result {
        val entries = zipEntries(bytes) { name ->
            name == "xl/workbook.xml" || name == "xl/_rels/workbook.xml.rels" ||
                    name == "xl/sharedStrings.xml" || name == "xl/styles.xml" ||
                    (name.startsWith("xl/worksheets/") && name.endsWith(".xml"))
        }
        val shared = parseSharedStrings(entries.text("xl/sharedStrings.xml"))
        val dateStyles = parseDateStyles(entries.text("xl/styles.xml"))
        val sheets = sheetOrder(entries)
        if (sheets.isEmpty()) return Result("xlsx", "", "工作簿里没有工作表")

        val sb = StringBuilder()
        var truncated = false
        sheets.forEach { (name, path) ->
            val xml = entries.text(path)
            if (xml.isBlank()) return@forEach
            val (table, cut) = sheetToMarkdown(xml, shared, dateStyles)
            if (cut) truncated = true
            if (table.isNotBlank()) {
                sb.append("## 工作表：").append(name).append('\n').append(table).append("\n\n")
            }
            if (sb.length > MAX_CHARS) return@forEach
        }
        val text = sb.toString().trim().take(MAX_CHARS)
        val note = when {
            text.isBlank() -> "工作簿里没有可提取的单元格内容"
            truncated -> "表格较大，每个工作表只取前 $MAX_XLSX_ROWS 行、前 $MAX_XLSX_COLS 列"
            else -> null
        }
        return Result("xlsx", text, note)
    }

    /** workbook.xml 的 sheet 顺序 + rels 的路径映射；解析不出来就按文件名排序兜底 */
    private fun sheetOrder(entries: Map<String, ByteArray>): List<Pair<String, String>> {
        val wb = entries.text("xl/workbook.xml")
        val rels = entries.text("xl/_rels/workbook.xml.rels")
        val relMap = HashMap<String, String>()
        Regex("<Relationship[^>]*>").findAll(rels).forEach { m ->
            val tag = m.value
            val id = Regex("Id=\"([^\"]+)\"").find(tag)?.groupValues?.get(1) ?: return@forEach
            val target = Regex("Target=\"([^\"]+)\"").find(tag)?.groupValues?.get(1) ?: return@forEach
            relMap[id] = target.removePrefix("/").let {
                if (it.startsWith("xl/")) it else "xl/" + it
            }
        }
        val out = ArrayList<Pair<String, String>>()
        Regex("<sheet[^>]*>").findAll(wb).forEach { m ->
            val tag = m.value
            val name = escape(Regex("name=\"([^\"]*)\"").find(tag)?.groupValues?.get(1) ?: "Sheet")
            val rid = Regex("r:id=\"([^\"]+)\"").find(tag)?.groupValues?.get(1)
            val path = rid?.let { relMap[it] } ?: ""
            if (path.isNotEmpty() && entries.containsKey(path)) out.add(name to path)
        }
        if (out.isNotEmpty()) return out
        return entries.keys.filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }
            .sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
            .map { "Sheet" to it }
    }

    /** 共享字符串表：每个 <si> 里可能有多段 <t>（富文本 run） */
    private fun parseSharedStrings(xml: String): List<String> {
        if (xml.isBlank()) return emptyList()
        return Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .map { escape(tagInnerTexts(it.groupValues[1], "t")) }
            .toList()
    }

    /** 日期判据：内置 numFmtId 14–22 / 45–47，或自定义格式串里出现日期码 */
    private fun parseDateStyles(xml: String): Set<Int> {
        if (xml.isBlank()) return emptySet()
        val custom = HashMap<Int, String>()
        Regex("<numFmt[^>]*numFmtId=\"(\\d+)\"[^>]*formatCode=\"([^\"]*)\"").findAll(xml)
            .forEach { custom[it.groupValues[1].toIntOrNull() ?: -1] = it.groupValues[2] }
        val cellXfs = xml.substringAfter("<cellXfs", "")
        val out = HashSet<Int>()
        Regex("<xf[^>]*>").findAll(cellXfs).forEachIndexed { idx, m ->
            val id = Regex("numFmtId=\"(\\d+)\"").find(m.value)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@forEachIndexed
            val isDate = id in 14..22 || id in 45..47 ||
                    (custom[id]?.let { code ->
                        Regex("[ymdhs]", RegexOption.IGNORE_CASE).containsMatchIn(
                            code.replace(Regex("\"[^\"]*\""), "")
                        )
                    } == true)
            if (isDate) out.add(idx)
        }
        return out
    }

    private fun sheetToMarkdown(
        xml: String,
        shared: List<String>,
        dateStyles: Set<Int>
    ): Pair<String, Boolean> {
        var cutOff = false
        val rows = ArrayList<List<String>>()
        Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { m ->
            if (rows.size > MAX_XLSX_ROWS) { cutOff = true; return@forEach }
            val cells = HashMap<Int, String>()
            Regex("<c\\s[^>]*/>|<c\\s[^>]*>.*?</c>", RegexOption.DOT_MATCHES_ALL)
                .findAll(m.groupValues[1]).forEach { cm ->
                    val tag = cm.value
                    val ref = Regex("r=\"([A-Z]+)\\d+\"").find(tag)?.groupValues?.get(1)
                    val col = ref?.let(::columnIndex) ?: cells.size
                    val type = Regex("\\st=\"([^\"]+)\"").find(tag)?.groupValues?.get(1) ?: ""
                    val styleIdx = Regex("\\ss=\"(\\d+)\"").find(tag)?.groupValues?.get(1)?.toIntOrNull()
                    val inner = cm.value
                    var value = when (type) {
                        "s" -> Regex("<v>(\\d+)</v>").find(inner)?.groupValues?.get(1)?.toIntOrNull()
                            ?.let { shared.getOrNull(it) } ?: ""
                        "inlineStr" -> escape(tagInnerTexts(inner, "t"))
                        "b" -> if (Regex("<v>1</v>").containsMatchIn(inner)) "TRUE" else "FALSE"
                        "e" -> Regex("<v>(.*?)</v>").find(inner)?.groupValues?.get(1) ?: "#ERR"
                        else -> Regex("<v>(.*?)</v>").find(inner)?.groupValues?.get(1)?.let(::escape) ?: ""
                    }
                    if (value.isEmpty()) {
                        // 没有缓存值但有公式：把公式本身给出来，比留空有用
                        val f = Regex("<f[^>]*>(.*?)</f>", RegexOption.DOT_MATCHES_ALL)
                            .find(inner)?.groupValues?.get(1)?.let(::escape)
                        if (!f.isNullOrBlank()) value = "=$f"
                    }
                    if (value.isNotEmpty() && styleIdx != null && styleIdx in dateStyles) {
                        value = excelSerialToText(value) ?: value
                    }
                    if (value.isNotEmpty()) cells[col] = value.replace("|", "\\|")
                }
            if (cells.isEmpty()) return@forEach
            val maxCol = minOf(cells.keys.maxOrNull() ?: 0, MAX_XLSX_COLS - 1)
            if ((cells.keys.maxOrNull() ?: 0) > maxCol) cutOff = true
            rows.add((0..maxCol).map { cells[it] ?: " " })
        }
        if (rows.isEmpty()) return "" to cutOff
        val width = rows.maxOf { it.size }
        val sb = StringBuilder()
        rows.forEachIndexed { idx, r ->
            val padded = r + List(width - r.size) { " " }
            sb.append("| ").append(padded.joinToString(" | ")).append(" |\n")
            if (idx == 0) sb.append("|").append(" --- |".repeat(width)).append('\n')
        }
        return sb.toString().trimEnd() to cutOff
    }

    /** "AB" → 27（0 基） */
    private fun columnIndex(letters: String): Int {
        var n = 0
        for (c in letters) n = n * 26 + (c.uppercaseChar() - 'A' + 1)
        return n - 1
    }

    /** Excel 序列号（1899-12-30 起）→ 日期/日期时间文本 */
    private fun excelSerialToText(raw: String): String? {
        val d = raw.toDoubleOrNull() ?: return null
        if (d < 1 || d > 2_958_466) return null
        val days = d.toLong()
        val cal = Calendar.getInstance(Locale.ROOT)
        cal.clear()
        cal.set(1899, Calendar.DECEMBER, 30, 0, 0, 0)
        cal.add(Calendar.DAY_OF_YEAR, days.toInt())
        val frac = d - days
        val hhmm = (frac * 24 * 60).roundToInt()
        val pattern = if (hhmm == 0) "yyyy-MM-dd" else "yyyy-MM-dd HH:mm"
        if (hhmm != 0) cal.add(Calendar.MINUTE, hhmm)
        return java.text.SimpleDateFormat(pattern, Locale.ROOT).format(cal.time)
    }

    // ---------------- pptx ----------------

    private fun pptxResult(bytes: ByteArray): Result {
        val entries = zipEntries(bytes) {
            (it.startsWith("ppt/slides/slide") && it.endsWith(".xml")) ||
                    (it.startsWith("ppt/notesSlides/notesSlide") && it.endsWith(".xml"))
        }
        val slides = entries.keys.filter { it.startsWith("ppt/slides/slide") }
            .sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        if (slides.isEmpty()) return Result("pptx", "", "演示文稿里没有幻灯片")
        val sb = StringBuilder()
        slides.forEachIndexed { idx, path ->
            sb.append("## 幻灯片 ").append(idx + 1).append('\n')
            val xml = entries.text(path)
            val lines = Regex("<a:p>.*?</a:p>", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml).map { escape(tagInnerTexts(it.value, "a:t")).trim() }
                .filter { it.isNotEmpty() }
                .toList()
            if (lines.isNotEmpty()) sb.append(lines.joinToString("\n"))
            val tables = Regex("<a:tbl>.*?</a:tbl>", RegexOption.DOT_MATCHES_ALL).findAll(xml).toList()
            tables.forEach { t ->
                Regex("<a:tr[ >].*?</a:tr>", RegexOption.DOT_MATCHES_ALL).findAll(t.value).forEach { row ->
                    val cells = Regex("<a:tc[ >].*?</a:tc>", RegexOption.DOT_MATCHES_ALL)
                        .findAll(row.value).map { escape(tagInnerTexts(it.value, "a:t")).trim() }
                        .toList()
                    if (cells.any { it.isNotBlank() }) {
                        sb.append("\n| ").append(cells.joinToString(" | ")).append(" |")
                    }
                }
            }
            // 备注：notesSlideN 与 slideN 序号对应（不保证严格，取同名序号）
            val num = path.filter(Char::isDigit)
            val notes = entries.text("ppt/notesSlides/notesSlide$num.xml")
            val noteLines = Regex("<a:p>.*?</a:p>", RegexOption.DOT_MATCHES_ALL)
                .findAll(notes).map { escape(tagInnerTexts(it.value, "a:t")).trim() }
                .filter { it.isNotEmpty() && it.toIntOrNull() == null }
                .toList()
            if (noteLines.isNotEmpty()) {
                sb.append("\n\n**备注**：").append(noteLines.joinToString(" "))
            }
            sb.append("\n\n")
            if (sb.length > MAX_CHARS) return@forEachIndexed
        }
        val text = sb.toString().trim().take(MAX_CHARS)
        return Result("pptx", text, null)
    }

    // ---------------- pdf ----------------

    private fun pdfResult(bytes: ByteArray): Result {
        PDDocument.load(ByteArrayInputStream(bytes)).use { doc ->
            val pages = doc.numberOfPages
            val stripper = PDFTextStripper()
            stripper.sortByPosition = true
            val sb = StringBuilder()
            val limit = minOf(pages, MAX_PDF_PAGES)
            for (p in 1..limit) {
                stripper.startPage = p
                stripper.endPage = p
                val t = runCatching { stripper.getText(doc) }.getOrNull()?.trim()
                if (!t.isNullOrEmpty()) sb.append("— 第 ").append(p).append(" 页 —\n").append(t).append("\n\n")
                if (sb.length > MAX_CHARS) break
            }
            val text = sb.toString().take(MAX_CHARS).trim()
            val note = when {
                text.isBlank() -> "PDF 没有文字层（可能是扫描件）：可以截图后走 OCR，或改用视觉模型"
                pages > limit -> "共 $pages 页，只抽取了前 $limit 页"
                else -> null
            }
            return Result("pdf", text, note)
        }
    }

    // ---------------- rtf ----------------

    private val RTF_DESTINATIONS = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer",
        "object", "generator", "listtable", "rsidtbl", "xmlnstbl"
    )

    /** RTF → 纯文本：去掉控制字/目标组，保留段落、制表、表格分隔 */
    private fun rtfToText(raw: String): String {
        val out = StringBuilder()
        var i = 0
        var depth = 0
        var skipDepth = -1
        val ctrl = Regex("^([a-zA-Z]+)(-?\\d+)? ?")
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '{' -> { depth++; i++ }
                c == '}' -> { if (skipDepth == depth) skipDepth = -1; depth--; i++ }
                c == '\\' -> {
                    if (i + 1 >= raw.length) { i++; continue }
                    val next = raw[i + 1]
                    when {
                        next == '*' -> { skipDepth = depth; i += 2 }
                        next == '\'' -> i += 4 // \'hh：单字节字形，跳过
                        next == 'u' -> {
                            val m = Regex("^u(-?\\d+) ?").find(raw.substring(i + 1))
                            if (m == null) i += 2 else {
                                val code = m.groupValues[1].toIntOrNull() ?: 0
                                if (skipDepth < 0 && code > 0) out.append(code.toChar())
                                i += 1 + m.value.length
                                // RTF 的 \uN 后面跟着一个「不支持 Unicode 时的回退字符」，
                                // 中文文档里通常是 ?，不跳过就会读成「你好??」
                                if (i < raw.length && raw[i] != '\\' && raw[i] != '{' && raw[i] != '}') i++
                            }
                        }
                        next == '\\' || next == '{' || next == '}' -> {
                            if (skipDepth < 0) out.append(next)
                            i += 2
                        }
                        else -> {
                            val m = ctrl.find(raw.substring(i + 1))
                            if (m == null) i += 2 else {
                                val word = m.groupValues[1].lowercase(Locale.ROOT)
                                val num = m.groupValues[2].toIntOrNull() ?: 0
                                when {
                                    word in RTF_DESTINATIONS -> skipDepth = depth
                                    skipDepth >= 0 -> Unit
                                    word == "par" || word == "line" -> out.append('\n')
                                    word == "tab" -> out.append('\t')
                                    word == "cell" -> out.append(" | ")
                                    word == "row" -> out.append('\n')
                                }
                                i += 1 + m.value.length
                                if (word == "bin") i += num
                            }
                        }
                    }
                }
                c == '\r' || c == '\n' -> i++
                else -> { if (skipDepth < 0) out.append(c); i++ }
            }
        }
        return out.toString()
            .replace(Regex("[ \t]{2,}"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
