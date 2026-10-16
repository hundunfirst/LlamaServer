package com.localllm.server.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * 紧凑输入框：统一收紧高度与字号（默认 M3 输入框 56dp 高、内边距大，数值看着只占 1/3）。
 * 高度 46dp + bodySmall 文本 + 小号浮动标签。
 */
@Composable
fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    number: Boolean = false,
    readOnly: Boolean = false,
    placeholder: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        textStyle = MaterialTheme.typography.bodySmall,
        placeholder = placeholder?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        singleLine = true,
        readOnly = readOnly,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (number) KeyboardType.Number else KeyboardType.Text
        ),
        trailingIcon = trailing,
        modifier = modifier.height(46.dp)
    )
}

/** 紧凑多行输入框（如参数文本） */
@Composable
fun CompactFieldMultiline(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    minHeight: Int = 72
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        textStyle = MaterialTheme.typography.bodySmall,
        modifier = modifier.height(minHeight.dp)
    )
}
