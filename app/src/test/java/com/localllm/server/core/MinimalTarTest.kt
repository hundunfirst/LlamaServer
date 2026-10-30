package com.localllm.server.core

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 用**真实 tarball** 验证解压器：ubuntu-base 与 node 官方包都包含大量符号链接
 * （/bin/sh -> /usr/bin/bash、/usr/local/bin/node -> ../lib/...），zip 表达不了，
 * 必须 tar 原样还原。在 JVM 上跑（assets/runtime/ 相对路径在单测里可读）。
 */
class MinimalTarTest {

    companion object {
        // 单测工作目录 = app/ 模块目录，assets 相对路径从那里起
        private const val ASSETS = "src/main/assets/runtime"
        // 短根目录：npm 深层路径超 Windows 260 字符上限，必须避开长前缀
        private const val TMP_ROOT = "C:/mimotar"
        private lateinit var tmp: File
        private var symlinkOk = false

        @BeforeClass
        @JvmStatic
        fun setUp() {
            tmp = File(TMP_ROOT).apply { mkdirs() }
            // JVM 上用 Files.createSymbolicLink 模拟 android.system.Os.symlink
            // （Windows 需要开发者模式，失败就跳过链接断言，不影响真机路径）
            symlinkOk = runCatching {
                Files.createSymbolicLink(File(tmp, "probe").toPath(), File("x").toPath())
            }.isSuccess
            if (symlinkOk) {
                MinimalTar.symlinkImpl = { target, link ->
                    runCatching { Files.createSymbolicLink(File(link).toPath(), File(target).toPath()) }.isSuccess
                }
            }
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            tmp.deleteRecursively()
        }
    }

    private fun extract(asset: String): File {
        val dest = File(tmp, asset.substringBefore('.'))
        File(ASSETS, asset).inputStream().use { MinimalTar.extractGz(it, dest) }
        return dest
    }

    @Test
    fun `rootfs 解出关键路径且符号链接还原`() {
        val rf = extract("rootfs.bin")
        assertTrue("usr/bin/env 应存在", File(rf, "usr/bin/env").isFile)
        assertTrue("usr/bin/bash 应存在", File(rf, "usr/bin/bash").isFile)
        assertTrue("usr/bin/apt 应存在", File(rf, "usr/bin/apt").isFile)
        assertTrue(
            "lib/ld-linux 应存在",
            File(rf, "lib/ld-linux-aarch64.so.1").exists() ||
                    File(rf, "usr/lib/ld-linux-aarch64.so.1").exists()
        )
        assertTrue("条目数应可观", rf.walkTopDown().count() > 1000)
        if (symlinkOk) {
            assertTrue("bin/sh 应是符号链接", Files.isSymbolicLink(File(rf, "bin/sh").toPath()))
            assertTrue("bin/sh 解析到 bash", File(rf, "bin/sh").exists())
        }
    }

    @Test
    fun `node 包解到 usr local 且 bin 链接还原`() {
        val rf = extract("node-arm64.bin")
        // PATH 里真正用到的是 usr/local/bin/node（实体二进制）；lib/node_modules 深路径是 Windows 边角
        assertTrue("usr/local/bin/node 应存在", File(rf, "usr/local/bin/node").isFile)
        assertFalse("不应残留顶层目录", File(rf, "node-v24.22.1-linux-arm64").exists())
        if (symlinkOk) {
            assertTrue("usr/local/bin/npm 应是符号链接", Files.isSymbolicLink(File(rf, "usr/local/bin/npm").toPath()))
            assertTrue("usr/local/bin/npm 可解析", File(rf, "usr/local/bin/npm").exists())
        }
    }
}
