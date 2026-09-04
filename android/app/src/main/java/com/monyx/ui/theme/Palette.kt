package com.monyx.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Church
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HomeWork
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalLaundryService
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.LocalPizza
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material.icons.filled.Water
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WorkOutline
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

    /**
     * A subcategory takes its parent's colour BY DEFAULT, so "Dom" and
     * "Dom > Remonty" read as one family rather than two unrelated blobs. The
     * parent's OWN key is used for the fallback too — otherwise a parent with
     * no colour set would hash to one colour and its children to another.
     *
     * By default, not always. A child that has been given a colour of its own
     * keeps it. This used to ignore [childColor] outright whenever there was a
     * parent, which made the colour picker in the subcategory dialog a control
     * that silently did nothing — you chose violet, saved, and the row stayed
     * brown. Inheriting is the default because most subcategories are never
     * given a colour at all; overriding it is the point of offering the choice.
     */
    fun colorForChild(childColor: String?, parentColor: String?, parentId: String?, ownId: String): Color =
        when {
            childColor != null -> colorFor(childColor, ownId)
            parentId != null -> colorFor(parentColor, parentId)
            else -> colorFor(null, ownId)
        }

    /**
     * Keys are stable and synced; the ImageVector behind one may be swapped, a
     * key may be added, but a key already written to a row must never be
     * removed or repointed at something unrelated.
     */
    val icons: List<Pair<String, ImageVector>> = listOf(
        // Original sixteen — these keys are in the seed data and on real rows.
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
        // Added so the picker is worth browsing rather than settling from.
        "restaurant" to Icons.Filled.Restaurant,
        "cafe" to Icons.Filled.LocalCafe,
        "bar" to Icons.Filled.LocalBar,
        "pizza" to Icons.Filled.LocalPizza,
        "cake" to Icons.Filled.Cake,
        "car" to Icons.Filled.DirectionsCar,
        "fuel" to Icons.Filled.LocalGasStation,
        "parking" to Icons.Filled.LocalParking,
        "train" to Icons.Filled.Train,
        "bike" to Icons.Filled.DirectionsBike,
        "flight" to Icons.Filled.Flight,
        "hotel" to Icons.Filled.Hotel,
        "shopping" to Icons.Filled.ShoppingBag,
        "pharmacy" to Icons.Filled.LocalPharmacy,
        "gym" to Icons.Filled.FitnessCenter,
        "spa" to Icons.Filled.Spa,
        "sport" to Icons.Filled.SportsSoccer,
        "music" to Icons.Filled.MusicNote,
        "audio" to Icons.Filled.Headphones,
        "tv" to Icons.Filled.Tv,
        "books" to Icons.Filled.MenuBook,
        "computer" to Icons.Filled.Computer,
        "mobile" to Icons.Filled.Smartphone,
        "internet" to Icons.Filled.Wifi,
        "power" to Icons.Filled.Bolt,
        "water" to Icons.Filled.Water,
        "laundry" to Icons.Filled.LocalLaundryService,
        "repairs" to Icons.Filled.Handyman,
        "tools" to Icons.Filled.Build,
        "garden" to Icons.Filled.LocalFlorist,
        "beauty" to Icons.Filled.Brush,
        "insurance" to Icons.Filled.Umbrella,
        "bank" to Icons.Filled.AccountBalance,
        "card" to Icons.Filled.CreditCard,
        "savings" to Icons.Filled.Savings,
        "work" to Icons.Filled.WorkOutline,
        "church" to Icons.Filled.Church,
    )

    private val iconMap = icons.toMap()

    fun icon(key: String?): ImageVector = iconMap[key] ?: Icons.Filled.Category
}
