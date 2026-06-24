package com.spends.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.spends.app.data.settings.SettingsState
import com.spends.app.ui.addedit.AddEditScreen
import com.spends.app.ui.categories.CategoriesScreen
import com.spends.app.ui.home.HomeScreen
import com.spends.app.ui.onboarding.OnboardingScreen
import com.spends.app.ui.settings.SettingsScreen
import com.spends.app.ui.trash.TrashScreen

@Composable
fun SpendsNavHost(settings: SettingsState) {
    val navController = rememberNavController()
    val start = if (settings.onboardingComplete) Routes.HOME else Routes.ONBOARDING

    NavHost(
        navController = navController,
        startDestination = start,
        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start) },
        exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start) },
        popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End) },
        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End) },
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onFinished = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                settings = settings,
                onAddTransaction = { navController.navigate(Routes.addEdit()) },
                onEditTransaction = { id -> navController.navigate(Routes.addEdit(id)) },
                onOpenTrash = { navController.navigate(Routes.TRASH) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(
            route = Routes.ADD_EDIT_PATTERN,
            arguments = listOf(
                navArgument(Routes.ARG_EXPENSE_ID) {
                    type = NavType.LongType
                    defaultValue = Routes.NO_EXPENSE_ID
                },
            ),
        ) {
            AddEditScreen(onDone = { navController.popBackStack() })
        }

        composable(Routes.TRASH) {
            TrashScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenTrash = { navController.navigate(Routes.TRASH) },
                onOpenCategories = { navController.navigate(Routes.CATEGORIES) },
            )
        }

        composable(Routes.CATEGORIES) {
            CategoriesScreen(onBack = { navController.popBackStack() })
        }
    }
}
