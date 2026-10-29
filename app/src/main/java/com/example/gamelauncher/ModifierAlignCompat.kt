package com.example.gamelauncher

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.IntSize

/**
 * Fallback alignment modifier for places in the launcher that are outside a BoxScope.
 * BoxScope.align remains preferred inside Box content; this version keeps the child at
 * its measured size and positions it inside the available parent bounds.
 */
fun Modifier.align(alignment: Alignment): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val parentWidth = constraints.maxWidth.takeIf { it != androidx.compose.ui.unit.Constraints.Infinity } ?: placeable.width
    val parentHeight = constraints.maxHeight.takeIf { it != androidx.compose.ui.unit.Constraints.Infinity } ?: placeable.height
    val position = alignment.align(
        IntSize(placeable.width, placeable.height),
        IntSize(parentWidth, parentHeight),
        layoutDirection
    )
    layout(parentWidth, parentHeight) {
        placeable.place(position.x, position.y)
    }
}
