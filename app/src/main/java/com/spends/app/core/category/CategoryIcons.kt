package com.spends.app.core.category

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Maps the pure icon keys from [IconAssigner] to concrete Compose icons, aligned with the Spends
 * Design System's Material Symbols set.
 */
object CategoryIcons {
    fun vectorFor(key: String): ImageVector = when (key) {
        "food" -> Icons.Filled.Restaurant
        "grocery" -> Icons.Filled.ShoppingCart
        "shopping" -> Icons.Filled.ShoppingBag
        "entertainment" -> Icons.Filled.PlayCircle
        "health" -> Icons.Filled.Favorite
        "fitness" -> Icons.Filled.FitnessCenter
        "travel" -> Icons.Filled.Flight
        "fuel" -> Icons.Filled.LocalGasStation
        "utilities" -> Icons.Filled.Bolt
        "bills" -> Icons.Filled.Receipt
        "rent" -> Icons.Filled.Home
        "subscriptions" -> Icons.Filled.Autorenew
        "personal_care" -> Icons.Filled.Spa
        "education" -> Icons.Filled.School
        "investments" -> Icons.Filled.ShowChart
        "loan_emi" -> Icons.Filled.AccountBalance
        "gifts" -> Icons.Filled.CardGiftcard
        "transport" -> Icons.Filled.DirectionsCar
        "music" -> Icons.Filled.MusicNote
        "coffee" -> Icons.Filled.LocalCafe
        "pet" -> Icons.Filled.Pets
        "salary" -> Icons.Filled.Payments
        "business" -> Icons.Filled.Work
        "refund" -> Icons.Filled.Replay
        "interest" -> Icons.Filled.Savings
        "cashback" -> Icons.Filled.Redeem
        "car" -> Icons.Filled.DirectionsCar
        "maintenance" -> Icons.Filled.Build
        "office" -> Icons.Filled.Work
        "adjustment" -> Icons.Filled.Tune
        "clothing" -> Icons.Filled.Checkroom
        "fastfood" -> Icons.Filled.Fastfood
        else -> Icons.Filled.Category
    }
}
