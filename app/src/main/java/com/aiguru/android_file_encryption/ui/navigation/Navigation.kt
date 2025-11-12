package com.aiguru.android_file_encryption.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aiguru.android_file_encryption.ui.screens.FilePickerScreen
import com.aiguru.android_file_encryption.ui.screens.CloudFilesScreen
import com.aiguru.android_file_encryption.ui.screens.SettingsScreen
import com.aiguru.android_file_encryption.ui.screens.AuthScreen

sealed class Screen(val route: String) {
    object Auth : Screen("auth")
    object FilePicker : Screen("file_picker")
    object CloudFiles : Screen("cloud_files")
    object Settings : Screen("settings")
}

@Composable
fun AppNavigation(modifier: Modifier = Modifier) {
    val navController = rememberNavController()

    NavigationGraph(navController = navController)
}

@Composable
fun NavigationGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Auth.route
    ) {
        composable(Screen.Auth.route) {
            AuthScreen(
                onAuthSuccess = {
                    navController.navigate(Screen.FilePicker.route) {
                        popUpTo(Screen.Auth.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.FilePicker.route) {
            FilePickerScreen(
                onFileSelected = { uri ->
                    // Handle file selection
                },
                onNavigateToEncryptedFiles = {
                    navController.navigate(Screen.CloudFiles.route)
                }
            )
        }

        composable(Screen.CloudFiles.route) {
            CloudFilesScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onFileDownload = { cloudFileInfo ->
                    // Handle file download
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}