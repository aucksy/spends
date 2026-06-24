package com.spends.app.core.category

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Weekend
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector

/** Maps the pure icon keys from [IconAssigner] to concrete Compose icons. */
object CategoryIcons {
    fun vectorFor(key: String): ImageVector = when (key) {
        "food" -> Icons.Filled.Fastfood
        "grocery" -> Icons.Filled.LocalGroceryStore
        "shopping" -> Icons.Filled.ShoppingBag
        "entertainment" -> Icons.Filled.Movie
        "health" -> Icons.Filled.MedicalServices
        "fitness" -> Icons.Filled.FitnessCenter
        "travel" -> Icons.Filled.Flight
        "fuel" -> Icons.Filled.LocalGasStation
        "utilities" -> Icons.Filled.Bolt
        "bills" -> Icons.Filled.Receipt
        "rent" -> Icons.Filled.Weekend
        "subscriptions" -> Icons.Filled.Subscriptions
        "personal_care" -> Icons.Filled.Spa
        "education" -> Icons.Filled.School
        "investments" -> Icons.Filled.TrendingUp
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
        else -> Icons.Filled.Label
    }
}
