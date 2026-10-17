package com.localllm.server.core

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 文档生成：
 * - make_xlsx：生成真正的 .xlsx（最小 OOXML：内联字符串，单工作表）
 * - make_pdf：用系统 PdfDocument 渲染文本（自动换行 + 分页，无外部依赖）
 * 输出写入用户授权的工作目录（与文件工具共用路径解析）。
 */
object DocMaker {

    const val TOOL_XLSX = "make_xlsx"
    const val TOOL_PDF = "make_pdf"

    val NAMES = setOf(TOOL_XLSX, TOOL_PDF)

    private const val MAX_ROWS = 2000
    private const val MAX_COL_CHARS = 300
    private const val MAX_PDF_CHARS = 20_000

    fun toolsJson(): org.json.JSONArray {
        fun fn(name: String, desc: String, props: org.json.JSONObject, required: List<String>) =
            org.json.JSONObject()
                .put("type", "function")
                .put(
                    "function", org.json.JSONObject()
                        .put("name", name)
                        .put("description", desc)
                        .put(
                            "parameters", org.json.JSONObject()
                                .put("type", "object")
                                .put("properties", props)
                                .put("required", org.json.JSONArray().apply { required.forEach { put(it) } })
                        )
                )

        val pathProp = org.json.JSONObject().put("type", "string")
            .put("description", "相对工作目录的文件路径，如 report.xlsx")

        return org.json.JSONArray().apply {
            put(
                fn(
                    TOOL_XLSX, "把二维数组写成 .xlsx 表格文件（第一行通常表头）",
                    org.json.JSONObject()
                        .put("path", pathProp)
                        .put(
                            "rows", org.json.JSONObject().put("type", "array")
                                .put("description", "二维数组：[[\"日期\",\"收入\"],[\"2026-07-01\",\"5858.40\"]]")
                                .put(
                                    "items", org.json.JSONObject().put("type", "array")
                                        .put("items", org.json.JSONObject().put("type", "string"))
                                )
                        ),
                    listOf("path", "rows")
                )
            )
            put(
                fn(
                    TOOL_PDF, "把纯文本写成 .pdf（自动换行分页，支持标题）",
                    org.json.JSONObject()
                        .put("path", pathProp)
                        .put("title", org.json.JSONObject().put("type", "string").put("description", "标题（可选）"))
                        .put(
                            "content", org.json.JSONObject().put("type", "string")
                                .put("description", "正文文本，用换行分段")
                        ),
                    listOf("path", "content")
                )
            )
        }
    }

