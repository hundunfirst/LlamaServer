package com.localllm.server.core

import android.content.Context
import java.io.File

/**
 * 按包名给的「这个 App 长什么样」的提示。
 *
 * 为什么要它：模型第一次操作某个 App，总要先摸清底部导航在哪、搜索框在哪，这要花掉两三步。
 * Mika 的做法是社区维护的纯文本知识（`APP.md`），开局按包名挑几条给模型。这里做两层：
 *
 * 1. 内置：只写**确定性高**的（底部导航、搜索框位置）。写错比不写更糟，所以只收这几个常用的；
 * 2. 用户自建：`files/app-knowledge/<包名>.md`，有就优先用（用户比我更清楚自己手机上的 App）。
 *
 * 提示只在读到该 App 的界面时附一次，不占常驻上下文。
 */
object AppKnowledge {

    private val builtin: Map<String, String> = mapOf(
        "com.tencent.mm" to
                "微信：底部四个 tab（微信 / 通讯录 / 发现 / 我）。聊天列表在「微信」tab；" +
                "搜联系人用顶部搜索框；发消息要先进入与某人的对话再输入。",
        "com.xingin.xhs" to
                "小红书：底部是 首页 / 购物 / ➕ / 消息 / 我。消息在倒数第二个 tab；" +
                "首页顶部是搜索框（点进去可能先到「AI 搜索」页，再点一次输入框才是普通搜索）。",
        "com.android.settings" to
                "系统设置：顶部有搜索框，直接搜设置项名字最快；下面按分类（网络、显示、声音…）排列。",
        "com.xiaomi.shop" to
                "小米商城：首页顶部是搜索框（右侧放大镜）；底部 首页 / 分类 / 服务 / 购物车 / 我的。" +
                "启动时有开屏广告，右上角有「跳过」。",
        "com.taobao.taobao" to
                "淘宝：首页顶部是搜索框；底部 首页 / 逛逛 / 消息 / 购物车 / 我的。",
        "com.eg.android.AlipayGphone" to
                "支付宝：首页顶部是搜索框；底部 首页 / 理财 / 生活 / 消息 / 我的。",
        "com.tencent.mtt" to
                "QQ 浏览器：顶部地址栏可直接输入网址或搜索词。",
        "com.android.chrome" to
                "Chrome：顶部地址栏可直接输入网址或搜索词。"
    )

    private fun dir(context: Context): File = File(context.filesDir, "app-knowledge")

    /** 用户自己写的知识文件放哪（设置页可以告诉用户这个路径） */
    fun userDir(context: Context): File = dir(context)

    /** 这个包的用法提示；没有返回 null */
    fun forPackage(context: Context, packageName: String): String? {
        if (packageName.isBlank()) return null
        val custom = File(dir(context), "$packageName.md")
        if (custom.isFile) {
            val text = runCatching { custom.readText().trim() }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }
        return builtin[packageName]
    }
}
