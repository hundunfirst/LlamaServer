package com.localllm.server.core

/**
 * llama-server 启动参数拼装。
 *
 * 从 `ServerProcessManager.buildCommand` 抽出来做成纯函数：原实现直接依赖 `AppSettings` 和
 * `File.exists()`，在 JVM 单测里跑不起来，而这串参数恰恰是最容易悄悄回归的地方——引擎升版
 * 可能改掉 flag 名（`--spec-type`、`--lora-scaled`、`-fa` 都变过），而改错的后果是
 * 服务起不来或者静默少了个能力，只能靠肉眼读日志发现。
 *
 * 返回值的第二个 List 是要写进日志的中文说明（LoRA 生效、FA 被自动纠正），由调用方负责落盘。
 */
fun buildServerArgs(
    binaryPath: String,
    modelPath: String,
    host: String,
    port: Int,
    ctxSize: Int,
    threads: Int,
    ngl: Int,
    npuDevice: String? = null,
    lanEnabled: Boolean,
    apiToken: String,
    cacheTypeK: String,
    cacheTypeV: String,
    specType: String,
    mmprojPath: String?,
    mmprojExists: Boolean,
    faMode: String,
    apiTokenEnabled: Boolean,
    loraPath: String?,
    loraScale: Float,
    /** 模型加载模式："" = 引擎默认；"dio" = DirectIO（模型比内存大时绕开 page cache） */
    loadMode: String = ""
): Pair<List<String>, List<String>> {
    val cmd = mutableListOf(
        binaryPath,
        "-m", modelPath,
        "--host", host,
        "--port", port.toString(),
        "-c", ctxSize.toString(),
        // 现代模型（尤其多模态 Qwen-VL 等）需要完整 jinja 模板才能正确组装对话
        "--jinja"
    )
    // 加载模式：模型体积接近/超过物理内存时，mmap 会疯狂换页，
    // dio（DirectIO）绕开 page cache、只读当前需要的权重块，是 MoE 大模型的救命开关
    if (loadMode.isNotEmpty()) {
        cmd += listOf("-lm", loadMode)
    }
    if (threads > 0) {
        cmd += listOf("-t", threads.toString())
    }
    if (ngl > 0) {
        cmd += listOf("-ngl", ngl.toString())
    }
    // Hexagon NPU：这台设备上 NPU 在卸载参数里等同于 GPU，所以配 `-ngl 99` + `--device HTP0`。
    // 设备名由 ggml-hexagon 注册（HTP0 / HTP0:0 / HTP0,HTP1 等），调用方传什么就是什么。
    if (npuDevice != null) {
        cmd += listOf("--device", npuDevice)
    }
    // 令牌只在真正对外（局域网 + 开关打开 + 非空）时才下发，免得本机回环也带鉴权
    if (lanEnabled && apiTokenEnabled && apiToken.isNotEmpty()) {
        cmd += listOf("--api-key", apiToken)
    }
    if (cacheTypeK.isNotEmpty()) {
        cmd += listOf("--cache-type-k", cacheTypeK)
    }
    if (cacheTypeV.isNotEmpty()) {
        cmd += listOf("--cache-type-v", cacheTypeV)
    }
    if (specType.isNotEmpty() && specType != "none") {
        cmd += listOf("--spec-type", specType)
        if (specType.contains("ngram")) {
            cmd += listOf("--spec-draft-n-max", "32")
        }
    }
    if (mmprojPath != null && mmprojExists) {
        cmd += listOf("--mmproj", mmprojPath)
    }

    val logs = mutableListOf<String>()
    // LoRA 适配器：路径存在性已由调用方（effectiveLora）过滤，这里不再判断
    if (loraPath != null) {
        cmd += listOf("--lora-scaled", "$loraPath:$loraScale")
        logs += "LoRA 适配器：${loraPath.substringAfterLast('/')}（强度 $loraScale）"
    }
    // 量化 V 缓存必须开 FA（引擎硬性要求：quantized V cache requires flash_attn），
    // 用户设了 off/自动时强制 on 并留日志，避免启动失败。
    var fa = faMode
    if (cacheTypeV.isNotEmpty() && cacheTypeV != "f16" && fa != "on") {
        logs += "V缓存量化（$cacheTypeV）需要 Flash Attention，已自动将 -fa 设为 on" +
                "（原设置：${faMode.ifBlank { "自动" }}）"
        fa = "on"
    }
    if (fa == "on" || fa == "off") {
        cmd += listOf("-fa", fa)
    }
    return cmd to logs
}
