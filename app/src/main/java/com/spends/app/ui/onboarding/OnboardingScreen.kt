package com.spends.app.ui.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spends.app.core.theme.LocalSemanticColors

/**
 * Onboarding (#10), per the "Spends Onboarding Flow" design: Welcome → SMS permission → Battery →
 * Salary → Setup. The Welcome screen intentionally shows aspirational copy (cards / My Cycle) the app
 * will grow into. SMS + battery permissions are requested inline; salary uses a day grid.
 */
@Composable
fun OnboardingScreen(
    onImport: () -> Unit,
    onRestore: () -> Unit,
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    // 0 Welcome · 1 SMS · 2 Battery · 3 Salary · 4 Setup. Saveable so a side-trip returns to the same step.
    var step by rememberSaveable { mutableIntStateOf(0) }
    var dataChoice by rememberSaveable { mutableIntStateOf(0) } // 0 fresh · 1 import · 2 restore
    var autoCapture by rememberSaveable { mutableStateOf(true) }
    var scanPast by rememberSaveable { mutableStateOf(false) }
    val salaryDay by viewModel.salaryDay.collectAsStateWithLifecycle()
    val lastStep = 4

    // The notification permission is requested right after SMS (still on THIS screen), and we only
    // advance to the next step once the user has answered it — so the prompt never appears over the
    // Battery screen the way it used to (#4).
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { step = 2 }
    fun requestNotifThenAdvance() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            step = 2
        }
    }

    val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.READ_SMS] == true && result[Manifest.permission.RECEIVE_SMS] == true
        if (granted) {
            viewModel.enableCaptureAndScan(scanPast)
            requestNotifThenAdvance()
        } else {
            step = 2 // continue regardless — capture just won't run if denied
        }
    }
    fun hasSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    fun enableCaptureAndAdvance() {
        if (autoCapture || scanPast) {
            if (hasSmsPermission()) {
                viewModel.enableCaptureAndScan(scanPast); requestNotifThenAdvance()
            } else {
                smsLauncher.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))
            }
        } else {
            step = 2
        }
    }

    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { step = 3 }
    fun requestBattery() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
        runCatching { batteryLauncher.launch(intent) }.onFailure { step = 3 }
    }

    val primaryLabel: String
    val primaryAction: () -> Unit
    var secondaryLabel: String? = null
    var secondaryAction: () -> Unit = {}
    when (step) {
        0 -> { primaryLabel = "Get started"; primaryAction = { step = 1 } }
        1 -> {
            primaryLabel = "Enable & continue"; primaryAction = { enableCaptureAndAdvance() }
            secondaryLabel = "Not now — I'll add manually"; secondaryAction = { step = 2 }
        }
        2 -> {
            primaryLabel = "Allow"; primaryAction = { requestBattery() }
            secondaryLabel = "Skip"; secondaryAction = { step = 3 }
        }
        3 -> { primaryLabel = "Confirm salary day"; primaryAction = { viewModel.persistSalaryDay(); step = 4 } }
        else -> {
            primaryLabel = "Continue"
            primaryAction = {
                when (dataChoice) {
                    1 -> onImport()
                    2 -> onRestore()
                    else -> viewModel.finish(onFinished)
                }
            }
        }
    }

    // System back steps backward (and exits from Welcome) — pairs with the header back arrow (#3).
    BackHandler(enabled = step > 0) { step -= 1 }

    // Explicit theme background so the onboarding always sits on the light paper (it doesn't use a
    // Scaffold, so without this it would show the window background — dark on a dark device). The screen
    // is edge-to-edge, so inset for the status/nav bars — otherwise the logo + headers slide under the
    // status bar on every step (#2.1/#2.2).
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(24.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top header (steps 1..4): back arrow + the design's 3 dots on the left, a "Skip" link on the
            // right (no Skip on the final Setup step). The extra Battery step shares the first dot with SMS.
            if (step in 1..lastStep) {
                val dotIndex = when (step) { 1, 2 -> 0; 3 -> 1; else -> 2 }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp).clickable { step -= 1 },
                        )
                        Spacer(Modifier.width(12.dp))
                        StepDots(count = 3, current = dotIndex)
                    }
                    if (step != lastStep) {
                        Text(
                            "Skip",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // Skipping the Salary step must still persist the picked day (mirrors "Confirm
                            // salary day") — otherwise an importer silently loses it back to the default.
                            modifier = Modifier.clickable { if (step == 3) viewModel.persistSalaryDay(); step += 1 },
                        )
                    } else {
                        Spacer(Modifier.size(1.dp)) // keep the dots left-aligned via SpaceBetween
                    }
                }
            }
            AnimatedContent(
                targetState = step,
                transitionSpec = { slideInFade() togetherWith slideOutFade() },
                modifier = Modifier.weight(1f),
                label = "onboarding-step",
            ) { current ->
                when (current) {
                    0 -> WelcomeStep()
                    1 -> SmsPermissionStep(
                        autoCapture = autoCapture,
                        scanPast = scanPast,
                        onToggleAuto = { autoCapture = it },
                        onToggleScan = { scanPast = it },
                    )
                    2 -> BatteryStep()
                    3 -> SalaryStep(salaryDay = salaryDay, onSelect = viewModel::setSalaryDay)
                    else -> SetupStep(selectedIndex = dataChoice, onSelect = { dataChoice = it })
                }
            }

            Button(
                onClick = primaryAction,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(primaryLabel)
                if (step == 0) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
            secondaryLabel?.let { label ->
                TextButton(onClick = secondaryAction, modifier = Modifier.fillMaxWidth()) { Text(label) }
            }
        }
    }
}

