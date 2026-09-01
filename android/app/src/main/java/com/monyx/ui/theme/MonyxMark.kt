package com.monyx.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The Monyx logo: a disc cut by a cross into a top half and two bottom
 * quarters — a pie chart with the slices pulled apart, which is why it can
 * stand in for the one the overview tab used to carry.
 *
 * Built in Kotlin rather than loaded from a drawable because the tab list is a
 * top-level `val` of plain [ImageVector]s, and `vectorResource` is composable —
 * reading it from res would have meant rebuilding that list inside the
 * navigation bar for the sake of one icon.
 *
 * The corners are rounded by geometry INSET by k and stroked back out by 2k
 * with a round join: r-k stroked by 2k is r again, with every corner turned.
 * Writing the rounding into the path data instead is four arc tangencies per
 * piece, solved by hand.
 *
 * Kept in step with res/drawable/ic_launcher_foreground.xml, which is this
 * shape at a 108-unit viewport. Change one, change the other.
 *
 * Black, and tinted by whoever draws it — `Icon` replaces these colours with a
 * ColorFilter, so what is set here only shows through in a preview.
 */
val MonyxMark: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    val pieces = listOf(
        "M21.37,10.35 A9.52,9.52 0 0 0 2.63,10.35 Z",
        "M2.63,13.65 L10.35,13.65 L10.35,21.37 A9.52,9.52 0 0 1 2.63,13.65 Z",
        "M13.65,13.65 L21.37,13.65 A9.52,9.52 0 0 1 13.65,21.37 Z",
    )
    ImageVector.Builder(
        name = "MonyxMark",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        pieces.forEach { piece ->
            addPath(
                pathData = addPathNodes(piece),
                fill = SolidColor(Color.Black),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 0.97f,
                strokeLineJoin = StrokeJoin.Round,
                strokeLineCap = StrokeCap.Round,
            )
        }
    }.build()
}
