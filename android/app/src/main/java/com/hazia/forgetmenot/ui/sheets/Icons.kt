package com.hazia.forgetmenot.ui.sheets

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Port of www/js/ui/icons.js plus the icons the sheet and settings markup inlines: 24-unit
// stroke paths, width 2, round caps and joins, drawn in the current content colour. Built
// from the same path strings, so they look as they did; no icon font, nothing fetched (I7).
// Arc flags are spelled out ("a2 2 0 0 1 2 2", not "a2 2 0 012 2"): Compose's path parser reads
// SVG's packed flags as one number and draws the arc garbled. AppIconPathTest guards it.
enum class AppIcon(val path: String) {
    MAIL("M4 6h16v12H4z M4 7l8 6 8-6"),
    PLUS("M12 5v14 M5 12h14"),
    X("M6 6l12 12 M18 6L6 18"),
    CHECK("M5 12l5 5L20 7"),
    ARROW_RIGHT("M5 12h14 M13 5l7 7-7 7"),
    ARROW_LEFT("M19 12H5 M11 5l-7 7 7 7"),
    CHEVRON_DOWN("M6 9l6 6 6-6"),
    CALENDAR("M4 5h16v16H4z M4 9h16 M8 3v4 M16 3v4"),
    EDIT("M12 20h9 M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"),
    TRASH("M3 6h18 M8 6V4a2 2 0 0 1 2 -2h4a2 2 0 0 1 2 2v2 M19 6l-1 14a2 2 0 0 1 -2 2H8a2 2 0 0 1 -2 -2L5 6"),
    FOLDER("M3 6h6l2 2h10v11H3z"),
    WARN("M12 9v4 M12 17h.01 M10.3 3.9L2.5 17a2 2 0 0 0 1.7 3h15.6a2 2 0 0 0 1.7 -3L13.7 3.9a2 2 0 0 0 -3.4 0z"),
    DOWNLOAD("M12 3v12 M7 11l5 5 5-5 M4 21h16"),
    UPLOAD("M12 21V9 M7 13l5-5 5 5 M4 3h16"),
    SEARCH("M11 19a8 8 0 1 0 0 -16 8 8 0 0 0 0 16z M21 21l-4.3-4.3");

    /** Stroked in black; [Glyph] tints it. */
    val vector: ImageVector by lazy {
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = addPathNodes(path),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()
    }
}

/** A decorative icon (the label is on its button or row), [size] square. */
@Composable
fun Glyph(icon: AppIcon, size: Dp = 18.dp, tint: Color = LocalContentColor.current, contentDescription: String? = null) {
    Icon(icon.vector, contentDescription, Modifier.size(size), tint = tint)
}
