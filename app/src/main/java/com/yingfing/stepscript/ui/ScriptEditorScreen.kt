package com.yingfing.stepscript.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yingfing.stepscript.StepScriptApp
import com.yingfing.stepscript.data.ScriptEntity
import kotlinx.coroutines.launch

/** 脚本编辑器：新建 / 编辑源码，实时语法校验，顶部返回即保存。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptEditorScreen(scriptId: Long?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val existing = scriptId?.let { id ->
        produceState<ScriptEntity?>(initialValue = null, id) {
            value = StepScriptApp.db.scripts.getById(id)
        }.value
    }

    var name by remember(existing) { mutableStateOf(existing?.name ?: "未命名脚本") }
    var source by remember(existing) { mutableStateOf(existing?.source ?: "# 一行一个动作，# 开头是注释\n") }
    val error = remember(source) { tryParse(source) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (scriptId == null) "新建脚本" else "编辑脚本") },
                navigationIcon = {
                    IconButton(onClick = {
                        scope.launch {
                            if (scriptId == null) {
                                StepScriptApp.db.scripts.insert(ScriptEntity(name = name, source = source))
                            } else {
                                StepScriptApp.db.scripts.updateSource(scriptId, source)
                            }
                        }
                        onBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "保存并返回") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("脚本名称") },
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                singleLine = true,
            )
            val highlight = remember { StepScriptHighlight() }
            OutlinedTextField(
                value = source,
                onValueChange = { source = it },
                visualTransformation = highlight,
                label = { Text("脚本内容（StepScript）") },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).weight(1f),
            )
            // 语法状态条
            Surface(color = if (error == null) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.errorContainer) {
                Text(
                    error?.message ?: "✓ 语法检查通过",
                    Modifier.fillMaxWidth().padding(10.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
