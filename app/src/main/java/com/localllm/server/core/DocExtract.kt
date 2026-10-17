package com.localllm.server.core

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * 文档文本抽取（纯本地，不依赖视觉模型）：
 * - PDF：PDFBox-Android 抽取文字（扫描件无文字层时返回空 → 提示改为 OCR/视觉）
 * - .docx：zip 内 word/document.xml → 去标签取段落文本
 * - .xlsx：zip 内 xl/sharedStrings.xml + 首个 sheet 的单元格值（粗略表格文本）
 * - .doc/.xls 旧二进制格式不支持。
 */
object DocExtract {

    private const val MAX_CHARS = 400_000 // 抽取上限，之后由聊天侧按 token 预算再截断

    /** 依据文件名/类型分发；不支持的返回 null */
    fun extract(bytes: ByteArray, fileName: String, mime: String): Pair<String, String?>? {
        val name = fileName.lowercase()
        val kind = when {
            name.endsWith(".pdf") || mime == "application/pdf" -> "pdf"
            name.endsWith(".docx") ||
                    mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
            name.endsWith(".xlsx") ||
                    mime == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
            else -> return null
        }
        return runCatching {
            val text = when (kind) {
                "pdf" -> extractPdf(bytes)
                "docx" -> extractDocx(bytes)
                else -> extractXlsx(bytes)
            }.take(MAX_CHARS)
            kind to text
        }.getOrNull()
    }

    private fun extractPdf(bytes: ByteArray): String {
        PDDocument.load(ByteArrayInputStream(bytes)).use { doc ->
            val stripper = PDFTextStripper()
            stripper.sortByPosition = true
            return stripper.getText(doc).trim()
        }
    }

    private fun extractDocx(bytes: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val xml = zip.readBytes().toString(Charsets.UTF_8)
                    return xmlToPlain(xml)
                }
                entry = zip.nextEntry
            }
        }
        return ""
    }

    private fun extractXlsx(bytes: ByteArray): String {
        val shared = StringBuilder()
        val sheet = StringBuilder()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when {
                    entry.name == "xl/sharedStrings.xml" ->
                        shared.append(xmlToPlain(zip.readBytes().toString(Charsets.UTF_8)))
                    entry.name == "xl/worksheets/sheet1.xml" ->
                        sheet.append(cellValues(zip.readBytes().toString(Charsets.UTF_8)))
                }
                entry = zip.nextEntry
            }
        }
        val sb = StringBuilder()
        if (shared.isNotEmpty()) sb.append("【共享文本】\n").append(shared).append('\n')
        if (sheet.isNotEmpty()) sb.append("【工作表1】\n").append(sheet)
        return sb.toString().trim()
    }

    /** docx/xlsx 的 XML → 纯文本：段落/行结束换行，去除标签与实体 */
    private fun xmlToPlain(xml: String): String =
        xml.replace(Regex("</w:p>"), "\n")
            .replace(Regex("</a:p>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .replace(Regex("[ \t]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    /** xlsx 单元格值：<c r="A1"><v>文本</v></c> 或 t="s" 的索引 → 粗略行文本 */
    private fun cellValues(xml: String): String {
        val sb = StringBuilder()
        Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { row ->
            val cells = Regex("<c[^>]*>(.*?)</c>", RegexOption.DOT_MATCHES_ALL)
                .findAll(row.groupValues[1])
                .map { c ->
                    val inner = c.groupValues[1]
                    Regex("<v>(.*?)</v>").find(inner)?.groupValues?.get(1)
                        ?: Regex("<t[^>]*>(.*?)</t>").find(inner)?.groupValues?.get(1) ?: ""
                }
                .filter { it.isNotBlank() }
                .toList()
            if (cells.isNotEmpty()) sb.append(cells.joinToString(" | ")).append('\n')
        }
        return sb.toString()
    }
}
