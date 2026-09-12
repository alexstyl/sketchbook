package dev.alexstyl.sketchbook.iconography

/*
 * Icons from Lucide: pen-line and eraser.
 * ISC License, Copyright (c) 2026 Lucide Icons and Contributors.
 * https://lucide.dev/license
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
            fill = SolidColor(Color.Transparent), stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        ) { moveTo(13f, 21f); horizontalLineToRelative(8f) }
        path(
            fill = SolidColor(Color.Transparent), stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
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
            fill = SolidColor(Color.Transparent), stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
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
            fill = SolidColor(Color.Transparent), stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        ) { moveTo(5.082f, 11.09f); lineToRelative(8.828f, 8.828f) }
    }.build().also { eraser = it }

val Icons.FilePlus: ImageVector
    get() = filePlus ?: ImageVector.Builder(
        name = "file-plus",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = SolidColor(Color.Transparent), stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(6f, 22f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
            verticalLineTo(4f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
            horizontalLineToRelative(8f)
            arcToRelative(2.4f, 2.4f, 0f, false, true, 1.704f, 0.706f)
            lineToRelative(3.588f, 3.588f)
            arcTo(2.4f, 2.4f, 0f, false, true, 20f, 8f)
            verticalLineToRelative(12f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
            close()
        }
        path(
            fill = SolidColor(Color.Transparent), stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(14f, 2f)
            verticalLineToRelative(5f)
            arcToRelative(1f, 1f, 0f, false, false, 1f, 1f)
            horizontalLineToRelative(5f)
        }
        path(
            fill = SolidColor(Color.Transparent), stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        ) { moveTo(9f, 15f); horizontalLineToRelative(6f) }
        path(
            fill = SolidColor(Color.Transparent), stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        ) { moveTo(12f, 18f); verticalLineToRelative(-6f) }
    }.build().also { filePlus = it }

private var penLine: ImageVector? = null
private var eraser: ImageVector? = null
private var filePlus: ImageVector? = null
