package com.spends.app.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spends.app.data.db.entity.PaymentMethodEntity
import com.spends.app.domain.model.PaymentMethodType
import com.spends.app.ui.components.parseHexColor

/**
 * Classify a payment method for the "Paid with" picker + Single-Card selector (#2): credit/debit cards
 * carry their own billing cycle; banks / UPI / wallets ride the salary cycle. Single source of truth so
 * every screen groups instruments the same way.
 */
fun PaymentMethodEntity.isCardInstrument(): Boolean =
    type == PaymentMethodType.CREDIT_CARD || type == PaymentMethodType.DEBIT_CARD

fun PaymentMethodEntity.toCardOption(): CardOption =
    CardOption(id = id, label = label, last4 = last4, colorHex = colorHex, isCard = isCardInstrument())

/**
 * A selectable instrument in the "Paid with" picker. A null selection means the generic "Bank" (the
 * salary-cycle bucket). [isCard] = a credit/debit card (its own billing cycle); false = a named bank
 * (rides the salary cycle) — used to group + caption the picker (#2).
 */
data class CardOption(val id: Long, val label: String, val last4: String?, val colorHex: String, val isCard: Boolean = true)

/**
 * What the entry screens need to render "Paid with": whether the feature is on, the available instruments,
 * and the [defaultId] a NEW expense pre-selects (#2; null = generic Bank).
 */
data class PaymentState(
    val enabled: Boolean = false,
    val cards: List<CardOption> = emptyList(),
    val defaultId: Long? = null,
)

/** A tiny colour swatch (or a Bank glyph for the null/Bank option). */
@Composable
private fun Swatch(colorHex: String?) {
    if (colorHex == null) {
        Icon(Icons.Filled.AccountBalance, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(parseHexColor(colorHex)))
    }
}

private fun CardOption.display(): String = label + (last4?.let { " ·$it" } ?: "")

/**
 * The full "PAID WITH" field row for the editor (Design System: Add-transaction screen). Shows the chosen
 * instrument with its colour swatch; tap to open the picker.
 */
@Composable
fun PaidWithField(selected: CardOption?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.CreditCard, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("PAID WITH", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Swatch(selected?.colorHex)
                Spacer(Modifier.size(6.dp))
                Text(
                    selected?.display() ?: "Bank",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A compact "Paid with" pill for the quick-add sheet (space is tight there). */
@Composable
fun PaidWithChip(selected: CardOption?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Swatch(selected?.colorHex)
        Text(
            selected?.display() ?: "Bank",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Bottom sheet to choose Bank (null) or one of the [cards]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaidWithPickerSheet(
    cards: List<CardOption>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    onDismiss: () -> Unit,
    // #3: when provided, the picker offers "Add a card / bank" rows. isBank distinguishes the two.
    onAddNew: ((isBank: Boolean) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val banks = cards.filter { !it.isCard }
    val creditCards = cards.filter { it.isCard }
    // Group headers only earn their place once there are cards to separate from banks (#4); with banks
    // alone the list stays flat.
    val grouped = creditCards.isNotEmpty()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // Scrolls so a long instrument list never hides the "Add a card / bank" rows at the foot (#3).
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp).padding(bottom = 24.dp)) {
            Text(
                "Paid with",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 8.dp),
            )
            // Banks & UPI (salary cycle): the generic Bank bucket + any named banks.
            if (grouped) PickerGroupHeader("Banks & UPI")
            PaidWithOptionRow(swatch = null, label = "Bank", caption = "Bank / UPI / cash · salary cycle", selected = selectedId == null) { onSelect(null) }
            banks.forEach { bank ->
                PaidWithOptionRow(
                    swatch = bank.colorHex,
                    label = bank.display(),
                    caption = "Bank · salary cycle",
                    selected = selectedId == bank.id,
                ) { onSelect(bank.id) }
            }
            // Credit cards (own billing cycle).
            if (grouped) PickerGroupHeader("Credit cards")
            creditCards.forEach { card ->
                PaidWithOptionRow(
                    swatch = card.colorHex,
                    label = card.display(),
                    caption = "Card · its own billing cycle",
                    selected = selectedId == card.id,
                ) { onSelect(card.id) }
            }

            // Add a new instrument WITHOUT leaving the entry (#3): the quick-add sheet stays mounted, so the
            // amount/category/note already typed survive; the caller auto-selects the new instrument on save.
            if (onAddNew != null) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                Spacer(Modifier.height(4.dp))
                AddInstrumentRow(Icons.Filled.CreditCard, "Add a credit card") { onAddNew(false) }
                AddInstrumentRow(Icons.Filled.AccountBalance, "Add a bank or UPI") { onAddNew(true) }
            }
        }
    }
}

/** An "add a card / bank" action row at the foot of the picker (#3): primary-tinted, no swatch. */
@Composable
private fun AddInstrumentRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun PickerGroupHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun PaidWithOptionRow(swatch: String?, label: String, caption: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Swatch(swatch)
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
