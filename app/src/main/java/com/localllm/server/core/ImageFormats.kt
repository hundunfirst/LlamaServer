package com.localllm.server.core

/**
 * 图片格式判定。
 *
 * 为什么单独拎出来：App 能显示的格式 ≠ 本地引擎能读的格式。本地 llama.cpp 的图片解码用的是
 * stb_image，只认 jpg / png / bmp / gif（首帧）；**WebP、HEIC 一律解不了**，会把整个请求打成
 * `HTTP 400 Failed to load image or audio file`。踩过一次：相册里存的 WebP 被当成"可直传"，
 * 用户看到的就是"开了视觉模型也读不出图"。所以判定的口径统一按**服务端能不能读**来定，
 * 不是按手机能不能显示。
 */
object ImageFormats {

    /** 按文件头认格式，认不出来返回 null */
    fun magic(bytes: ByteArray): String? {
        if (bytes.size < 12) return null
        fun at(i: Int) = bytes[i].toInt() and 0xFF
        return when {
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "jpeg"
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "png"
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "gif"
            at(0) == 0x42 && at(1) == 0x4D -> "bmp"
            at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
                at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 -> "webp"
            at(4) == 0x66 && at(5) == 0x74 && at(6) == 0x79 && at(7) == 0x70 -> {
                // ftyp box：品牌在 8..12，heic/avif 系列手机拍照常见
                val brand = String(bytes, 8, 4, Charsets.US_ASCII).lowercase()
                when {
                    brand.startsWith("avif") -> "avif"
                    brand.startsWith("heic") || brand.startsWith("heix") ||
                        brand.startsWith("hevc") || brand.startsWith("mif1") -> "heic"
                    brand.startsWith("mp4") -> "mp4"
                    else -> null
                }
            }
            else -> null
        }
    }

    /** 本地引擎（stb_image）读得动的格式 */
    fun serverReadable(bytes: ByteArray): Boolean = when (magic(bytes)) {
        "jpeg", "png", "gif", "bmp" -> true
        else -> false
    }

    fun mimeOf(format: String?): String = when (format) {
        "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "heic" -> "image/heic"
        "avif" -> "image/avif"
        else -> "image/jpeg"
    }

    /**
     * 从 base64 头几个字符认格式（不解整串，省得为判格式把几百 KB 解码一遍）。
     * 认不出返回 null。
     */
    fun formatOfB64(b64: String): String? = when {
        b64.startsWith("/9j/") -> "jpeg"
        b64.startsWith("iVBOR") -> "png"
        b64.startsWith("R0lGOD") -> "gif"
        b64.startsWith("UklGR") -> "webp"
        b64.startsWith("Qk") -> "bmp"
        else -> null
    }

    fun mimeOfB64(b64: String): String = mimeOf(formatOfB64(b64))
}
