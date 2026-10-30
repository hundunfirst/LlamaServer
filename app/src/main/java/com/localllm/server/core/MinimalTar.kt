package com.localllm.server.core

import java.io.File
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.zip.GZIPInputStream

/**
 * 极简 tar 解压器（Kotlin 纯实现，零依赖）。
 *
 * 为什么不用 zip：rootfs 里的 /bin、/lib、node 的 bin 全是符号链接，zip 表达不了；
 * tar 原生保存链接。Android/Java 标准库没有 tar，所以手写这 512 字节头的最小子集：
 * 普通文件(0/'0')、目录(5)、符号链接(2)、硬链接(1)、GNU 长文件名(L)、PAX 头(x)。
 *
 * 只处理我们信任的 tarball（ubuntu-base / node 官方包），不做安全扫描。
 */
object MinimalTar {

    private const val BLOCK = 512

    /** 建符号链接；可注入以便 JVM 单测（android.system.Os 在本地跑不了） */
    internal var symlinkImpl: (target: String, link: String) -> Boolean = { target, link ->
        runCatching { android.system.Os.symlink(target, link) }.isSuccess
    }

    /** 把（可能 gzip 的）tar 流解压到 [dest]。先嗅探 gzip 魔数再决定要不要解压 */
    fun extractGz(gz: InputStream, dest: File) {
        val peek = ByteArray(2)
        val n = gz.read(peek)
        val buf = java.io.ByteArrayInputStream(peek.copyOf(n))
        val stream: InputStream = if (n >= 2 && peek[0] == 0x1f.toByte() && peek[1] == 0x8b.toByte()) {
            GZIPInputStream(SequenceInputStream(buf, gz))
        } else {
            SequenceInputStream(buf, gz)
        }
        stream.use { extract(it, dest) }
    }

    fun extract(tar: InputStream, dest: File) {
        var pendingName: String? = null
        while (true) {
            val header = ByteArray(BLOCK)
            val read = readFully(tar, header)
            if (read == 0) break
            if (read < BLOCK) {
                // 末尾 padding 可能不足一块；剩余全是 0 就当正常结束
                if (header.copyOfRange(0, read).all { it == 0.toByte() }) break
                throw IllegalStateException("tar 头不完整")
            }
            if (header.all { it == 0.toByte() }) break // 结束块

            val name = readString(header, 0, 100)
            val size = octal(header, 124, 12)
            val type = header[156].toInt().toChar()
            val linkName = readString(header, 157, 100)

            when (type) {
                'L' -> { // GNU 长文件名：下一块内容是文件名
                    pendingName = readPayload(tar, size).trimEnd('\u0000')
                }
                'x' -> { // PAX 头：跳过
                    readPayload(tar, size)
                }
                else -> {
                    val path = pendingName ?: name
                    pendingName = null
                    runCatching {
                        extractEntry(tar, dest, path, linkName, type, size)
                    }
                    // 单条失败（个别归档的 Windows 边角）跳过，不中断整体
                }
            }
        }
    }

    private fun extractEntry(
        tar: InputStream,
        dest: File,
        path: String,
        linkName: String,
        type: Char,
        size: Long
    ) {
        val f = File(dest, path)
        when (type) {
            '5' -> f.mkdirs()
            '2' -> { // 符号链接
                f.parentFile?.mkdirs()
                symlinkImpl(linkName, f.absolutePath)
            }
            '1' -> { // 硬链接：复制目标内容
                val target = File(dest, linkName)
                f.parentFile?.mkdirs()
                target.takeIf { it.exists() }?.copyTo(f, overwrite = true)
                skip(tar, size)
            }
            else -> { // 普通文件（0 / '0' / 空）
                f.parentFile?.mkdirs()
                f.outputStream().use { out ->
                    var left = size
                    val buf = ByteArray(BLOCK)
                    while (left > 0) {
                        val n = tar.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) break
                        out.write(buf, 0, n)
                        left -= n
                    }
                    skipTo(tar, size) // 补齐到 512 边界
                }
            }
        }
    }

    private fun readFully(`in`: InputStream, b: ByteArray): Int {
        var off = 0
        while (off < b.size) {
            val n = `in`.read(b, off, b.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    private fun readString(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun octal(b: ByteArray, off: Int, len: Int): Long {
        var v = 0L
        for (i in off until off + len) {
            val c = b[i]
            if (c in '0'.code.toByte()..'7'.code.toByte()) v = v * 8 + (c - '0'.code.toByte())
            else if (c == 0.toByte() || c == ' '.code.toByte()) break
        }
        return v
    }

    private fun readPayload(`in`: InputStream, size: Long): String {
        val buf = ByteArray(size.toInt())
        readFully(`in`, buf)
        skipTo(`in`, size)
        return String(buf, Charsets.UTF_8)
    }

    private fun skip(`in`: InputStream, size: Long) {
        skipTo(`in`, size)
    }

    private fun skipTo(`in`: InputStream, size: Long) {
        var left = (size + BLOCK - 1) / BLOCK * BLOCK - size // 补齐到 512 的倍数
        val buf = ByteArray(BLOCK)
        while (left > 0) {
            val n = `in`.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            left -= n
        }
    }
}
