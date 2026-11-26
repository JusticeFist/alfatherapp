package app.hermesandroid.ui.theme

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import app.hermesandroid.R

/**
 * A theme: one colour ramp for the app's surfaces. A theme supplies **only** the four surface tokens
 * — `canvas` (the screen), `surface` (cards, the composer) and `raised` (wells, pressed rows, the
 * segmented thumb), with `composer` = `surface` as it is upstream. Everything else (text, the
 * off-white accent, the secondary/tertiary alpha steps, the bubble, the aura, the status colours,
 * `hairline`, `glow`) stays exactly as [AllFatherColors] defines it: a theme moves the ground, never
 * the ink, and that is why the contrast ratios the design system measured still hold on it.
 *
 * The ramps are built on the graphite ladder's own lightness (6 % / 10 % / 14 % HLS, upstream's three
 * steps) with the saturation ceiling at **0.45** — measured: 0.45 is where the ramp is unmistakably
 * coloured while `secondary` (text at 60 %) stays above 5.1:1 on every token and `tertiary` stays in
 * the 2.8–2.9 band, the two numbers the design system quotes. Raising the ceiling pushes the
 * supporting text below it. Graphite is the app as shipped, byte for byte.
 *
 * The `raised` step of the green and cyan ramps is a shade under the ladder's 14 % lightness, not at it.
 * The binding constraint on a surface is never the text on it, it is a status colour sitting on it: the
 * config fields draw their red error line on `raised`, and the design system asks for ≥ 4.5:1 there too.
 * A saturated green at 14 % is bright enough to break that (4.11:1), so Pine and Steel drop to 11 % and
 * 12.7 % — Pine is the tightest ramp in the app at 4.63:1, and every other token is at the ladder.
 *
 * KNOWN LIMITATION: `res/values/colors.xml` (`af_canvas`, `af_surface`, `af_ink`, `af_outline`,
 * `af_hairline`) carries the same palette for the home-screen widget, the notifications and the
 * shortcut icons. It is static XML, read outside Compose, so it cannot follow a runtime theme and
 * stays graphite. The design system's "keep the two in step" note covers the token *values*; a themed
 * widget would need the ramp written into resources per theme, which is not what a theme is.
 */
enum class Tint(@StringRes val label: Int, val canvas: Color, val surface: Color, val raised: Color) {
    /** The app as shipped: graphite. Every other entry is this ladder, hue-shifted. */
    Graphite(R.string.appearancepage_theme_graphite, Color(0xFF0F0F10), Color(0xFF1A1A1B), Color(0xFF222224)),

    /** Blue-black: the least hue of the coloured ramps, closest to the app's own temperature. */
    Ink(R.string.appearancepage_theme_ink, Color(0xFF090D16), Color(0xFF0F1626), Color(0xFF131D33)),

    /** Blue-grey: the screen reads as cold steel in daylight. */
    Slate(R.string.appearancepage_theme_slate, Color(0xFF090F16), Color(0xFF0F1A26), Color(0xFF132233)),

    /** Cyan-dark: the voice and the live dot sit on the same family. */
    Steel(R.string.appearancepage_theme_steel, Color(0xFF091316), Color(0xFF0F2026), Color(0xFF12272F)),

    /** Green-dark: the warmest of the cool ramps. */
    Pine(R.string.appearancepage_theme_pine, Color(0xFF091611), Color(0xFF0F261E), Color(0xFF0F2920)),

    /** Brown-dark: the one warm ramp — sepia, not wood panel. */
    Umber(R.string.appearancepage_theme_umber, Color(0xFF160F09), Color(0xFF261A0F), Color(0xFF322112)),

    /** Violet-dark: the farthest the ceiling lets the app go. */
    Plum(R.string.appearancepage_theme_plum, Color(0xFF140916), Color(0xFF220F26), Color(0xFF2C1333));

    /** The composer sits on the surface, in every theme, exactly as it does in the app as shipped. */
    val composer: Color get() = surface

    /** The stored form (Store keeps a theme as its lowercase name). */
    val key: String get() = name.lowercase()
}