    /** 执行并返回结果文本；失败抛 IllegalStateException */
    fun execute(context: Context, name: String, args: org.json.JSONObject): String {
        val path = args.optString("path")
        if (path.isBlank()) throw IllegalStateException("缺少 path 参数")
        return when (name) {
            TOOL_XLSX -> {
                val rowsArr = args.optJSONArray("rows")
                    ?: throw IllegalStateException("缺少 rows 参数")
                val rows = (0 until rowsArr.length()).mapNotNull { i ->
                    rowsArr.optJSONArray(i)?.let { r ->
                        (0 until r.length()).map { j -> r.optString(j).take(MAX_COL_CHARS) }
                    }
                }.take(MAX_ROWS)
                if (rows.isEmpty()) throw IllegalStateException("rows 为空")
                val bytes = buildXlsx(rows)
                openOutput(context, path, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .use { it.write(bytes) }
                "已生成 XLSX：$path（${rows.size} 行 × ${rows.maxOf { it.size }} 列）"
            }
            TOOL_PDF -> {
                val content = args.optString("content")
                if (content.isBlank()) throw IllegalStateException("缺少 content 参数")
                val title = args.optString("title")
                val bytes = buildPdf(title, content.take(MAX_PDF_CHARS))
                openOutput(context, path, "application/pdf").use { it.write(bytes) }
                "已生成 PDF：$path（${bytes.size} 字节）"
            }
            else -> throw IllegalStateException("未知文档工具：$name")
        }
    }

    // ---------------- 写文件（复用授权目录） ----------------
    private fun openOutput(context: Context, path: String, mime: String): java.io.OutputStream {
        val root = FileTools.rootDir(context)
            ?: throw IllegalStateException("尚未授权工作目录：请在 设置 → 文件工具 里选择文件夹")
        val segs = path.replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." }
        if (segs.isEmpty() || segs.any { it == ".." }) throw IllegalStateException("非法路径")
        var dir: DocumentFile = root
        for (d in segs.dropLast(1)) {
            val existing = dir.findFile(d)
            dir = if (existing != null && existing.isDirectory) existing
            else dir.createDirectory(d) ?: throw IllegalStateException("无法创建目录：$d")
        }
        val fileName = segs.last()
        val file = dir.findFile(fileName) ?: dir.createFile(mime, fileName)
            ?: throw IllegalStateException("无法创建文件：$fileName")
        return context.contentResolver.openOutputStream(file.uri, "wt")
            ?: throw IllegalStateException("无法写入文件")
    }

    // ---------------- 最小 XLSX ----------------
    private fun buildXlsx(rows: List<List<String>>): ByteArray {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        fun colName(idx: Int): String {
            var n = idx
            val sb = StringBuilder()
            while (true) {
                sb.insert(0, ('A' + n % 26))
                n = n / 26 - 1
                if (n < 0) break
            }
            return sb.toString()
        }
        val sheet = StringBuilder()
        sheet.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sheet.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        rows.forEachIndexed { r, row ->
            sheet.append("<row r=\"${r + 1}\">")
            row.forEachIndexed { c, cell ->
                if (cell.isNotEmpty()) {
                    sheet.append("<c r=\"${colName(c)}${r + 1}\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(esc(cell))
                        .append("</t></is></c>")
                }
            }
            sheet.append("</row>")
        }
        sheet.append("</sheetData></worksheet>")

        val workbook = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>"""

        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>"""

        val rootRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

        val wbRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>"""

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, data: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", contentTypes)
            put("_rels/.rels", rootRels)
            put("xl/workbook.xml", workbook)
            put("xl/_rels/workbook.xml.rels", wbRels)
            put("xl/worksheets/sheet1.xml", sheet.toString())
        }
        return out.toByteArray()
    }

    // ---------------- PDF ----------------
    private fun buildPdf(title: String, content: String): ByteArray {
        val pageW = 595
        val pageH = 842
        val margin = 36f
        val bodySize = 11f
        val lineH = 16f

        val body = Paint().apply {
            textSize = bodySize
            isAntiAlias = true
            color = android.graphics.Color.BLACK
        }
        val titlePaint = Paint().apply {
            textSize = 16f
            isFakeBoldText = true
            isAntiAlias = true
            color = android.graphics.Color.BLACK
        }

        fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
            val lines = mutableListOf<String>()
            for (raw in text.split('\n')) {
                if (raw.isEmpty()) {
                    lines.add("")
                    continue
                }
                var start = 0
                while (start < raw.length) {
                    val count = paint.breakText(raw, start, raw.length, true, maxWidth, null)
                    val n = if (count <= 0) 1 else count
                    lines.add(raw.substring(start, start + n))
                    start += n
                }
            }
            return lines
        }

        val doc = PdfDocument()
        var pageNum = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
        var canvas = page.canvas
        var y = margin + 16f

        if (title.isNotBlank()) {
            for (l in wrap(title, titlePaint, pageW - margin * 2)) {
                canvas.drawText(l, margin, y, titlePaint)
                y += 22f
            }
            y += 6f
        }

        for (line in wrap(content, body, pageW - margin * 2)) {
            if (y > pageH - margin) {
                doc.finishPage(page)
                pageNum++
                page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
                canvas = page.canvas
                y = margin + 12f
            }
            if (line.isNotEmpty()) canvas.drawText(line, margin, y, body)
            y += lineH
        }
        doc.finishPage(page)
        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }
}
