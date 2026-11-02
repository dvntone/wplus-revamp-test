package com.wscanplus.android.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.wscanplus.android.ui.screens.AiAnalysisScreen
import com.wscanplus.android.ui.screens.AlertsScreen
import com.wscanplus.android.ui.screens.HomeScreen
import com.wscanplus.android.ui.screens.IntegrationsScreen
import com.wscanplus.android.ui.screens.ModularToolScreen
import com.wscanplus.android.ui.screens.SettingsScreen

sealed class Screen(val route: String, val label: String) {
    data object Home : Screen("home", "Scan")
    data object Alerts : Screen("alerts", "Alerts")
    data object Tools : Screen("tools", "SecLab")
    data object Ai : Screen("ai", "Nyx AI")
    data object Integrations : Screen("integrations", "Terminal")
    data object Settings : Screen("settings", "Settings")
}

private val bottomNavItems = listOf(
    Screen.Home,
    Screen.Alerts,
    Screen.Tools,
    Screen.Ai,
    Screen.Integrations
)

@Composable
fun WscanPlusApp(
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
) {
    val navController = rememberNavController()
    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                bottomNavItems.forEach { screen ->
                    NavigationBarItem(
                        modifier = Modifier.testTag("nav_${screen.route}"),
                        icon = {
                            when (screen) {
                                Screen.Home -> Icon(Icons.Filled.Home, contentDescription = screen.label)
                                Screen.Alerts -> Icon(Icons.Filled.Warning, contentDescription = screen.label)
                                Screen.Tools -> Icon(Icons.Filled.Hub, contentDescription = screen.label)
                                Screen.Ai -> Icon(Icons.Filled.SmartToy, contentDescription = screen.label)
                                Screen.Integrations -> Icon(Icons.Filled.Terminal, contentDescription = screen.label)
                                Screen.Settings -> Icon(Icons.Filled.Settings, contentDescription = screen.label)
                            }
                        },
                        label = { Text(screen.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    darkTheme = darkTheme,
                    onToggleTheme = onToggleTheme,
                    onNavigateToSettings = {
                        navController.navigate(Screen.Settings.route) {
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(Screen.Alerts.route) {
                AlertsScreen()
            }
            composable(Screen.Tools.route) {
                ModularToolScreen()
            }
            composable(Screen.Ai.route) {
                AiAnalysisScreen()
            }
            composable(Screen.Integrations.route) {
                IntegrationsScreen()
            }
            composable(Screen.Settings.route) {
                SettingsScreen()
            }
        }
    }
}
