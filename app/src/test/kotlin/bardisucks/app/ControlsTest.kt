package bardisucks.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import bardisucks.app.ui.BardiTheme
import bardisucks.app.ui.ColorWheel
import bardisucks.app.ui.LevelSlider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The two custom controls: preview while dragging, exactly one commit on release. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi-keyshidden-nonav")
class ControlsTest {
    @get:Rule
    val compose = createComposeRule()

    private val previews = mutableListOf<Int>()
    private val commits = mutableListOf<Int>()

    private fun slider(scrollable: Boolean = false) = compose.setContent {
        BardiTheme(dynamicColor = false) {
            Column(if (scrollable) Modifier.height(300.dp).verticalScroll(rememberScrollState()) else Modifier) {
                LevelSlider(
                    label = "Brightness", value = 50, range = 1..100, valueText = { "$it%" },
                    onPreview = { previews += it }, onCommit = { commits += it }, modifier = Modifier.width(300.dp),
                )
                if (scrollable) Spacer(Modifier.height(2000.dp))
            }
        }
    }

    @Test
    fun draggingTheSliderPreviewsAlongTheWayAndCommitsOnce() {
        slider()
        compose.onNodeWithTag("slider:Brightness").performTouchInput { swipeRight(startX = width * 0.2f, endX = width * 0.9f) }
        compose.waitForIdle()
        assertTrue("several previews: $previews", previews.size >= 3)
        assertEquals("previews rise with the finger", previews.sorted(), previews)
        assertEquals(listOf(previews.last()), commits)
        assertTrue("ended near 90 %: $commits", commits.single() in 85..95)
    }

    @Test
    fun tappingTheSliderJumpsThere() {
        slider()
        compose.onNodeWithTag("slider:Brightness").performTouchInput { click(Offset(width * 0.25f, height / 2f)) }
        compose.waitForIdle()
        assertTrue("one commit near 25 %: $commits", commits.size == 1 && commits.single() in 23..28)
    }

    @Test
    fun scrollingThePageOverASliderLeavesTheLampAlone() {
        slider(scrollable = true)
        compose.onNodeWithTag("slider:Brightness").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertEquals(emptyList<Int>(), previews)
        assertEquals(emptyList<Int>(), commits)
    }

    @Test
    fun theWheelGivesHueByAngleAndSaturationByDistance() {
        val picked = mutableListOf<Pair<Int, Int>>()
        compose.setContent {
            BardiTheme(dynamicColor = false) {
                ColorWheel(hue = 0, saturation = 0, onPreview = { _, _ -> }, onCommit = { h, s -> picked += h to s }, modifier = Modifier.size(300.dp))
            }
        }
        val wheel = compose.onNodeWithTag("colorWheel")
        wheel.performTouchInput { click(Offset(width - 2f, height / 2f)) } // 3 o'clock, rim: red, fully saturated
        wheel.performTouchInput { click(Offset(width / 2f, height * 0.75f)) } // 6 o'clock, half way out: yellow-green side
        wheel.performTouchInput { click(Offset(width * 0.25f, height / 2f)) } // 9 o'clock, half way out: cyan
        wheel.performTouchInput { click(Offset(2f, 2f)) } // corner, outside the circle: ignored
        compose.waitForIdle()
        assertEquals(3, picked.size)
        assertTrue("red at the rim: ${picked[0]}", picked[0].first in listOf(0, 359, 1) && picked[0].second >= 98)
        assertTrue("90° half way: ${picked[1]}", picked[1].first in 88..92 && picked[1].second in 48..52)
        assertTrue("180° half way: ${picked[2]}", picked[2].first in 178..182 && picked[2].second in 48..52)
    }
}
