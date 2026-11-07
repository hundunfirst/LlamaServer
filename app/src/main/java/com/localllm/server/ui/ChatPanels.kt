package com.localllm.server.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 两个弹出面板：附件选择、用量明细。
 *
 * 从 ChatScreen（四千行）里搬出来的独立面板，只依赖传入的参数与回调，
 * 和聊天主流程没有耦合。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachSheet(
    permissionLabel: String,
    planMode: Boolean,
    autoDrive: Boolean,
    planWithCloud: Boolean,
    onDismiss: () -> Unit,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    onFile: () -> Unit,
    onPermission: () -> Unit,
    onPlanMode: (Boolean) -> Unit,
    onAutoDrive: (Boolean) -> Unit,
    onPlanWithCloud: (Boolean) -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SheetAction(
                    label = "相册",
                    icon = Icons.Default.Image,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    onClick = onGallery
                )
                SheetAction(
                    label = "拍照",
                    icon = Icons.Default.PhotoCamera,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                    onClick = onCamera
                )
                SheetAction(
                    label = "文件",
                    icon = Icons.Default.Folder,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f),
                    onClick = onFile
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                "本对话",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    SheetRow(
                        title = "权限",
                        value = permissionLabel + " ›",
                        onClick = onPermission
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlanMode(!planMode) }
                            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("计划模式", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (planMode) "只读、先给计划，你点头再动手" else "关闭：照常执行",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = planMode,
                            onCheckedChange = onPlanMode
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAutoDrive(!autoDrive) }
                            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("自驱", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (autoDrive) "没做完就自己接着做，最多 5 次" else "关闭：模型说要收尾就收尾",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = autoDrive,
                            onCheckedChange = onAutoDrive
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlanWithCloud(!planWithCloud) }
                            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("规划用云端", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (planWithCloud) "用一次就自动关闭；云端只出计划，文件和命令仍在本地"
                                else "关闭：全部由本地模型完成",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = planWithCloud,
                            onCheckedChange = onPlanWithCloud
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SheetAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun SheetRow(title: String, value: String, onClick: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            color = if (onClick == null) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary
        )
        if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * 输入框右侧的上下文圆环：环长 = 占用比例，中间一位到两位百分比。
 * 没有用量（空会话）时调用方不会渲染它。
 */
/** 行级增删统计：新建文件 = 全是新增；有旧内容时按「同样的行先抵消」算差集 */
@Composable
internal fun UsageRing(pct: Int, onClick: () -> Unit) {
    val ring = when {
        pct >= 85 -> MaterialTheme.colorScheme.error
        pct >= 60 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(30.dp)) {
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 3.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = stroke
            )
            drawArc(
                color = ring,
                startAngle = -90f,
                sweepAngle = 360f * (pct.coerceIn(0, 100) / 100f),
                useCenter = false,
                style = stroke
            )
        }
        Text(
            "$pct",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** token 数：过万折成「1.5万」，跟 DSH 的读法一致 */
internal fun compactTokens(n: Int): String =
    if (n >= 10000) String.format(java.util.Locale.CHINA, "%.1f万", n / 10000.0) else n.toString()

/**
 * 点圆环打开的「用量」面板：上下文明细 + 本会话用量。
 * 只做这两块——主项目没有余额/计费这回事。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun UsageSheet(
    usage: com.localllm.server.ui.ChatViewModel.Usage,
    limit: Int,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("用量", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(18.dp))

            Text(
                "上下文",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            val usedTotal = usage.prompt + usage.output
            val pct = if (limit > 0) (usedTotal * 100L / limit).toInt().coerceIn(0, 100) else 0
            UsageCard {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "$pct%",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        "${compactTokens(usedTotal)} / ${compactTokens(limit)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { pct / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = when {
                        pct >= 85 -> MaterialTheme.colorScheme.error
                        pct >= 60 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "系统 ${String.format(java.util.Locale.CHINA, "%,d", usage.systemTokens)} · " +
                            "工具 ${String.format(java.util.Locale.CHINA, "%,d", usage.toolTokens)} · " +
                            "消息 ${String.format(java.util.Locale.CHINA, "%,d", usage.messageTokens)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(18.dp))
            Text(
                "本对话",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            UsageCard {
                Row {
                    UsageStat(compactTokens(usage.prompt), "输入", Modifier.weight(1f))
                    UsageStat(compactTokens(usage.output), "输出", Modifier.weight(1f))
                    UsageStat(
                        if (usage.prompt > 0) "${usage.cacheHit * 100 / usage.prompt}%" else "—",
                        "缓存命中",
                        Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    "共 ${usage.rounds} 轮 · 输入含缓存命中",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun UsageCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
internal fun UsageStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// 一体时间线的一行（DSH 样式）：思考/中间正文/搜索/工具按原始顺序渲染，每行独立可折叠。// think：折叠显示预览，展开显示完整思考（限高滚动盒，长按可复制）；
// text（中间正文）：默认展开显示迷你 markdown，可折叠；
// tool/search：折叠显示参数/结果预览，展开成「命令|参数」+「输出」两块。
/** 权限档位 → 面板上显示的名字 */
