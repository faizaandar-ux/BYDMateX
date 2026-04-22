package com.bydmate.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.platform.LocalContext
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.service.UpdateChecker
import com.bydmate.app.ui.battery.BatteryHealthScreen
import com.bydmate.app.ui.automation.AutomationScreen
import com.bydmate.app.ui.places.PlacesScreen
import com.bydmate.app.ui.dashboard.DashboardScreen
import com.bydmate.app.ui.settings.SettingsScreen
import com.bydmate.app.ui.settings.UpdateDialog
import com.bydmate.app.ui.settings.UpdateState
import com.bydmate.app.ui.theme.*
import com.bydmate.app.ui.trips.TripsScreen
import com.bydmate.app.ui.welcome.WelcomeScreen
import kotlinx.coroutines.delay

enum class Screen(val route: String, val label: String, val icon: ImageVector) {
    Dashboard("dashboard", "Главная", Icons.Outlined.Home),
    Trips("trips", "Поездки", Icons.Outlined.DirectionsCar),
    Automation("automation", "Автоматизация", Icons.Outlined.Bolt),
    Settings("settings", "Настройки", Icons.Outlined.Settings)
}

@Composable
fun AppNavigation(
    settingsRepository: SettingsRepository,
    updateChecker: UpdateChecker
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    var startDestination by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        startDestination = if (settingsRepository.isSetupCompleted()) "dashboard" else "welcome"
    }

    if (startDestination == null) return // Loading

    // Автоматическая проверка обновлений: через 30с после открытия приложения
    // дёргаем GitHub, если включено в настройках и есть новая версия —
    // показываем стандартный UpdateDialog.
    val autoCheckContext = LocalContext.current
    var autoUpdateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    LaunchedEffect(Unit) {
        delay(30_000L)
        if (!UpdateChecker.isAutoCheckEnabled(autoCheckContext)) return@LaunchedEffect
        try {
            autoUpdateInfo = updateChecker.checkForUpdate(autoCheckContext, forceCheck = false)
        } catch (_: Exception) {
            // тихо игнорируем — оффлайн, rate-limit и т.п.
        }
    }
    autoUpdateInfo?.let { info ->
        val currentVersion = runCatching {
            autoCheckContext.packageManager.getPackageInfo(autoCheckContext.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
        UpdateDialog(
            currentVersion = currentVersion,
            state = UpdateState.Available(version = info.version, notes = info.releaseNotes),
            onCheck = {
                navController.navigate(Screen.Settings.route) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                }
                autoUpdateInfo = null
            },
            onDismiss = { autoUpdateInfo = null }
        )
    }

    val isWelcome = currentDestination?.route == "welcome"

    Scaffold(
        containerColor = NavyDark,
        bottomBar = {
            if (!isWelcome) {
                NavigationBar(
                    containerColor = NavBarBackground
                ) {
                    Screen.entries.forEach { screen ->
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    imageVector = screen.icon,
                                    contentDescription = screen.label
                                )
                            },
                            label = { Text(screen.label) },
                            selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AccentGreen,
                                selectedTextColor = AccentGreen,
                                unselectedIconColor = TextMuted,
                                unselectedTextColor = TextMuted,
                                indicatorColor = NavIndicator
                            )
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = startDestination!!,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable("welcome") {
                WelcomeScreen(
                    onComplete = {
                        navController.navigate("dashboard") {
                            popUpTo("welcome") { inclusive = true }
                        }
                    }
                )
            }
            composable(Screen.Dashboard.route) { DashboardScreen() }
            composable(Screen.Trips.route) { TripsScreen() }
            composable(Screen.Automation.route) { AutomationScreen() }
            composable(Screen.Settings.route) { SettingsScreen(onNavigateToPlaces = { navController.navigate("places") }) }
            composable("battery_health") { BatteryHealthScreen() }
            composable("places") { PlacesScreen(onBack = { navController.popBackStack() }) }
        }
    }
}
