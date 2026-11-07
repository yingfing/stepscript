package com.yingfing.stepscript.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yingfing.stepscript.StepScriptApp

/** 设置：AI 模型（OpenAI 兼容 base URL + key + 模型名）。 */
@Composable
fun SettingsScreen() {
    val prefs = StepScriptApp.prefs
    var baseUrl by remember { mutableStateOf(prefs.getString("baseUrl", "") ?: "") }
    var apiKey by remember { mutableStateOf(prefs.getString("apiKey", "") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("model", "") ?: "") }

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("AI 脚本医生", style = MaterialTheme.typography.titleMedium)
        Text(
            "配置一个 OpenAI 兼容接口（也兼容本地 Ollama：填 http://电脑IP:11434/v1，模型名如 qwen2.5）。" +
                "AI 仅在脚本失败时被调用，产出脚本补丁，由你确认后生效，不会直接操作手机。",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("接口地址 Base URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API Key（本地模型可留空）") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(model, { model = it }, label = { Text("模型名") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            prefs.edit().putString("baseUrl", baseUrl).putString("apiKey", apiKey).putString("model", model).apply()
        }) { Text("保存") }
    }
}
