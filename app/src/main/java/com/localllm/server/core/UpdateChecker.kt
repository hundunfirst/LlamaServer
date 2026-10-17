package com.localllm.server.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** 检查 GitHub Releases 是否有新版本（api.github.com，国内可用性视网络而定） */
object UpdateChecker {

    data class Info(
        val version: String,
        val apkUrl: String,
        val releaseUrl: String,
        val notes: String
    )

    suspend fun check(currentVersion: String): Info? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL("https://api.github.com/repos/hundunfirst/LlamaServer/releases/latest")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 6_000
            conn.readTimeout = 9_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "LlamaServer-Android")
            val body = try {
                if (conn.responseCode !in 200..299) return@runCatching null
                conn.inputStream.bufferedReader().use(BufferedReader::readText)
            } finally {
                conn.disconnect()
            }
            val o = JSONObject(body)
            val tag = o.optString("tag_name").removePrefix("v").trim()
            if (tag.isBlank() || tag == currentVersion) return@runCatching null
            val assets = o.optJSONArray("assets")
            val apkUrl = (0 until (assets?.length() ?: 0))
                .mapNotNull { assets!!.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") }
                ?.optString("browser_download_url")
                ?: o.optString("html_url")
            Info(tag, apkUrl, o.optString("html_url"), o.optString("body").take(1500))
        }.getOrNull()
    }
}
