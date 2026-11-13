package bardisucks.app

import bardisucks.app.outside.RunActivity
import bardisucks.core.home.Action
import bardisucks.core.home.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OutsideTest {
    @Test
    fun shortcutLinksCarryTheirAction() {
        for (action in listOf(Action.RunScene("s1a2b3c4"), Action.Switch(Target.Group("all")), Action.Switch(Target.Light("bf00:x")))) {
            val link = RunActivity.link(action)
            assertEquals("bardisucks", link.scheme)
            assertEquals(action, RunActivity.actionOf(link))
        }
        assertNull(RunActivity.actionOf(android.net.Uri.parse("https://example.com/run/scene:x")))
    }
}
