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
import com.spends.app.ui.breakdown.CycleBreakdownScreen
import com.spends.app.ui.cards.CardsScreen
import com.spends.app.ui.capture.CaptureSettingsScreen
import com.spends.app.ui.capture.NotificationDebugScreen
import com.spends.app.ui.capture.SilencedAlertsScreen
import com.spends.app.ui.capture.SmsDebugScreen
import com.spends.app.ui.categories.CategoriesScreen
import com.spends.app.ui.categorytxns.CategoryTransactionsScreen
import com.spends.app.ui.home.HomeScreen
import com.spends.app.ui.importer.ImportScreen
import com.spends.app.ui.onboarding.OnboardingScreen
import com.spends.app.ui.recurring.RecurringScreen
import com.spends.app.ui.review.ReviewScreen
import com.spends.app.ui.settings.AppearanceSettingsScreen
import com.spends.app.ui.settings.AutomaticSettingsScreen
import com.spends.app.ui.settings.BackupSettingsScreen
import com.spends.app.ui.settings.DataSettingsScreen
import com.spends.app.ui.settings.CurrencySettingsScreen
import com.spends.app.ui.settings.MoneySettingsScreen
import com.spends.app.ui.settings.SettingsScreen
import com.spends.app.ui.trash.TrashScreen

@Composable
fun SpendsNavHost(
    settings: SettingsState,
    pendingCaptureDraft: Boolean = false,
    onCaptureDraftConsumed: () -> Unit = {},
    pendingOpenExpenseId: Long? = null,
    onOpenExpenseConsumed: () -> Unit = {},
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

    // A "recurring added" notification's Edit tap (#3) — open that saved transaction. Unlike the draft above
    // the row already exists, so this is an ordinary edit route.
    LaunchedEffect(pendingOpenExpenseId) {
        val id = pendingOpenExpenseId ?: return@LaunchedEffect
        if (settings.onboardingComplete) navController.navigate(Routes.addEdit(id))
        onOpenExpenseConsumed()
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
                onOpenRecurring = { navController.navigate(Routes.recurring()) },
                onOpenCategory = { categoryId, name, cycleLabel, start, end ->
                    navController.navigate(Routes.categoryTxns(categoryId, name, cycleLabel, start, end))
                },
                onOpenBreakdown = { navController.navigate(Routes.CYCLE_BREAKDOWN) },
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
            AddEditScreen(
                onDone = { navController.popBackStack() },
                onOpenRule = { ruleId -> navController.navigate(Routes.recurring(ruleId)) },
            )
        }

        composable(
            route = Routes.CATEGORY_TXNS_PATTERN,
            arguments = listOf(
                navArgument(Routes.ARG_CATEGORY_ID) { type = NavType.LongType },
                navArgument(Routes.ARG_CATEGORY_NAME) { type = NavType.StringType },
                navArgument(Routes.ARG_PERIOD_START) { type = NavType.LongType },
                navArgument(Routes.ARG_PERIOD_END) { type = NavType.LongType },
                navArgument(Routes.ARG_CYCLE_LABEL) { type = NavType.StringType; defaultValue = "" },
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
                onOpenMoney = { navController.navigate(Routes.SETTINGS_MONEY) },
                onOpenCurrency = { navController.navigate(Routes.SETTINGS_CURRENCY) },
                onOpenAutomatic = { navController.navigate(Routes.SETTINGS_AUTOMATIC) },
                onOpenCategories = { navController.navigate(Routes.CATEGORIES) },
                onOpenAppearance = { navController.navigate(Routes.SETTINGS_APPEARANCE) },
                onOpenBackup = { navController.navigate(Routes.SETTINGS_BACKUP) },
                onOpenData = { navController.navigate(Routes.SETTINGS_DATA) },
            )
        }

        composable(Routes.SETTINGS_MONEY) {
            MoneySettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenBanksCards = { navController.navigate(Routes.BANKS_CARDS) },
            )
        }

        composable(Routes.SETTINGS_CURRENCY) {
            CurrencySettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS_AUTOMATIC) {
            AutomaticSettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenCapture = { navController.navigate(Routes.CAPTURE) },
                onOpenRecurring = { navController.navigate(Routes.recurring()) },
            )
        }

        composable(Routes.SETTINGS_APPEARANCE) {
            AppearanceSettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS_BACKUP) {
            BackupSettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS_DATA) {
            DataSettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenImport = { navController.navigate(Routes.importRoute(fromOnboarding = false)) },
                onOpenTrash = { navController.navigate(Routes.TRASH) },
            )
        }

        composable(Routes.BANKS_CARDS) {
            CardsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.CAPTURE) {
            CaptureSettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenReview = { navController.navigate(Routes.REVIEW) },
                onOpenSilencedAlerts = { navController.navigate(Routes.SILENCED_ALERTS) },
                onOpenNotificationDebug = { navController.navigate(Routes.NOTIFICATION_DEBUG) },
                onOpenSmsDebug = { navController.navigate(Routes.SMS_DEBUG) },
            )
        }

        composable(Routes.SILENCED_ALERTS) {
            SilencedAlertsScreen(
                onBack = { navController.popBackStack() },
                onOpenReview = { navController.navigate(Routes.REVIEW) },
            )
        }

        // TEMPORARY diagnostic screen — remove with NotificationDebugLog.
        composable(Routes.NOTIFICATION_DEBUG) {
            NotificationDebugScreen(onBack = { navController.popBackStack() })
        }

        // TEMPORARY diagnostic screen — remove with SmsDebugLog.
        composable(Routes.SMS_DEBUG) {
            SmsDebugScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.CATEGORIES) {
            CategoriesScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.RECURRING_PATTERN,
            arguments = listOf(
                navArgument(Routes.ARG_RULE_ID) {
                    type = NavType.LongType
                    defaultValue = Routes.NO_RULE_ID
                },
            ),
        ) { entry ->
            val ruleId = entry.arguments?.getLong(Routes.ARG_RULE_ID) ?: Routes.NO_RULE_ID
            RecurringScreen(
                onBack = { navController.popBackStack() },
                openRuleId = ruleId.takeIf { it != Routes.NO_RULE_ID },
            )
        }

        composable(Routes.CYCLE_BREAKDOWN) {
            CycleBreakdownScreen(onBack = { navController.popBackStack() })
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
