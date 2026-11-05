package com.localllm.server.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片格式判定：口径是「本地引擎能不能读」，不是「手机能不能显示」。
 * 踩过的坑：WebP 在手机上显示正常，直传给 llama.cpp 却整条请求 400。
 */
class ImageFormatsTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun riffWebp() = "RIFF".toByteArray() +
            byteArrayOf(0x14, 0x09, 0x01, 0x00) + "WEBPVP8 ".toByteArray()

    @Test
    fun `认出常见格式`() {
        assertEquals("jpeg", ImageFormats.magic(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0, 0, 0, 0, 0, 0, 0)))
        assertEquals("png", ImageFormats.magic(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)))
        assertEquals("gif", ImageFormats.magic("GIF89a000000".toByteArray()))
        assertEquals("webp", ImageFormats.magic(riffWebp()))
    }

    @Test
    fun `webp 与 avif 不能直传`() {
        assertFalse(ImageFormats.serverReadable(riffWebp()))
        assertFalse(ImageFormats.serverReadable("AVIF00000000".toByteArray()))
        assertNull(ImageFormats.magic("hello world!".toByteArray()))
        assertFalse(ImageFormats.serverReadable("hello world!".toByteArray()))
    }

    @Test
    fun `jpg 和 png 可以直传`() {
        assertTrue(ImageFormats.serverReadable(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0, 0, 0, 0, 0, 0, 0)))
        assertTrue(ImageFormats.serverReadable(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)))
    }

    @Test
    fun `base64 头能认出格式`() {
        assertEquals("jpeg", ImageFormats.formatOfB64("/9j/4AAQSkZJRg"))
        assertEquals("png", ImageFormats.formatOfB64("iVBORw0KGgo"))
        assertEquals("webp", ImageFormats.formatOfB64("UklGRhQJAQBXRUJQVlA4"))
        assertNull(ImageFormats.formatOfB64("AAAAAAAA"))
        assertEquals("image/webp", ImageFormats.mimeOfB64("UklGRhQJAQBXRUJQVlA4"))
        // 认不出时按 jpeg 标注：服务端至少给个明确的错，而不是空 mime
        assertEquals("image/jpeg", ImageFormats.mimeOfB64("AAAA"))
    }
}
