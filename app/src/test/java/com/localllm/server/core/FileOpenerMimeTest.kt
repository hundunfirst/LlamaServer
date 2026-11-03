package com.localllm.server.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 产物文件交给系统应用时用的 MIME。
 * 这里错了，用户点一下就是「无法打开」——APK 尤其明显：给成 octet-stream 只会弹"没有应用"。
 */
class FileOpenerMimeTest {

    @Test
    fun `apk 必须给安装器的 MIME`() {
        assertEquals("application/vnd.android.package-archive", FileOpener.mimeFor("HelloAPK.apk"))
        assertEquals("application/vnd.android.package-archive", FileOpener.mimeFor("X.APK"))
    }

    @Test
    fun `表格与文档`() {
        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            FileOpener.mimeFor("跑单记账_周统计.xlsx")
        )
        assertEquals("text/csv", FileOpener.mimeFor("daily.csv"))
        assertEquals("application/pdf", FileOpener.mimeFor("a.PDF"))
    }

    @Test
    fun `常见文本与图片`() {
        assertEquals("text/markdown", FileOpener.mimeFor("README.md"))
        assertEquals("text/html", FileOpener.mimeFor("index.html"))
        assertEquals("image/png", FileOpener.mimeFor("shot.png"))
    }

    @Test
    fun `没有后缀不抛异常`() {
        assertEquals("application/octet-stream", FileOpener.mimeFor("noext"))
        assertEquals("application/octet-stream", FileOpener.mimeFor(""))
    }
}
