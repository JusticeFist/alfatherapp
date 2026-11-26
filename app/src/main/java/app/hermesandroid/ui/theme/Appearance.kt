package app.hermesandroid.ui.theme

import android.graphics.ImageDecoder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.hermesandroid.ui.shell.decodeImage
import app.hermesandroid.ui.shell.imageCache
import java.io.File

/** What the hero panels and the app's face draw (Settings > Appearance > Backdrop). */
enum class HeroMode {
    /** AllFather's own art: the mascot portraits. */
    Art,

    /** A flat panel in the theme's canvas colour, in every place the mascot appeared. */
    Flat,

    /** The user's own picture, stored in the app's files like the portrait. */
    Picture,
}

/**
 * The user's own portrait, shown wherever the app would otherwise show its bundled art
 * (Settings > Appearance). Mirrors DisplayPrefs: Store is the truth on disk and exposes no Flow, so
 * composition reads this snapshot and AppGraph's setter writes both sides. Device-wide on purpose —
 * a portrait is not a per-dashboard notion.
 *
 * The Backdrop (what the heroes draw) and the theme (what colour the app's surfaces are) live here for
 * the same reason and with the same setters.
 */
object Appearance {
    /** Absolute path of the stored square portrait, or null for the app's own art. */
    var portrait by mutableStateOf<String?>(null)

    /** True when the user replaced the portrait; screens use it to offer the reset. */
    val custom: Boolean get() = portrait != null

    /** What the heroes draw. `Art` is the app as shipped: nothing here changes it by default. */
    var heroMode by mutableStateOf(HeroMode.Art)

    /**
     * The app's theme: the surface tokens every screen draws on (Settings > Appearance > Backdrop).
     * [Tint.Graphite] is the app as shipped. Device-wide on purpose, like the portrait: a theme is not
     * a per-dashboard notion, and it is read during composition by [afColors], so a new value retints
     * the app at once.
     */
    var tint by mutableStateOf(Tint.Graphite)

    /** Absolute path of the stored backdrop picture, or null to fall back to the app's art. */
    var heroImage by mutableStateOf<String?>(null)

    /** True when the user took the mascot off the heroes; screens use it to offer the reset. */
    val heroCustom: Boolean get() = heroMode != HeroMode.Art

    /**
     * The stored string back into a [HeroMode]. Store keeps it lowercase; an unknown value (a future
     * mode written by a newer app, or a hand-edited file) reads as the app's own art, never a crash.
     * `"colour"` is the name the flat mode shipped with in the Backdrop build: a user who picked a
     * swatch there keeps the panel they chose, in the theme's canvas.
     */
    fun modeOf(stored: String): HeroMode = when (stored) {
        "colour" -> HeroMode.Flat
        else -> HeroMode.values().firstOrNull { it.name.lowercase() == stored } ?: HeroMode.Art
    }

    /**
     * The stored string back into a [Tint], the same tolerant way: a theme written by a newer app reads
     * as graphite, the app as shipped, and never crashes the launch it is loaded in.
     */
    fun tintOf(stored: String): Tint = Tint.values().firstOrNull { it.key == stored } ?: Tint.Graphite

    /**
     * The stored portrait decoded for the size it shows, or null to fall back to the bundled art.
     * Decoded once per (path, size) into the app's image cache, the way Avatar caches host photos:
     * pages rebuild on every open and rows must not re-read a file each time. A missing file (a
     * restored app without the old internal storage) is not an error — it means the app's own art.
     */
    @Composable
    fun portraitImage(px: Int): ImageBitmap? {
        val path = portrait ?: return null
        return remember(path, px) {
            runCatching {
                val key = "portrait:$path:$px"
                imageCache.get(key)
                    ?: decodeImage(ImageDecoder.createSource(File(path)), px, cover = true)?.asImageBitmap()
                        ?.also { imageCache.put(key, it) }
            }.getOrNull()
        }
    }

    /**
     * The stored backdrop decoded for the width it shows, or null to fall back to the bundled art.
     * Decoded by max dimension, not the square cover crop the portrait uses: a hero is wide, and the
     * crop is the caller's (`ContentScale.Crop`), so a tall photo keeps its centre on every screen.
     */
    @Composable
    fun heroImagePx(px: Int): ImageBitmap? {
        val path = heroImage ?: return null
        return remember(path, px) {
            runCatching {
                val key = "hero:$path:$px"
                imageCache.get(key)
                    ?: decodeImage(ImageDecoder.createSource(File(path)), px)?.asImageBitmap()
                        ?.also { imageCache.put(key, it) }
            }.getOrNull()
        }
    }
}
