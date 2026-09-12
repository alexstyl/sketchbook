package dev.alexstyl.sketchbook.iconography

/*
 * Icons from Lucide: pen-line, eraser, and trash-2.
 * ISC License, Copyright (c) 2026 Lucide Icons and Contributors.
 * https://lucide.dev/license
 *
 * trash-2 is derived from Feather and is also available under the MIT License,
 * Copyright (c) 2013-present Cole Bemis.
 */

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object Icons

val Icons.PenLine: ImageVector
    get() = penLine ?: ImageVector.Builder(
        name = "pen-line",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) { moveTo(13f, 21f); horizontalLineToRelative(8f) }
        path(
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(21.174f, 6.812f)
            arcToRelative(1f, 1f, 0f, false, false, -3.986f, -3.987f)
            lineTo(3.842f, 16.174f)
            arcToRelative(2f, 2f, 0f, false, false, -0.5f, 0.83f)
            lineToRelative(-1.321f, 4.352f)
            arcToRelative(0.5f, 0.5f, 0f, false, false, 0.623f, 0.622f)
            lineToRelative(4.353f, -1.32f)
            arcToRelative(2f, 2f, 0f, false, false, 0.83f, -0.497f)
            close()
        }
    }.build().also { penLine = it }

val Icons.Eraser: ImageVector
    get() = eraser ?: ImageVector.Builder(
        name = "eraser",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(21f, 21f)
            horizontalLineTo(8f)
            arcToRelative(2f, 2f, 0f, false, true, -1.42f, -0.587f)
            lineToRelative(-3.994f, -3.999f)
            arcToRelative(2f, 2f, 0f, false, true, 0f, -2.828f)
            lineToRelative(10f, -10f)
            arcToRelative(2f, 2f, 0f, false, true, 2.829f, 0f)
            lineToRelative(5.999f, 6f)
            arcToRelative(2f, 2f, 0f, false, true, 0f, 2.828f)
            lineTo(12.834f, 21f)
        }
        path(
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) { moveTo(5.082f, 11.09f); lineToRelative(8.828f, 8.828f) }
    }.build().also { eraser = it }

val Icons.Trash2: ImageVector
    get() = trash2 ?: ImageVector.Builder(
        name = "trash-2",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) { moveTo(10f, 11f); verticalLineToRelative(6f) }
        path(
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) { moveTo(14f, 11f); verticalLineToRelative(6f) }
        path(
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(19f, 6f)
            verticalLineToRelative(14f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
            horizontalLineTo(7f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
            verticalLineTo(6f)
        }
        path(
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) { moveTo(3f, 6f); horizontalLineToRelative(18f) }
        path(
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(8f, 6f)
            verticalLineTo(4f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
            horizontalLineToRelative(4f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
            verticalLineToRelative(2f)
        }
    }.build().also { trash2 = it }

private var penLine: ImageVector? = null
private var eraser: ImageVector? = null
private var trash2: ImageVector? = null