private fun slideInFade() = androidx.compose.animation.fadeIn() +
    androidx.compose.animation.slideInHorizontally(initialOffsetX = { it / 4 })

private fun slideOutFade() = androidx.compose.animation.fadeOut() +
    androidx.compose.animation.slideOutHorizontally(targetOffsetX = { -it / 4 })

// ---- Steps ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WelcomeStep() {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BarsMark(size = 38.dp)
            Spacer(Modifier.width(10.dp))
            Text("Spends", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.height(30.dp))
        Text(
            "Every card and account, in one calm view.",
            style = MaterialTheme.typography.headlineMedium,
            fontSize = 29.sp, // design h1 (headlineMedium is only 24sp)
            lineHeight = 34.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Spends reads your bank alerts on-device and sorts your spending by your own billing & salary " +
                "cycles. No logins, no uploads.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(22.dp))
        // Aspirational preview card (decorative — multi-card / My Cycle land in a later phase). Full-width
        // to match the design (it previously wrapped its content and looked short) (#2.1).
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(
                    "BALANCE · MY CYCLE",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                )
                Text("₹1,24,500", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    PreviewStat("Expense", "₹83,200")
                    PreviewStat("Income", "₹2,07,700")
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // Flow-wrap so the three chips keep their natural size and wrap (design uses flex-wrap); a plain
        // Row squeezed them and made "+11 more" mismatch HDFC/ICICI (#2.1).
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AccountChip(Color(0xFF60286E), "HDFC ·4821")
            AccountChip(Color(0xFF004C8F), "ICICI ·9032")
            AccountChip(MaterialTheme.colorScheme.primary, "+11 more")
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = LocalSemanticColors.current.income, modifier = Modifier.size(15.dp))
            Text("Your data never leaves your phone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PreviewStat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
private fun AccountChip(dotColor: Color, label: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(dotColor))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun SmsPermissionStep(
    autoCapture: Boolean,
    scanPast: Boolean,
    onToggleAuto: (Boolean) -> Unit,
    onToggleScan: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        StepBadge(Icons.Filled.Sms)
        Spacer(Modifier.height(20.dp))
        Text("Capture spends from SMS", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(10.dp))
        Text(
            "Spends reads bank SMS entirely on your phone — no logins, no uploads. Choose what it does:",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        ToggleCard(
            selected = autoCapture,
            icon = Icons.Filled.NotificationsActive,
            badgeBg = MaterialTheme.colorScheme.primaryContainer,
            badgeTint = MaterialTheme.colorScheme.primary, // design glyph is primary teal #0F766E on #CFEEE9
            title = "Auto-capture new SMS",
            subtitle = "When a bank SMS arrives, get a notification to add the spend in one tap.",
            checked = autoCapture,
            onCheckedChange = onToggleAuto,
        )
        Spacer(Modifier.height(12.dp))
        ToggleCard(
            selected = scanPast,
            icon = Icons.Filled.History,
            badgeBg = MaterialTheme.colorScheme.surfaceVariant,
            badgeTint = Color(0xFF3F6212),
            title = "Scan past SMS",
            subtitle = "One-time read of older bank texts to fill in spends you've already made.",
            checked = scanPast,
            onCheckedChange = onToggleScan,
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(Icons.Filled.FilterAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
            Text(
                "Only money messages are read — OTPs, promos and personal texts are ignored.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToggleCard(
    selected: Boolean,
    icon: ImageVector,
    badgeBg: Color,
    badgeTint: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.5.dp,
            if (selected) MaterialTheme.colorScheme.primary else Color(0xFFE3DED2),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Top-aligned (design align-items:flex-start): the badge + switch sit beside the title's first
        // line, not centred against a 2–3 line subtitle.
        Row(modifier = Modifier.padding(15.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(badgeBg),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = badgeTint, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(10.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun BatteryStep() {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        StepBadge(Icons.Filled.BatteryChargingFull)
        Spacer(Modifier.height(20.dp))
        Text("Keep capture running", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(10.dp))
        Text(
            "Some phones stop background apps and make Spends miss bank SMS. Letting it ignore battery " +
                "optimisation keeps capture reliable — change it anytime in Settings.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Text(
                "Optional — skip it if you'll add transactions manually. It only affects background capture.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun SalaryStep(salaryDay: Int, onSelect: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        StepBadge(Icons.Filled.EventRepeat)
        Spacer(Modifier.height(18.dp))
        Text("When does your salary land?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your salary cycle starts on this day each month — it's how Spends groups income & spending.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        "SALARY DAY",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                    )
                    Text(
                        "${ordinal(salaryDay)} of every month",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Icon(
                    Icons.Filled.Payments,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("PICK A DAY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        DayGrid(selected = salaryDay, onSelect = onSelect)
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Exact design pill: warm #F7F5EF fill + #D9D4C8 dashed border (onboarding is pinned light).
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFFF7F5EF))
                .dashedBorder(Color(0xFFD9D4C8), 10.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = LocalSemanticColors.current.review,
                modifier = Modifier.size(17.dp),
            )
            Text(
                "Last few days of the month auto-adjust for Feb & 30-day months.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DayGrid(selected: Int, onSelect: (Int) -> Unit) {
    // 7-column grid of days 1..31 (design screen 4). Plain Column of Rows so it nests in a scroll.
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        (1..31).chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    val isSel = day == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                            .then(if (isSel) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)))
                            .clickable { onSelect(day) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "$day",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                // pad the final short row so the cells keep their width
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SetupStep(selectedIndex: Int, onSelect: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("How do you want to start?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "You can change this later. Nothing leaves your phone.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        SetupOptionCard(
            selected = selectedIndex == 0,
            icon = Icons.Filled.AutoAwesome,
            badgeTint = MaterialTheme.colorScheme.onPrimaryContainer,
            badgeBg = MaterialTheme.colorScheme.primaryContainer,
            title = "Start fresh",
            subtitle = "Auto-capture begins now. Recommended.",
            onClick = { onSelect(0) },
        )
        Spacer(Modifier.height(12.dp))
        SetupOptionCard(
            selected = selectedIndex == 1,
            icon = Icons.Filled.TableChart,
            badgeTint = Color(0xFF3F6212),
            badgeBg = MaterialTheme.colorScheme.surfaceVariant,
            title = "Import from Excel",
            subtitle = "Bring your existing spreadsheet history.",
            onClick = { onSelect(1) },
        )
        Spacer(Modifier.height(12.dp))
        SetupOptionCard(
            selected = selectedIndex == 2,
            icon = Icons.Filled.CloudDownload,
            badgeTint = Color(0xFF1D4ED8),
            badgeBg = MaterialTheme.colorScheme.surfaceVariant,
            title = "Restore from Drive",
            subtitle = "Bring back a Google Drive backup — transactions, categories and settings.",
            onClick = { onSelect(2) },
        )
    }
}

@Composable
private fun SetupOptionCard(
    selected: Boolean,
    icon: ImageVector,
    badgeBg: Color,
    badgeTint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.5.dp,
            if (selected) MaterialTheme.colorScheme.primary else Color(0xFFE3DED2),
        ),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(badgeBg),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = badgeTint, modifier = Modifier.size(24.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
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

// ---- Shared bits ----

@Composable
private fun StepBadge(icon: ImageVector) {
    Box(
        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp))
    }
}

/** The small descending-bars brand mark used on the Welcome header. */
@Composable
private fun BarsMark(size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        // Design mark: a white lead bar with two light-teal (#A7E0D8) descending bars (not translucent
        // white, which read as a washed-out grey) (#2.1).
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(size * 0.08f)) {
            val lead = MaterialTheme.colorScheme.onPrimary
            val trail = Color(0xFFA7E0D8)
            Box(Modifier.width(size * 0.12f).height(size * 0.45f).clip(RoundedCornerShape(1.dp)).background(lead))
            Box(Modifier.width(size * 0.12f).height(size * 0.32f).clip(RoundedCornerShape(1.dp)).background(trail))
            Box(Modifier.width(size * 0.12f).height(size * 0.19f).clip(RoundedCornerShape(1.dp)).background(trail))
        }
    }
}

@Composable
private fun StepDots(count: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            val active = index == current
            Box(
                modifier = Modifier
                    .height(7.dp)
                    .width(if (active) 20.dp else 7.dp)
                    .clip(CircleShape)
                    .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

/** A 1dp dashed rounded border (design's amber info pill), drawn behind the content. */
private fun Modifier.dashedBorder(color: Color, radius: androidx.compose.ui.unit.Dp): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 6f))),
        cornerRadius = CornerRadius(radius.toPx()),
    )
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
