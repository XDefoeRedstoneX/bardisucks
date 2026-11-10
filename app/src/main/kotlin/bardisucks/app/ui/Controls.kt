package bardisucks.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** How long a released control keeps showing the released value while the lamp's confirmation is on its way. */
private const val SETTLE_MS = 1_500L

/**
 * A wide slider for a lamp value. While it is dragged, [onPreview] gets every new value (the lamp follows
 * live); letting go calls [onCommit] once. With [track] set it draws that gradient and a thumb instead of a
 * fill (used for the warm–cool slider).
 */
@Composable
fun LevelSlider(
    label: String,
    value: Int,
    range: IntRange,
    valueText: (Int) -> String,
    onPreview: (Int) -> Unit,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    track: Brush? = null,
    fill: Color = MaterialTheme.colorScheme.primary,
) {
    var dragged by remember { mutableStateOf<Int?>(null) }
    var released by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(released, value) {
        if (released != null) {
            if (released != value) delay(SETTLE_MS)
            released = null
        }
    }
    val shown = (dragged ?: released ?: value).coerceIn(range.first, range.last)
    val preview by rememberUpdatedState(onPreview)
    val commit by rememberUpdatedState(onCommit)

    Column(modifier.alpha(if (enabled) 1f else 0.4f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(valueText(shown), style = MaterialTheme.typography.labelLarge)
        }
        val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        val thumbColor = MaterialTheme.colorScheme.onSurface
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .testTag("slider:$label")
                .semantics {
                    contentDescription = label
                    progressBarRangeInfo = ProgressBarRangeInfo(shown.toFloat(), range.first.toFloat()..range.last.toFloat())
                    if (enabled) setProgress { target -> commit(target.roundToInt().coerceIn(range.first, range.last)); true }
                }
                .pointerInput(enabled, range) {
                    if (!enabled) return@pointerInput
                    fun valueAt(x: Float): Int {
                        val fraction = (x / size.width).coerceIn(0f, 1f)
                        return (range.first + fraction * (range.last - range.first)).roundToInt()
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var last: Int? = null
                        fun moveTo(x: Float) {
                            val next = valueAt(x)
                            if (next != last) {
                                last = next
                                dragged = next
                                preview(next)
                            }
                        }
                        // Wait for a sideways move, so scrolling the page over a slider doesn't change the lamp.
                        val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        if (start != null) {
                            moveTo(start.position.x)
                            horizontalDrag(start.id) { change ->
                                moveTo(change.position.x)
                                change.consume()
                            }
                        } else {
                            // No drag: it was a tap if the finger simply went up (and the page didn't scroll).
                            val up = currentEvent.changes.firstOrNull { it.id == down.id }
                            if (up != null && !up.pressed && !up.isConsumed) moveTo(up.position.x)
                        }
                        last?.let {
                            released = it
                            commit(it)
                        }
                        dragged = null
                    }
                },
        ) {
            val fraction = (shown - range.first).toFloat() / (range.last - range.first).coerceAtLeast(1)
            if (track != null) {
                drawRect(track)
                val x = (12.dp.toPx() + fraction * (size.width - 24.dp.toPx()))
                drawRoundRect(
                    Color.Black.copy(alpha = 0.35f),
                    topLeft = Offset(x - 5.dp.toPx(), 5.dp.toPx()),
                    size = Size(10.dp.toPx(), size.height - 10.dp.toPx()),
                    cornerRadius = CornerRadius(5.dp.toPx()),
                )
                drawRoundRect(
                    Color.White,
                    topLeft = Offset(x - 3.dp.toPx(), 7.dp.toPx()),
                    size = Size(6.dp.toPx(), size.height - 14.dp.toPx()),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            } else {
                drawRect(trackColor)
                // Never thinner than the handle, so 1 % is still something to grab.
                val width = (fraction * size.width).coerceAtLeast(20.dp.toPx())
                drawRect(fill, size = Size(width, size.height))
                drawRoundRect(
                    thumbColor.copy(alpha = 0.55f),
                    topLeft = Offset(width - 9.dp.toPx(), size.height / 2 - 9.dp.toPx()),
                    size = Size(3.dp.toPx(), 18.dp.toPx()),
                    cornerRadius = CornerRadius(1.5.dp.toPx()),
                )
            }
        }
    }
}

/**
 * Hue around the circle, saturation from the centre (white) outwards. Like [LevelSlider] it previews while
 * dragged and commits on release.
 */
@Composable
fun ColorWheel(
    hue: Int,
    saturation: Int,
    onPreview: (hue: Int, saturation: Int) -> Unit,
    onCommit: (hue: Int, saturation: Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** False while the lamp isn't showing a colour: the thumb is then hidden. */
    showThumb: Boolean = true,
) {
    var dragged by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var released by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val current = hue to saturation
    LaunchedEffect(released, current) {
        if (released != null) {
            if (released != current) delay(SETTLE_MS)
            released = null
        }
    }
    val shown = dragged ?: released ?: current
    val preview by rememberUpdatedState(onPreview)
    val commit by rememberUpdatedState(onCommit)
    val ring = MaterialTheme.colorScheme.outlineVariant

    Canvas(
        modifier
            .aspectRatio(1f)
            .alpha(if (enabled) 1f else 0.4f)
            .testTag("colorWheel")
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val centre = Offset(size.width / 2f, size.height / 2f)
                    val radius = min(size.width, size.height) / 2f
                    if ((down.position - centre).getDistance() > radius) return@awaitEachGesture
                    var last: Pair<Int, Int>? = null
                    fun moveTo(position: Offset) {
                        val next = pick(position, centre, radius)
                        if (next != last) {
                            last = next
                            dragged = next
                            preview(next.first, next.second)
                        }
                    }
                    down.consume()
                    moveTo(down.position)
                    drag(down.id) { change ->
                        moveTo(change.position)
                        change.consume()
                    }
                    last?.let {
                        released = it
                        commit(it.first, it.second)
                    }
                    dragged = null
                }
            },
    ) {
        val radius = size.minDimension / 2f
        drawCircle(Brush.sweepGradient(HUES, center), radius)
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center, radius), radius)
        drawCircle(ring, radius, style = Stroke(1.dp.toPx()))
        if (showThumb || dragged != null || released != null) {
            val angle = shown.first * PI / 180
            val distance = shown.second / 100f * radius
            val thumb = center + Offset((cos(angle) * distance).toFloat(), (sin(angle) * distance).toFloat())
            drawCircle(Color.Black.copy(alpha = 0.25f), 16.dp.toPx(), thumb)
            drawCircle(Color.White, 14.dp.toPx(), thumb)
            drawCircle(hueColor(shown.first, shown.second), 11.dp.toPx(), thumb)
        }
    }
}

private val HUES = (0..360 step 30).map { Color.hsv(it.toFloat(), 1f, 1f) }

/** Hue (0–359, clockwise from 3 o'clock, matching the gradient) and saturation (0–100) under [position]. */
private fun pick(position: Offset, centre: Offset, radius: Float): Pair<Int, Int> {
    val dx = position.x - centre.x
    val dy = position.y - centre.y
    val degrees = (atan2(dy, dx) * 180 / PI).roundToInt()
    val saturation = (hypot(dx, dy) / radius * 100).roundToInt().coerceIn(0, 100)
    return ((degrees + 360) % 360) to saturation
}

/** A round colour dot: swatches, and the member dots of a group. */
@Composable
fun ColorDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(50)).background(color), contentAlignment = Alignment.Center) {}
}
