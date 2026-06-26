package com.spends.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.spends.app.data.settings.SettingsState
import com.spends.app.ui.addedit.AddEditScreen
import com.spends.app.ui.backup.OnboardingRestoreScreen
import com.spends.app.ui.capture.CaptureSettingsScreen
import com.spends.app.ui.categories.CategoriesScreen
import com.spends.app.ui.categorytxns.CategoryTransactionsScreen
import com.spends.app.ui.home.HomeScreen
import com.spends.app.ui.importer.ImportScreen
import com.spends.app.ui.onboarding.OnboardingScreen
import com.spends.app.ui.recurring.RecurringScreen
import com.spends.app.ui.review.ReviewScreen
import com.spends.app.ui.settings.SettingsScreen
import com.spends.app.ui.trash.TrashScreen

@Composable
fun SpendsNavHost(
    settings: SettingsState,
    pendingCaptureDraft: Boolean = false,
    onCaptureDraftConsumed: () -> Unit = {},
    pendingQuickAdd: Boolean = false,
    onQuickAddConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val start = if (settings.onboardingComplete) Routes.HOME else Routes.ONBOARDING

    // A capture-prompt "Edit" tap parsed an UNSAVED draft (held in CaptureDraftStore) — open the editor
    // on it; nothing is written until the user Saves (#4).
    LaunchedEffect(pendingCaptureDraft) {
        if (!pendingCaptureDraft) return@LaunchedEffect
        if (settings.onboardingComplete) navController.navigate(Routes.addEditDraft())
        onCaptureDraftConsumed()
    }

    NavHost(
        navController = navController,
        startDestination = start,
        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start) },
        exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start) },
        popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End) },
        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End) },
    ) {
        composable(Routes.ONBOARDING) {
            // A widget quick-add only makes sense once set up; if we're still onboarding, drop any
            // pending signal so it can't pop the sheet open later when Home first appears.
            LaunchedEffect(Unit) { if (pendingQuickAdd) onQuickAddConsumed() }
            OnboardingScreen(
                onImport = { navController.navigate(Routes.importRoute(fromOnboarding = true)) },
                onRestore = { navController.navigate(Routes.RESTORE) },
                onFinished = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.RESTORE) {
            OnboardingRestoreScreen(
                onBack = { navController.popBackStack() },
                onRestored = {
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
                onOpenRecurring = { navController.navigate(Routes.RECURRING) },
                onOpenCategory = { categoryId, name, start, end ->
                    navController.navigate(Routes.categoryTxns(categoryId, name, start, end))
                },
                openQuickAddSignal = pendingQuickAdd,
                onQuickAddConsumed = onQuickAddConsumed,
            )
        }

        composable(
            route = Routes.ADD_EDIT_PATTERN,
            arguments = listOf(
                navArgument(Routes.ARG_EXPENSE_ID) {
                    type = NavType.LongType
                    defaultValue = Routes.NO_EXPENSE_ID
                },
                navArgument(Routes.ARG_PENDING_ID) {
                    type = NavType.LongType
                    defaultValue = Routes.NO_PENDING_ID
                },
                navArgument(Routes.ARG_FROM_DRAFT) {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) {
            AddEditScreen(onDone = { navController.popBackStack() })
        }

        composable(
            route = Routes.CATEGORY_TXNS_PATTERN,
            arguments = listOf(
                navArgument(Routes.ARG_CATEGORY_ID) { type = NavType.LongType },
                navArgument(Routes.ARG_CATEGORY_NAME) { type = NavType.StringType },
                navArgument(Routes.ARG_PERIOD_START) { type = NavType.LongType },
                navArgument(Routes.ARG_PERIOD_END) { type = NavType.LongType },
            ),
        ) {
            CategoryTransactionsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.TRASH) {
            TrashScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenTrash = { navController.navigate(Routes.TRASH) },
                onOpenCategories = { navController.navigate(Routes.CATEGORIES) },
                onOpenImport = { navController.navigate(Routes.importRoute(fromOnboarding = false)) },
                onOpenRecurring = { navController.navigate(Routes.RECURRING) },
                onOpenCapture = { navController.navigate(Routes.CAPTURE) },
            )
        }

        composable(Routes.CAPTURE) {
            CaptureSettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenReview = { navController.navigate(Routes.REVIEW) },
            )
        }

        composable(Routes.CATEGORIES) {
            CategoriesScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.RECURRING) {
            RecurringScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.REVIEW) {
            ReviewScreen(
                onBack = { navController.popBackStack() },
                onEditPending = { id -> navController.navigate(Routes.addEditPending(id)) },
            )
        }

        composable(
            route = Routes.IMPORT_PATTERN,
            arguments = listOf(
                navArgument(Routes.ARG_FROM_ONBOARDING) {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { entry ->
            val fromOnboarding = entry.arguments?.getBoolean(Routes.ARG_FROM_ONBOARDING) ?: false
            ImportScreen(
                fromOnboarding = fromOnboarding,
                onBack = { navController.popBackStack() },
                onFinished = {
                    if (fromOnboarding) {
                        // Finishing import during onboarding drops the user straight into the app
                        // with their data, instead of bouncing back to the welcome step.
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    } else {
                        navController.popBackStack()
                    }
                },
            )
        }
    }
}
