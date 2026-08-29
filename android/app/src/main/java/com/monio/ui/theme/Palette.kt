package com.monio.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.HomeWork
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Category icon and colour are stored as short stable keys, not resource ids —
 * a resource id is not portable across builds and would not survive sync.
 */
object Palette {

    val colors: List<Pair<String, Color>> = listOf(
        "coral" to Color(0xFFE9605F),
        "amber" to Color(0xFFEEA23E),
        "gold" to Color(0xFFD9C048),
        "olive" to Color(0xFF8FAE4B),
        "green" to Color(0xFF43A171),
        "teal" to Color(0xFF3FA9A2),
        "sky" to Color(0xFF4E9FD1),
        "indigo" to Color(0xFF6C7BD1),
        "violet" to Color(0xFF9A6FD1),
        "pink" to Color(0xFFD96BA5),
        "brown" to Color(0xFFA07B5E),
        "slate" to Color(0xFF74868F),
    )

    private val colorMap = colors.toMap()

    fun color(key: String?): Color = colorMap[key] ?: colors[0].second

    /** A deterministic fallback so a category without a colour is still stable. */
    fun colorFor(key: String?, id: String): Color =
        colorMap[key] ?: colors[(id.hashCode().let { if (it < 0) -it else it }) % colors.size].second

    val icons: List<Pair<String, ImageVector>> = listOf(
        "groceries" to Icons.Filled.LocalGroceryStore,
        "food" to Icons.Filled.Fastfood,
        "transport" to Icons.Filled.DirectionsBus,
        "home" to Icons.Filled.HomeWork,
        "health" to Icons.Filled.LocalHospital,
        "fun" to Icons.Filled.Movie,
        "kids" to Icons.Filled.ChildCare,
        "school" to Icons.Filled.School,
        "clothes" to Icons.Filled.Checkroom,
        "phone" to Icons.Filled.Phone,
        "games" to Icons.Filled.SportsEsports,
        "pets" to Icons.Filled.Pets,
        "gift" to Icons.Filled.Redeem,
        "salary" to Icons.Filled.AttachMoney,
        "wallet" to Icons.Filled.Wallet,
        "other" to Icons.Filled.Category,
    )

    private val iconMap = icons.toMap()

    fun icon(key: String?): ImageVector = iconMap[key] ?: Icons.Filled.Category
}
