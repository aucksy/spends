package com.spends.app.core.category

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.BakeryDining
import androidx.compose.material.icons.filled.BeachAccess
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.DinnerDining
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.EmojiFoodBeverage
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.House
import androidx.compose.material.icons.filled.Icecream
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalMall
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.LocalPizza
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Pool
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.RequestQuote
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.Toll
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Maps icon **keys** (assigned by [IconAssigner], or hand-picked by the user via the icon picker, #5)
 * to concrete Compose icons. [vectorFor] is the single resolver; [groups] is the curated, grouped
 * catalog the picker shows.
 *
 * IMPORTANT: the legacy keys (food, grocery, transport, …) keep their EXACT original icons so existing
 * categories never change icon on upgrade. New keys were only ADDED (never repointed) for the expanded
 * pack the user can choose from.
 */
object CategoryIcons {

    fun vectorFor(key: String): ImageVector = VECTORS[key] ?: Icons.Filled.Category

    /** A titled group of icon keys for the picker grid. */
    data class IconGroup(val title: String, val keys: List<String>)

    /** The grouped, user-pickable catalog (#5). Each key resolves via [vectorFor]; visuals are distinct. */
    val groups: List<IconGroup> = listOf(
        IconGroup("Food & drink", listOf("food", "fastfood", "coffee", "bar", "pizza", "dining", "dessert", "bakery", "drinks")),
        IconGroup("Shopping", listOf("shopping", "grocery", "store", "mall", "clothing", "gifts", "offer", "jewelry", "watch")),
        IconGroup("Transport", listOf("transport", "bus", "bike", "train", "taxi", "scooter", "fuel", "evcharge", "shipping")),
        IconGroup("Bills & home", listOf("bills", "utilities", "rent", "house", "wifi", "phone", "water", "electricity", "maintenance", "repair", "cleaning", "furniture", "tv")),
        IconGroup("Money", listOf("salary", "wallet", "bank", "card", "cash", "savings", "investments", "trending", "loan", "rupee", "tax", "refund", "cashback")),
        IconGroup("Health & fitness", listOf("health", "fitness", "hospital", "pharmacy", "medicine", "personal_care", "yoga", "sports", "running", "pool")),
        IconGroup("Leisure & travel", listOf("entertainment", "movie", "music", "games", "travel", "hotel", "beach", "park", "education", "books", "photography", "art", "pet", "party", "cake")),
        IconGroup("Work & more", listOf("business", "computer", "email", "subscriptions", "donation", "family", "child", "star", "award", "adjustment", "other")),
    )

    private val VECTORS: Map<String, ImageVector> = mapOf(
        // ---- Legacy keys: unchanged so existing categories keep their icons ----
        "food" to Icons.Filled.Restaurant,
        "grocery" to Icons.Filled.ShoppingCart,
        "shopping" to Icons.Filled.ShoppingBag,
        "entertainment" to Icons.Filled.PlayCircle,
        "health" to Icons.Filled.Favorite,
        "fitness" to Icons.Filled.FitnessCenter,
        "travel" to Icons.Filled.Flight,
        "fuel" to Icons.Filled.LocalGasStation,
        "utilities" to Icons.Filled.Bolt,
        "bills" to Icons.Filled.Receipt,
        "rent" to Icons.Filled.Home,
        "subscriptions" to Icons.Filled.Autorenew,
        "personal_care" to Icons.Filled.Spa,
        "education" to Icons.Filled.School,
        "investments" to Icons.Filled.ShowChart,
        "loan_emi" to Icons.Filled.AccountBalance,
        "gifts" to Icons.Filled.CardGiftcard,
        "transport" to Icons.Filled.DirectionsCar,
        "music" to Icons.Filled.MusicNote,
        "coffee" to Icons.Filled.LocalCafe,
        "pet" to Icons.Filled.Pets,
        "salary" to Icons.Filled.Payments,
        "business" to Icons.Filled.Work,
        "refund" to Icons.Filled.Replay,
        "interest" to Icons.Filled.Savings,
        "cashback" to Icons.Filled.Redeem,
        "car" to Icons.Filled.DirectionsCar,
        "maintenance" to Icons.Filled.Build,
        "office" to Icons.Filled.Work,
        "adjustment" to Icons.Filled.Tune,
        "clothing" to Icons.Filled.Checkroom,
        "fastfood" to Icons.Filled.Fastfood,
        // ---- New keys (expanded pack, #5) ----
        "bar" to Icons.Filled.LocalBar,
        "pizza" to Icons.Filled.LocalPizza,
        "dining" to Icons.Filled.DinnerDining,
        "dessert" to Icons.Filled.Icecream,
        "bakery" to Icons.Filled.BakeryDining,
        "drinks" to Icons.Filled.EmojiFoodBeverage,
        "store" to Icons.Filled.Storefront,
        "mall" to Icons.Filled.LocalMall,
        "offer" to Icons.Filled.LocalOffer,
        "jewelry" to Icons.Filled.Diamond,
        "watch" to Icons.Filled.Watch,
        "bus" to Icons.Filled.DirectionsBus,
        "bike" to Icons.Filled.DirectionsBike,
        "train" to Icons.Filled.Train,
        "taxi" to Icons.Filled.LocalTaxi,
        "scooter" to Icons.Filled.TwoWheeler,
        "evcharge" to Icons.Filled.EvStation,
        "shipping" to Icons.Filled.LocalShipping,
        "house" to Icons.Filled.House,
        "wifi" to Icons.Filled.Wifi,
        "phone" to Icons.Filled.Smartphone,
        "water" to Icons.Filled.WaterDrop,
        "electricity" to Icons.Filled.Lightbulb,
        "repair" to Icons.Filled.Handyman,
        "cleaning" to Icons.Filled.CleaningServices,
        "furniture" to Icons.Filled.Chair,
        "tv" to Icons.Filled.Tv,
        "wallet" to Icons.Filled.AccountBalanceWallet,
        "bank" to Icons.Filled.AccountBalance,
        "card" to Icons.Filled.CreditCard,
        "cash" to Icons.Filled.Paid,
        "savings" to Icons.Filled.Savings,
        "trending" to Icons.Filled.TrendingUp,
        "loan" to Icons.Filled.RequestQuote,
        "rupee" to Icons.Filled.CurrencyRupee,
        "tax" to Icons.Filled.Toll,
        "hospital" to Icons.Filled.LocalHospital,
        "pharmacy" to Icons.Filled.LocalPharmacy,
        "medicine" to Icons.Filled.Medication,
        "yoga" to Icons.Filled.SelfImprovement,
        "sports" to Icons.Filled.SportsSoccer,
        "running" to Icons.Filled.DirectionsRun,
        "pool" to Icons.Filled.Pool,
        "movie" to Icons.Filled.Theaters,
        "games" to Icons.Filled.SportsEsports,
        "hotel" to Icons.Filled.Hotel,
        "beach" to Icons.Filled.BeachAccess,
        "park" to Icons.Filled.Park,
        "books" to Icons.Filled.MenuBook,
        "photography" to Icons.Filled.PhotoCamera,
        "art" to Icons.Filled.Palette,
        "party" to Icons.Filled.Celebration,
        "cake" to Icons.Filled.Cake,
        "computer" to Icons.Filled.Computer,
        "email" to Icons.Filled.Email,
        "donation" to Icons.Filled.VolunteerActivism,
        "family" to Icons.Filled.FamilyRestroom,
        "child" to Icons.Filled.ChildCare,
        "star" to Icons.Filled.Star,
        "award" to Icons.Filled.EmojiEvents,
        "other" to Icons.Filled.Category,
    )
}
