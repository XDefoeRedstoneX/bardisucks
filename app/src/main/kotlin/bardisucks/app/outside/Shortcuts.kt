package bardisucks.app.outside

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.graphics.createBitmap
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import bardisucks.app.R
import bardisucks.app.ui.colors
import bardisucks.core.home.Action
import bardisucks.core.home.SceneConfig
import java.util.concurrent.Executor

/** Scene shortcuts: long-press the app icon, or pinned to the home screen. */
object Shortcuts {
    /** Launchers show about four; the first scenes in the list get them. */
    private const val LAUNCHER_SHORTCUTS = 4

    private fun id(scene: SceneConfig) = Action.RunScene(scene.id).encode()

    private fun info(context: Context, scene: SceneConfig): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, id(scene))
            .setShortLabel(scene.name.ifBlank { "?" })
            .setIcon(icon(scene))
            .setIntent(Intent(context, RunActivity::class.java).setAction(Intent.ACTION_VIEW).setData(RunActivity.link(Action.RunScene(scene.id))))
            .build()

    /** Keeps the launcher's scene shortcuts in line with the scenes: names, colours, deleted ones. */
    fun sync(context: Context, scenes: List<SceneConfig>) {
        runCatching {
            val infos = scenes.map { info(context, it) }
            ShortcutManagerCompat.setDynamicShortcuts(context, infos.take(LAUNCHER_SHORTCUTS))
            ShortcutManagerCompat.updateShortcuts(context, infos) // pinned ones get the new name and colours
            val existing = infos.map { it.id }.toSet()
            val gone = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED).map { it.id }.filter { it !in existing }
            if (gone.isNotEmpty()) ShortcutManagerCompat.disableShortcuts(context, gone, context.getString(R.string.shortcut_missing))
        }
    }

    fun canPin(context: Context) = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    fun pin(context: Context, scene: SceneConfig) {
        ShortcutManagerCompat.requestPinShortcut(context, info(context, scene), null)
    }

    /** The scene's colours as dots on a dark disc. */
    private fun icon(scene: SceneConfig): IconCompat {
        val size = 216 // adaptive icon, 108 dp at 2×
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF1B1A2E.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val colors = scene.looks.firstOrNull()?.colors().orEmpty().distinct().take(4).ifEmpty { listOf(Color.Unspecified) }
            .map { if (it == Color.Unspecified) 0xFF4A4858.toInt() else it.toArgb() }
        val c = size / 2f
        if (colors.size == 1) {
            paint.color = colors.single()
            canvas.drawCircle(c, c, size * 0.2f, paint)
        } else {
            val r = size * 0.1f
            val gap = size * 0.115f
            colors.forEachIndexed { i, color ->
                paint.color = color
                canvas.drawCircle(c + if (i % 2 == 0) -gap else gap, c + if (i < 2) -gap else gap, r, paint)
            }
        }
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }
}

/** Asking Android 13+ to put a tile in the Quick Settings panel. */
object TileRequest {
    fun supported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun request(context: Context, slot: Int, label: String) {
        if (!supported()) return
        val statusBar = context.getSystemService(android.app.StatusBarManager::class.java) ?: return
        val component = ComponentName(context, SlotTile.SLOTS[slot - 1])
        val direct = Executor { it.run() }
        statusBar.requestAddTileService(component, label, Icon.createWithResource(context, R.drawable.ic_tile), direct) {}
    }
}
