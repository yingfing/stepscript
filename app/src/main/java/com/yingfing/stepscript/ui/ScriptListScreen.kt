package com.yingfing.stepscript.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yingfing.stepscript.StepScriptApp
import com.yingfing.stepscript.access.A11y
import com.yingfing.stepscript.data.ScriptEntity
import com.yingfing.stepscript.playback.PlaybackEngine
import kotlinx.coroutines.launch

/** 首页：脚本列表 + 无障碍状态横幅 + 运行入口。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptListScreen(
    onOpen: (Long) -> Unit,
    onNew: () -> Unit,
) {
    val scripts by StepScriptApp.db.scripts.observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var running by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(title = { Text("拾步 StepScript") })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNew) {
                Icon(Icons.Filled.Add, null); Text(" 新建脚本 ")
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            if (!A11y.isReady) {
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Text(
                        "无障碍服务未开启 —— 请到 系统设置 > 无障碍 中开启「拾步 · 界面采集与自动化」",
                        Modifier.fillMaxWidth().padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(scripts, key = { it.id }) { s ->
                    ListItem(
                        headlineContent = { Text(s.name) },
                        supportingContent = { Text(if (s.enabled) "已启用" else "已停用") },
                        trailingContent = {
                            Row {
                                if (!running) {
                                    IconButton(onClick = {
                                        scope.launch {
                                            running = true
                                            runScript(s, snackbar)
                                            running = false
                                        }
                                    }) { Icon(Icons.Filled.PlayArrow, "运行") }
                                }
                                IconButton(onClick = { scope.launch { StepScriptApp.db.scripts.delete(s.id) } }) {
                                    Icon(Icons.Filled.Delete, "删除")
                                }
                            }
                        },
                        modifier = Modifier.clickable { onOpen(s.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

private suspend fun runScript(s: ScriptEntity, snackbar: SnackbarHostState) {
    val script = try {
        com.yingfing.stepscript.core.Parser.parse(s.source)
    } catch (e: com.yingfing.stepscript.core.ScriptParseException) {
        snackbar.showSnackbar("脚本语法有误：${e.message}")
        return
    }
    val engine = PlaybackEngine(
        screenshot = { null }, // v1 截图通道见 README：需要 ImageReader 落盘，占位
        notify = { _, _ -> },
        onRecordFailure = { ctx ->
            StepScriptApp.db.failures.insert(
                com.yingfing.stepscript.data.FailureEntity(
                    scriptId = s.id, scriptName = s.name,
                    stepIndex = ctx.stepIndex,
                    stepText = com.yingfing.stepscript.core.Renderer.renderStep(ctx.step),
                    reason = ctx.reason,
                    screenshotPath = ctx.screenshotPath,
                    layoutText = ctx.layoutText, layoutXml = ctx.layoutXml,
                )
            )
        },
    )
    val ok = engine.run(script) { }
    snackbar.showSnackbar(if (ok) "「${s.name}」执行完成" else "「${s.name}」执行失败，已留证，去「失败记录」处理")
}
