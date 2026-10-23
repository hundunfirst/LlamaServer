package com.localllm.server.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * llama-server 参数拼装的回归测试。
 * 这些 flag 一旦被引擎升版改名或逻辑被改坏，服务要么起不来、要么静默少个能力，
 * 只靠读日志很难发现——所以把每个条件分支钉死。
 */
class ServerArgsTest {

    private fun build(
        threads: Int = 6,
        ngl: Int = 0,
        npuDevice: String? = null,
        lanEnabled: Boolean = false,
        apiToken: String = "",
        cacheTypeK: String = "",
        cacheTypeV: String = "",
        specType: String = "none",
        mmprojPath: String? = null,
        mmprojExists: Boolean = false,
        faMode: String = "",
        apiTokenEnabled: Boolean = false,
        loraPath: String? = null,
        loraScale: Float = 1.0f,
        loadMode: String = ""
    ): Pair<List<String>, List<String>> = buildServerArgs(
        binaryPath = "/data/app/engines/cpu/llama-server",
        modelPath = "/data/models/a.gguf",
        host = "127.0.0.1",
        port = 8080,
        ctxSize = 32768,
        threads = threads,
        ngl = ngl,
        npuDevice = npuDevice,
        lanEnabled = lanEnabled,
        apiToken = apiToken,
        cacheTypeK = cacheTypeK,
        cacheTypeV = cacheTypeV,
        specType = specType,
        mmprojPath = mmprojPath,
        mmprojExists = mmprojExists,
        faMode = faMode,
        apiTokenEnabled = apiTokenEnabled,
        loraPath = loraPath,
        loraScale = loraScale,
        loadMode = loadMode
    )

    @Test
    fun `基础参数按固定顺序拼出`() {
        val args = build().first
        assertEquals("/data/app/engines/cpu/llama-server", args[0])
        // 只钉前缀：-t/-ngl 等是条件追加的，跟在后面
        val expectedPrefix = listOf(
            "-m", "/data/models/a.gguf",
            "--host", "127.0.0.1",
            "--port", "8080",
            "-c", "32768",
            "--jinja"
        )
        assertEquals(expectedPrefix, args.subList(1, 1 + expectedPrefix.size))
    }

    @Test
    fun `threads 与 ngl 非正数时不下发`() {
        val bare = build(threads = 0, ngl = 0).first
        assertFalse(bare.contains("-t"))
        assertFalse(bare.contains("-ngl"))

        val withBoth = build(threads = 6, ngl = 99).first
        assertTrue(withBoth.containsAll(listOf("-t", "6")))
        assertTrue(withBoth.containsAll(listOf("-ngl", "99")))
    }

    @Test
    fun `NPU 模式追加 --device 且沿用调用方给的 ngl`() {
        val args = build(ngl = 99, npuDevice = "HTP0").first
        val i = args.indexOf("--device")
        assertTrue(i >= 0)
        assertEquals("HTP0", args[i + 1])
        // NPU 靠 -ngl 决定卸载范围，必须是全量
        assertEquals("99", args[args.indexOf("-ngl") + 1])

        // 没开 NPU 时绝不能出现 --device（CPU/GPU 引擎不认这个参数）
        assertFalse(build(ngl = 99).first.contains("--device"))
        assertFalse(build(npuDevice = "HTP0", ngl = 0).first.contains("-ngl"))
    }

    @Test
    fun `加载模式留空不下发，指定时按 -lm 透传`() {
        assertFalse(build().first.contains("-lm"))

        val dio = build(loadMode = "dio").first
        assertEquals("dio", dio[dio.indexOf("-lm") + 1])

        val mmap = build(loadMode = "mmap").first
        assertEquals("mmap", mmap[mmap.indexOf("-lm") + 1])
    }

