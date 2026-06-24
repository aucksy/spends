package com.spends.app.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spends.app.ui.components.NumberWheelPicker

@Composable
fun OnboardingScreen(
    onImport: () -> Unit,
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    // Saveable so a side-trip (e.g. into Import) returns to the same step instead of step 0.
    var step by rememberSaveable { mutableIntStateOf(0) }
    // Data-setup choice: 0 = start fresh, 1 = import from Excel (Int so it survives config changes).
    var dataChoice by rememberSaveable { mutableIntStateOf(0) }
    val lastStep = 3
    val salaryDay by viewModel.salaryDay.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Column(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = step,
                transitionSpec = { (slideInFade()) togetherWith slideOutFade() },
                modifier = Modifier.weight(1f),
                label = "onboarding-step",
            ) { current ->
                when (current) {
                    0 -> WelcomeStep()
                    // Salary BEFORE data-setup so importing users (who finish via the import flow,
                    // skipping later steps) still configure their salary cycle.
                    1 -> SalaryStep(salaryDay = salaryDay, onSelect = viewModel::setSalaryDay)
                    2 -> DataSetupStep(selectedIndex = dataChoice, onSelect = { dataChoice = it })
                    else -> CaptureStep()
                }
            }

            StepDots(count = lastStep + 1, current = step)
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step > 0) {
                    TextButton(onClick = { step-- }) { Text("Back") }
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                Button(
                    shape = RoundedCornerShape(999.dp),
                    onClick = {
                        when {
                            // Data-setup step (2): "Import from Excel" routes into the import flow.
                            step == 2 && dataChoice == 1 -> onImport()
                            step < lastStep -> {
                                if (step == 1) viewModel.persistSalaryDay() // leaving the salary step
                                step++
                            }
                            else -> viewModel.finish(onFinished)
                        }
                    },
                ) {
                    Text(if (step < lastStep) "Continue" else "Get started")
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

private fun slideInFade() = androidx.compose.animation.fadeIn() +
    androidx.compose.animation.slideInHorizontally(initialOffsetX = { it / 4 })

private fun slideOutFade() = androidx.compose.animation.fadeOut() +
    androidx.compose.animation.slideOutHorizontally(targetOffsetX = { -it / 4 })

/** A large rounded-square icon badge in the primary tint — the recurring visual anchor per step. */
@Composable
private fun StepBadge(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(32.dp),
        )
    }
}

@Composable
private fun StepScaffold(
    badge: ImageVector,
    title: String,
    subtitle: String,
    content: @Composable () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(8.dp))
        StepBadge(badge)
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(10.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        content()
    }
}

@Composable
private fun WelcomeStep() {
    StepScaffold(
        badge = Icons.Filled.Savings,
        title = "Welcome to Spends",
        subtitle = "Track every rupee, effortlessly — income, spends and transfers in one calm place.",
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    "Your privacy promise: everything stays on your device. No account, no ads, no tracking.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun DataSetupStep(selectedIndex: Int, onSelect: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(8.dp))
        Text("How do you want to start?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(10.dp))
        Text(
            "You can always change this later. Nothing leaves your phone.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        SetupOptionCard(
            selected = selectedIndex == 0,
            enabled = true,
            icon = Icons.Filled.AutoAwesome,
            badgeBg = MaterialTheme.colorScheme.primaryContainer,
            badgeTint = MaterialTheme.colorScheme.onPrimaryContainer,
            title = "Start fresh",
            subtitle = "Add transactions as they happen. Recommended.",
            onClick = { onSelect(0) },
        )
        Spacer(Modifier.height(12.dp))
        SetupOptionCard(
            selected = selectedIndex == 1,
            enabled = true,
            icon = Icons.Filled.TableChart,
            badgeBg = MaterialTheme.colorScheme.surfaceVariant,
            badgeTint = androidx.compose.ui.graphics.Color(0xFF3F6212), // design olive accent
            title = "Import from Excel",
            subtitle = "Bring your Monito (.xls) or any CSV history — every category preserved.",
            onClick = { onSelect(1) },
        )
        Spacer(Modifier.height(12.dp))
        SetupOptionCard(
            selected = false,
            enabled = false,
            icon = Icons.Filled.CloudDownload,
            badgeBg = MaterialTheme.colorScheme.surfaceVariant,
            badgeTint = MaterialTheme.colorScheme.outline,
            title = "Restore from Drive",
            subtitle = "Available from Settings → Backup once you're set up.",
            onClick = {},
        )
    }
}

@Composable
private fun SetupOptionCard(
    selected: Boolean,
    enabled: Boolean,
    icon: ImageVector,
    badgeBg: androidx.compose.ui.graphics.Color,
    badgeTint: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val border = androidx.compose.foundation.BorderStroke(
        1.5.dp,
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
    )
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth().then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = border,
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(badgeBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = badgeTint, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                )
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(10.dp))
            Icon(
                if (selected) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun SalaryStep(salaryDay: Int, onSelect: (Int) -> Unit) {
    StepScaffold(
        badge = Icons.Filled.Savings,
        title = "When do you get paid?",
        subtitle = "Your bank and UPI spending is grouped by your salary date — so the total you see is " +
            "what you're actually about to pay this cycle, not just this calendar month.",
    ) {
        Text("Salary day", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        NumberWheelPicker(
            value = salaryDay,
            range = 1..31,
            onValueChange = onSelect,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "You get paid on the ${ordinal(salaryDay)}. You can change this anytime in Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CaptureStep() {
    StepScaffold(
        badge = Icons.Filled.NotificationsActive,
        title = "Automatic capture",
        subtitle = "Soon, Spends can read bank SMS and UPI app notifications to pre-fill your transactions — " +
            "you just pick a category and save. Permissions are asked only when you turn capture on, and " +
            "nothing ever leaves your device.",
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Text(
                "For now, add transactions with the + button, or import your history. Everything else is ready to go.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun StepDots(count: Int, current: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(count) { index ->
            val active = index == current
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(if (active) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
        }
    }
}

private fun ordinal(day: Int): String {
    val suffix = when {
        day in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$day$suffix"
}
