package com.yingfing.stepscript.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yingfing.stepscript.StepScriptApp
import kotlinx.coroutines.launch

/** 应用选择器：从已安装应用里选，替代手填包名。 */
@Composable
fun AppPickerScreen(onPicked: (String) -> Unit, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val apps = remember {
        val pm = context.packageManager
        pm.getInstalledApplications(0)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { (it.loadLabel(pm).toString() to it.packageName) }
            .sortedBy { it.first }
    }
    var query by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("选择要启动的应用") },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it }, placeholder = { Text("搜索应用") })
                LazyColumn(Modifier.height(360.dp)) {
                    items(apps.filter { it.first.contains(query, true) }) { (label, pkg) ->
                        ListItem(
                            headlineContent = { Text(label) },
                            supportingContent = { Text(pkg) },
                            modifier = Modifier.clickable { onPicked(pkg) },
                        )
                    }
                }
            }
        },
    )
}
