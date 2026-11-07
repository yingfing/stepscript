package com.yingfing.stepscript.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yingfing.stepscript.StepScriptApp
import com.yingfing.stepscript.ai.ScriptDoctor
import com.yingfing.stepscript.data.FailureEntity
import com.yingfing.stepscript.core.Diff
import kotlinx.coroutines.launch

/** 失败记录列表（待处理入口）。 */
@Composable
fun FailureListScreen(onOpen: (Long) -> Unit) {
    val items by StepScriptApp.db.failures.observePending()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    LazyColumn(Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { f ->
            ListItem(
                headlineContent = { Text("${f.scriptName} · 第 ${f.stepIndex + 1} 步") },
                supportingContent = { Text(f.reason, maxLines = 2) },
                modifier = Modifier.clickable { onOpen(f.id) },
            )
            HorizontalDivider()
        }
    }
}

/** 失败详情：现场信息 + 布局快照 + AI 补丁（diff 确认制）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FailureDetailScreen(failureId: Long, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val failure = produceState<FailureEntity?>(initialValue = null, failureId) {
        value = StepScriptApp.db.failures.getById(failureId)
    }.value
    val snackbar = remember { SnackbarHostState() }

    var patch by remember { mutableStateOf<ScriptDoctor.Patch?>(null) }
    var diagnosing by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text("失败现场") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        }) },
    ) { pad ->
        if (failure == null) { Box(Modifier.padding(pad)); return@Scaffold }
        Column(Modifier.padding(pad).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("脚本：${failure.scriptName}", style = MaterialTheme.typography.titleMedium)
            Text("出错语句（第 ${failure.stepIndex + 1} 步）：${failure.stepText}")
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Text(failure.reason, Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodySmall)
            }
            if (failure.screenshotPath != null) {
                Text("截图：${failure.screenshotPath}", style = MaterialTheme.typography.bodySmall)
            }
            // 布局快照（折叠展示，等宽字体）
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    failure.layoutText.take(2000),
                    Modifier.fillMaxWidth().padding(10.dp).heightIn(max = 200.dp)
                        .horizontalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                )
            }

            when {
                // 还没请求 AI
                patch == null && !diagnosing -> Button(onClick = {
                    diagnosing = true
                    scope.launch {
                        runCatching {
                            val script = StepScriptApp.db.scripts.getById(failure.scriptId) ?: return@runCatching
                            val doctor = ScriptDoctor(
                                baseUrl = StepScriptApp.prefs.getString("baseUrl", "")!!,
                                apiKey = StepScriptApp.prefs.getString("apiKey", "")!!,
                                model = StepScriptApp.prefs.getString("model", "")!!,
                            )
                            patch = doctor.diagnose(script.source, failure.stepText, failure.reason, failure.layoutText)
                        }.onFailure { snackbar.showSnackbar("AI 分析失败：${it.message}") }
                        diagnosing = false
                    }
                }) { Text(if (diagnosing) "正在分析…" else "让 AI 诊断并生成补丁") }

                diagnosing -> CircularProgressIndicator()

                // 展示 diff，等待用户确认
                patch != null -> {
                    Text("AI 修复说明：${patch!!.explanation}", style = MaterialTheme.typography.bodyMedium)
                    DiffView(patch!!.diff)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                // 打补丁 + 存版本历史
                                StepScriptApp.db.versions.insert(
                                    com.yingfing.stepscript.data.ScriptVersionEntity(
                                        scriptId = failure.scriptId,
                                        source = patch!!.newSource,
                                        comment = "AI 补丁：${patch!!.explanation}",
                                    )
                                )
                                StepScriptApp.db.scripts.updateSource(failure.scriptId, patch!!.newSource)
                                StepScriptApp.db.failures.markResolved(failureId)
                                snackbar.showSnackbar("补丁已应用")
                                onBack()
                            }
                        }) { Text("采纳补丁") }
                        OutlinedButton(onClick = { patch = null }) { Text("拒绝") }
                    }
                }
            }
        }
    }
}

/** diff 视图：- 红删除，+ 绿新增，其余灰。 */
@Composable
fun DiffView(lines: List<Diff.Line>) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            lines.forEach { l ->
                Text(
                    "${l.prefix} ${l.text}",
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    color = when (l.prefix) {
                        "-" -> Color(0xFFC62828)
                        "+" -> Color(0xFF2E7D32)
                        else -> Color.Gray
                    },
                )
            }
        }
    }
}
