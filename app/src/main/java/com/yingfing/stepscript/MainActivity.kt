package com.yingfing.stepscript

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.yingfing.stepscript.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StepScriptTheme {
                App()
            }
        }
    }
}

/** 底部导航：脚本 / 失败记录 / 设置；编辑器等二级页隐藏底栏。 */
@Composable
fun App() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = route in setOf("scripts", "failures", "settings")

    Scaffold(
        bottomBar = {
            if (showBar) NavigationBar {
                NavigationBarItem(
                    selected = route == "scripts",
                    onClick = { nav.navigate("scripts") { launchSingleTop = true } },
                    icon = { Icon(Icons.Filled.List, null) }, label = { Text("脚本") },
                )
                NavigationBarItem(
                    selected = route == "failures",
                    onClick = { nav.navigate("failures") { launchSingleTop = true } },
                    icon = { Icon(Icons.Filled.Warning, null) }, label = { Text("失败记录") },
                )
                NavigationBarItem(
                    selected = route == "settings",
                    onClick = { nav.navigate("settings") { launchSingleTop = true } },
                    icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("设置") },
                )
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = "scripts", Modifier.padding(pad)) {
            composable("scripts") {
                ScriptListScreen(
                    onOpen = { id -> nav.navigate("editor/$id") },
                    onNew = { nav.navigate("editor/-1") },
                )
            }
            composable("editor/{id}") { entry ->
                val id = entry.arguments?.getString("id")?.toLongOrNull()?.takeIf { it > 0 }
                ScriptEditorScreen(id) { nav.popBackStack() }
            }
            composable("failures") { FailureListScreen(onOpen = { id -> nav.navigate("failure/$id") }) }
            composable("failure/{id}") { entry ->
                val id = entry.arguments?.getString("id")?.toLongOrNull() ?: return@composable
                FailureDetailScreen(id) { nav.popBackStack() }
            }
            composable("settings") { SettingsScreen() }
        }
    }
}
