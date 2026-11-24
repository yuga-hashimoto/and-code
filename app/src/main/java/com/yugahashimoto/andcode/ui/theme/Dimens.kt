package com.yugahashimoto.andcode.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Shared spacing scale. Screens used to pick paddings and gaps one literal at a time (2, 4, 5, 6,
 * 8, 10, 12, 14, 16, 20, 24 dp all appear), which is why sibling screens never quite lined up.
 * New UI takes its gaps from here; the steps follow a 4 dp grid with a 2 dp hairline. Existing
 * off-scale values move onto it when their screen is redesigned, not as a drive-by change,
 * because that shifts the layout.
 */
object Spacing {
    val hairline = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp

    /** Horizontal inset of a full-width screen's content from the display edge. */
    val screenEdge = lg
}

/** Shared corner radii, from small inline controls up to the fully rounded pill. */
object Radius {
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp

    /** Large enough to fully round any chip or button height the app uses. */
    val pill = 100.dp
}

/** Ready-made shapes for the [Radius] steps, so call sites don't rebuild them each time. */
object AppShapes {
    val sm = RoundedCornerShape(Radius.sm)
    val md = RoundedCornerShape(Radius.md)
    val lg = RoundedCornerShape(Radius.lg)
    val xl = RoundedCornerShape(Radius.xl)
    val pill = RoundedCornerShape(Radius.pill)
}