    @Test
    fun `访问令牌只在局域网且开关打开时下发`() {
        // 有令牌但没开局域网：不带（本机回环不该被要求鉴权）
        assertFalse(build(apiToken = "tk", apiTokenEnabled = true, lanEnabled = false).first.contains("--api-key"))
        // 开了局域网但开关没开：不带
        assertFalse(build(apiToken = "tk", apiTokenEnabled = false, lanEnabled = true).first.contains("--api-key"))
        // 都开了但令牌为空：不带
        assertFalse(build(apiToken = "", apiTokenEnabled = true, lanEnabled = true).first.contains("--api-key"))
        // 三者齐备才带
        val args = build(apiToken = "tk", apiTokenEnabled = true, lanEnabled = true).first
        val i = args.indexOf("--api-key")
        assertTrue(i >= 0)
        assertEquals("tk", args[i + 1])
    }

    @Test
    fun `KV 缓存类型为空时不下发`() {
        val bare = build().first
        assertFalse(bare.contains("--cache-type-k"))
        assertFalse(bare.contains("--cache-type-v"))

        val args = build(cacheTypeK = "q8_0", cacheTypeV = "q4_0").first
        assertEquals("q8_0", args[args.indexOf("--cache-type-k") + 1])
        assertEquals("q4_0", args[args.indexOf("--cache-type-v") + 1])
    }

    @Test
    fun `投机解码 none 不下发 ngram 追加 draft 上限`() {
        assertFalse(build(specType = "none").first.contains("--spec-type"))
        assertFalse(build(specType = "").first.contains("--spec-type"))

        val mtp = build(specType = "draft-mtp").first
        assertEquals("draft-mtp", mtp[mtp.indexOf("--spec-type") + 1])
        assertFalse(mtp.contains("--spec-draft-n-max"))

        val ngram = build(specType = "ngram-simple").first
        assertEquals("ngram-simple", ngram[ngram.indexOf("--spec-type") + 1])
        assertEquals("32", ngram[ngram.indexOf("--spec-draft-n-max") + 1])
    }

    @Test
    fun `mmproj 路径为空或文件不存在时不下发`() {
        assertFalse(build().first.contains("--mmproj"))
        assertFalse(
            build(mmprojPath = "/data/projectors/p.gguf", mmprojExists = false).first.contains("--mmproj")
        )
        val args = build(mmprojPath = "/data/projectors/p.gguf", mmprojExists = true).first
        assertEquals("/data/projectors/p.gguf", args[args.indexOf("--mmproj") + 1])
    }

    @Test
    fun `LoRA 用 lora-scaled 拼路径与强度并留日志`() {
        val (args, logs) = build(loraPath = "/data/adapters/x.gguf", loraScale = 0.5f)
        val i = args.indexOf("--lora-scaled")
        assertTrue(i >= 0)
        assertEquals("/data/adapters/x.gguf:0.5", args[i + 1])
        assertTrue(logs.any { it.contains("x.gguf") && it.contains("0.5") })
    }

    @Test
    fun `量化 V 缓存会自动把 FA 打开并留日志`() {
        // 未指定 FA：应被自动纠正为 on
        val (args, logs) = build(cacheTypeV = "q4_0", faMode = "")
        assertEquals("on", args[args.indexOf("-fa") + 1])
        assertTrue(logs.any { it.contains("Flash Attention") })

        // 显式 off 也会被纠正成 on（引擎硬性要求）
        val (forced, forcedLogs) = build(cacheTypeV = "q8_0", faMode = "off")
        assertEquals("on", forced[forced.indexOf("-fa") + 1])
        assertTrue(forcedLogs.isNotEmpty())
    }

    @Test
    fun `FA 为自动或 f16 缓存时不强加 -fa`() {
        // V 缓存留空（默认 f16）→ 不动 FA
        assertFalse(build(cacheTypeV = "", faMode = "").first.contains("-fa"))
        assertFalse(build(cacheTypeV = "f16", faMode = "").first.contains("-fa"))
        // 用户显式 on/off 且 V 未量化 → 原样下发
        assertEquals("off", build(cacheTypeV = "", faMode = "off").first.let { it[it.indexOf("-fa") + 1] })
        assertEquals("on", build(cacheTypeV = "", faMode = "on").first.let { it[it.indexOf("-fa") + 1] })
    }
}
