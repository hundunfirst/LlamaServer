package com.localllm.server.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 用现场合成的最小 OOXML 包验证抽取结果。
 * 断言的是「模型真正会看到什么」：标题层级、表格行列、共享字符串、日期、公式、备注。
 */
class DocExtractTest {

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            entries.forEach { (name, content) ->
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    // ---------------- docx ----------------

    private fun docx(body: String): ByteArray = zip(
        "word/document.xml" to
                "<w:document xmlns:w=\"x\"><w:body>$body</w:body></w:document>"
    )

    @Test
    fun `docx 还原标题层级与表格`() {
        val body = """
            <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>季度报告</w:t></w:r></w:p>
            <w:p><w:r><w:t>营收同比</w:t></w:r><w:r><w:t>增长 12%</w:t></w:r></w:p>
            <w:tbl>
              <w:tr><w:tc><w:p><w:r><w:t>项目</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>金额</w:t></w:r></w:p></w:tc></w:tr>
              <w:tr><w:tc><w:p><w:r><w:t>广告收入</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>1200</w:t></w:r></w:p></w:tc></w:tr>
            </w:tbl>
            <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/></w:numPr></w:pPr><w:r><w:t>待办一</w:t></w:r></w:p>
        """.trimIndent()
        val r = DocExtract.extract(docx(body), "报告.docx", "")
        assertNotNull(r)
        val t = r!!.text
        assertTrue(t, t.contains("# 季度报告"))       // Heading1 → #
        assertTrue(t, t.contains("营收同比增长 12%"))  // 同一段多个 run 要拼接
        assertTrue(t, t.contains("| 项目 | 金额 |"))
        assertTrue(t, t.contains("| --- | --- |"))
        assertTrue(t, t.contains("| 广告收入 | 1200 |"))
        assertTrue(t, t.contains("- 待办一"))         // 列表项
    }

    // ---------------- xlsx ----------------

    private fun xlsx(): ByteArray = zip(
        "xl/workbook.xml" to """
            <workbook xmlns:r="r"><sheets>
              <sheet name="销售" sheetId="1" r:id="rId1"/>
              <sheet name="汇总" sheetId="2" r:id="rId2"/>
            </sheets></workbook>
        """.trimIndent(),
        "xl/_rels/workbook.xml.rels" to """
            <Relationships>
              <Relationship Id="rId1" Target="worksheets/sheet1.xml"/>
              <Relationship Id="rId2" Target="/xl/worksheets/sheet2.xml"/>
            </Relationships>
        """.trimIndent(),
        "xl/sharedStrings.xml" to
                "<sst><si><t>产品</t></si><si><t>销量</t></si></sst>",
        "xl/styles.xml" to
                "<styleSheet><cellXfs count=\"2\"><xf numFmtId=\"0\"/><xf numFmtId=\"14\"/></cellXfs></styleSheet>",
        "xl/worksheets/sheet1.xml" to """
            <worksheet><sheetData>
              <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
              <row r="2"><c r="A2" t="inlineStr"><is><t>内联值</t></is></c><c r="B2"><v>42</v></c></row>
              <row r="3"><c r="A3" s="1"><v>45000</v></c><c r="B3"><f>SUM(B2:B3)</f></c></row>
            </sheetData></worksheet>
        """.trimIndent(),
        "xl/worksheets/sheet2.xml" to
                "<worksheet><sheetData><row r=\"1\"><c r=\"A1\"><v>99</v></c></row></sheetData></worksheet>"
    )

    @Test
    fun `xlsx 读全部 sheet 且共享字符串要还原成文本`() {
        val r = DocExtract.extract(xlsx(), "报表.xlsx", "")
        val t = r!!.text
        assertTrue(t, t.contains("## 工作表：销售"))
        assertTrue(t, t.contains("## 工作表：汇总"))   // 以前只读第一个 sheet
        assertTrue(t, t.contains("| 产品 | 销量 |"))   // 以前这里是 0 / 1 这种索引
        assertTrue(t, t.contains("内联值"))
        assertTrue(t, t.contains("42"))
        assertFalse("不该把共享字符串索引当值吐给模型", t.contains("| 0 | 1 |"))
    }

    @Test
    fun `xlsx 日期序列号还原成日期、无缓存值的公式给公式本身`() {
        val t = DocExtract.extract(xlsx(), "报表.xlsx", "")!!.text
        assertTrue(t, t.contains("2023-03-15"))
        assertTrue(t, t.contains("=SUM(B2:B3)"))
    }

    // ---------------- pptx ----------------

    @Test
    fun `pptx 抽幻灯片文字与备注`() {
        val bytes = zip(
            "ppt/presentation.xml" to "<p:presentation/>",
            "ppt/slides/slide1.xml" to
                    "<p:sld><a:p><a:r><a:t>第一页标题</a:t></a:r></a:p>" +
                    "<a:p><a:r><a:t>要点一</a:t></a:r></a:p></p:sld>",
            "ppt/notesSlides/notesSlide1.xml" to
                    "<p:notes><a:p><a:r><a:t>备注内容</a:t></a:r></a:p></p:notes>"
        )
        val t = DocExtract.extract(bytes, "演示.pptx", "")!!.text
        assertTrue(t, t.contains("## 幻灯片 1"))
        assertTrue(t, t.contains("第一页标题"))
        assertTrue(t, t.contains("要点一"))
        assertTrue(t, t.contains("备注内容"))
    }

    // ---------------- 旧格式 / rtf / 嗅探 ----------------

    @Test
    fun `旧版 doc 二进制给出可执行的提示`() {
        val ole = ByteArray(64).also {
            it[0] = 0xD0.toByte(); it[1] = 0xCF.toByte(); it[2] = 0x11.toByte(); it[3] = 0xE0.toByte()
        }
        val r = DocExtract.extract(ole, "旧文档.doc", "")
        assertNotNull(r)
        assertTrue(r!!.note!!, r.note!!.contains("另存为"))
    }

    @Test
    fun `rtf 去掉字体表只留正文`() {
        // 真 RTF 里中文是 \uN 加一个回退字符（通常是 ?）；字体表整组要丢掉
        val rtf = "{\\rtf1\\ansi{\\fonttbl{\\f0 Arial;}}\\f0 " +
                "\\u20320?\\u22909?\\par \\u31532?\\u20108?\\u34892?}"
        val t = DocExtract.extract(rtf.toByteArray(Charsets.ISO_8859_1), "a.rtf", "")!!.text
        assertTrue(t, t.contains("你好"))
        assertTrue(t, t.contains("第二行"))
        assertFalse("回退字符不该一起读出来", t.contains("??"))
        assertFalse("字体表不该出现在正文里", t.contains("Arial"))
    }

    @Test
    fun `改过后缀的 docx 仍按文件头走 docx`() {
        val body = "<w:p><w:r><w:t>内容是 docx</w:t></w:r></w:p>"
        val t = DocExtract.extract(docx(body), "伪装.dat", "")!!.text
        assertTrue(t, t.contains("内容是 docx"))
    }

    @Test
    fun `纯文本不当文档处理`() {
        assertFalse(DocExtract.isDocument("notes.txt", "text/plain"))
        assertTrue(DocExtract.isDocument("a.docx", ""))
        assertTrue(DocExtract.isDocument("a.xlsx", ""))
        assertTrue(DocExtract.isDocument("a.pptx", ""))
        assertTrue(DocExtract.isDocument("a.pdf", ""))
        assertNull(DocExtract.extract("hello".toByteArray(), "notes.txt", "text/plain"))
    }
}
