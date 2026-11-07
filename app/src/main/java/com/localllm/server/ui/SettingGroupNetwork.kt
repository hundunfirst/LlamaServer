package com.localllm.server.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * 设置页「搜索与网络」分组：联网搜索策略、配额与搜索相关设置。
 *
 * 与其它分组一样从 SettingsScreen 搬出来，块内状态自持。
 */
@Composable
internal fun SettingGroupNetwork() {

            var imgPolicy by remember {
                mutableStateOf(com.localllm.server.core.AppSettings.imageSearchPolicy)
            }
            SectionHeader(
                title = "含图片时的联网搜索",
                help = "禁止：含图片的消息不提供搜索工具（证件照最安全）；" +
                        "先问我：模型想搜时弹窗展示将要发送的内容，你允许才发；允许：直接搜。立即生效。",
                titleStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp)
            )
            QuickChips(
                options = listOf("block" to "禁止", "ask" to "先问我", "allow" to "允许"),
                current = imgPolicy
            ) { v ->
                imgPolicy = v
                com.localllm.server.core.AppSettings.imageSearchPolicy = v
            }

            // ---- 联网搜索次数上限 ----
            var searchCapSel by remember {
                mutableStateOf(com.localllm.server.core.AppSettings.searchMaxCalls.toString())
            }
            SectionHeader(
                title = "联网搜索次数上限",
                help = "每条消息最多允许调用几次联网搜索（相同查询复用不计数）。次数用完会移除搜索工具，强制模型基于已有结果作答；「不限」适合需要多轮检索的深度问题，弱模型可能反复搜索。立即生效。",
                titleStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp)
            )
            QuickChips(
                options = listOf("2" to "2 次", "3" to "3 次", "5" to "5 次", "0" to "不限"),
                current = searchCapSel
            ) { v ->
                searchCapSel = v
                com.localllm.server.core.AppSettings.searchMaxCalls = v.toIntOrNull() ?: 3
            }

            // ---- 搜索是否抓取正文 ----
            var fetchFull by remember {
                mutableStateOf(com.localllm.server.core.AppSettings.searchFetchFullText)
            }
            SectionHeader(
                title = "搜索时抓取正文",
                help = "开启：每次搜索抓取前 3 条结果的网页正文（每篇约 1800 字）作为回答依据，回答更实在、不易照搬标题，代价是每次搜索多 1~3 秒；关闭：只用标题与摘要，更快但信息单薄。立即生效。",
                titleStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp)
            )
            QuickChips(
                options = listOf("true" to "开启（更实在）", "false" to "关闭（更快）"),
                current = fetchFull.toString()
            ) { v ->
                fetchFull = v.toBoolean()
                com.localllm.server.core.AppSettings.searchFetchFullText = fetchFull
            }

            // 这一段原本跟「服务设置」同组，但中间隔着别的组，展开后内容会落到下面几个组的标题后面，
            // 看起来像串行。给它自己的组头，保证每组内容紧跟自己的标题。
            }
