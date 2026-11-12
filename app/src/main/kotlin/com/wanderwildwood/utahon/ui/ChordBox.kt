package com.wanderwildwood.utahon.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wanderwildwood.utahon.music.Voicing

/**
 * A chord box, as a chord chart prints one: the strings down, four frets across them, a dot
 * where a finger goes, a ring over a string played open and a cross over one left silent. The
 * thick bar at the top is the nut; a shape further up the neck has the number of its first fret
 * beside it instead.
 */
@Composable
fun ChordBox(voicing: Voicing, description: String, modifier: Modifier = Modifier, width: Dp = 150.dp) {
    val ink = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    val label = MaterialTheme.typography.labelSmall
    val strings = voicing.frets.size
    val first = voicing.firstFret
    Canvas(modifier.size(width, width * 1.15f).semantics { contentDescription = description }) {
        val number = if (first > 1) measurer.measure(first.toString(), label) else null
        val side = size.width * 0.16f
        val left = side
        val right = size.width - side
        val top = size.height * 0.2f
        val bottom = size.height - 4.dp.toPx()
        val gapX = (right - left) / (strings - 1)
        val gapY = (bottom - top) / 4
        val thin = 1.5.dp.toPx()

        for (s in 0 until strings) {
            val x = left + s * gapX
            drawLine(ink, Offset(x, top), Offset(x, bottom), thin)
        }
        for (f in 0..4) {
            val y = top + f * gapY
            drawLine(ink, Offset(left - thin / 2, y), Offset(right + thin / 2, y), if (f == 0 && first == 1) 5.dp.toPx() else thin)
        }
        number?.let {
            drawText(it, ink, Offset((left - it.size.width - 6.dp.toPx()).coerceAtLeast(0f), top + gapY / 2 - it.size.height / 2))
        }

        val dot = minOf(gapX, gapY) * 0.36f
        val mark = minOf(gapX * 0.3f, top * 0.28f)
        val markY = top - mark - 6.dp.toPx()
        voicing.frets.forEachIndexed { s, fret ->
            val x = left + s * gapX
            when {
                fret < 0 -> {
                    drawLine(ink, Offset(x - mark, markY - mark), Offset(x + mark, markY + mark), thin)
                    drawLine(ink, Offset(x - mark, markY + mark), Offset(x + mark, markY - mark), thin)
                }
                fret == 0 -> drawCircle(ink, mark, Offset(x, markY), style = Stroke(thin))
                else -> drawCircle(ink, dot, Offset(x, top + (fret - first + 0.5f) * gapY))
            }
        }
    }
}
