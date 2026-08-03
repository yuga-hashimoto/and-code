package com.yugahashimoto.andcode.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val DrawerEdgeWidth = 48.dp

internal fun isDrawerGestureAllowed(
    startX: Float,
    edgeWidthPx: Float,
    drawerIsOpen: Boolean,
): Boolean = drawerIsOpen || startX in 0f..edgeWidthPx

/**
 * Keeps the drawer's built-in drag behavior while limiting its gesture start area to the leading
 * edge. The drawer remains responsible for tracking the finger and settling to an anchor.
 */
internal fun Modifier.onlyAllowDrawerEdgeSwipe(
    edgeWidth: Dp = DrawerEdgeWidth,
    drawerIsOpen: Boolean = false,
): Modifier =
    pointerInput(edgeWidth, drawerIsOpen) {
        val edgeWidthPx = edgeWidth.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (isDrawerGestureAllowed(down.position.x, edgeWidthPx, drawerIsOpen)) {
                return@awaitEachGesture
            }

            awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                if (overSlop > 0f) change.consume()
            }
        }
    }
