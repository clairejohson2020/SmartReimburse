package com.smartreimburse.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.smartreimburse.data.AttachmentType
import com.smartreimburse.ui.screens.CameraCaptureScreen
import com.smartreimburse.ui.screens.ExpenseDetailScreen
import com.smartreimburse.ui.screens.ExpenseFormScreen
import com.smartreimburse.ui.screens.HomeScreen
import com.smartreimburse.viewmodel.SmartReimburseViewModel

@Composable
fun SmartReimburseNavHost(viewModel: SmartReimburseViewModel) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        enterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(260)
            )
        },
        exitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(220)
            )
        },
        popEnterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(260)
            )
        },
        popExitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(220)
            )
        }
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                viewModel = viewModel,
                onAddClick = { navController.navigate(Routes.newForm()) },
                onExpenseClick = { navController.navigate(Routes.detail(it)) }
            )
        }

        composable(route = Routes.NEW_FORM) {
            ExpenseFormScreen(
                expenseId = null,
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onSaved = { savedId ->
                    navController.navigate(Routes.detail(savedId)) {
                        popUpTo(Routes.HOME)
                    }
                },
                onOpenCamera = { type, runOcr ->
                    navController.navigate(Routes.camera(type, runOcr))
                }
            )
        }

        composable(
            route = Routes.EDIT_FORM,
            arguments = listOf(
                navArgument("expenseId") {
                    type = NavType.LongType
                }
            )
        ) { backStackEntry ->
            val expenseId = backStackEntry.arguments?.getLong("expenseId")
            ExpenseFormScreen(
                expenseId = expenseId,
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onSaved = { savedId ->
                    navController.navigate(Routes.detail(savedId)) {
                        popUpTo(Routes.HOME)
                    }
                },
                onOpenCamera = { type, runOcr ->
                    navController.navigate(Routes.camera(type, runOcr))
                }
            )
        }

        composable(
            route = Routes.DETAIL,
            arguments = listOf(navArgument("expenseId") { type = NavType.LongType })
        ) { backStackEntry ->
            val expenseId = backStackEntry.arguments?.getLong("expenseId") ?: return@composable
            ExpenseDetailScreen(
                expenseId = expenseId,
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate(Routes.editForm(expenseId)) },
                onDeleted = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Routes.CAMERA,
            arguments = listOf(
                navArgument("type") { type = NavType.StringType },
                navArgument("runOcr") { type = NavType.BoolType }
            )
        ) { backStackEntry ->
            val type = AttachmentType.valueOf(backStackEntry.arguments?.getString("type") ?: AttachmentType.RECEIPT.name)
            val runOcr = backStackEntry.arguments?.getBoolean("runOcr") ?: false
            CameraCaptureScreen(
                attachmentType = type,
                runOcr = runOcr,
                onBack = { navController.popBackStack() },
                onCaptured = { path, attachmentType, shouldRunOcr ->
                    viewModel.onCapturedImage(path, attachmentType, shouldRunOcr)
                    navController.popBackStack()
                }
            )
        }
    }
}
