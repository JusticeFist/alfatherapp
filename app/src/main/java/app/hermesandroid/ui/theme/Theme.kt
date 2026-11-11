package app.hermesandroid.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.hermesandroid.R
import com.kyant.capsule.ContinuousCapsule
import com.kyant.capsule.ContinuousRoundedRectangle

// AllFather design system: dark-only monochrome techwear (docs/design/concepts, style-prompt.txt). The token names
// are the original port's, so call sites kept working when the values changed.

@Immutable
data class AfColors(
    val dark: Boolean,
    /** Primary action and selection: off-white, not a hue. */
    val accent: Color,
    val accentSoft: Color,
    /** The user's message bubble: raised graphite (light text sits on it). */
    val bubble: Color,
    /** Text/glyph on a solid accent fill. */
    val onAccent: Color,
    val aura: Color,
    val attention: Color,
    val success: Color,
    val scheduled: Color,
    val voice: Color,
    val text: Color,
    val secondary: Color,
    val tertiary: Color,
    val surface: Color,
    val canvas: Color,
    val composer: Color,
    val composerStroke: Color,
    val red: Color,
    val orange: Color,
    val yellow: Color,
    val blue: Color,
    /** Selection highlights; white, used at low alpha. */
    val glow: Color,
    /** A step above surface: pressed rows, wells, segmented thumbs. */
    val raised: Color,
    /** 1 px rules between rows and around quiet controls. */
    val hairline: Color,
)

private val Ink = Color(0xFFE9EAEC) // res/values/colors.xml af_ink (widget, shortcuts, notifications)

val AllFatherColors = AfColors(
    dark = true,
    accent = Ink,
    accentSoft = Ink.copy(alpha = 0.7f),
    bubble = Color(0xFF26262A),
    onAccent = Color(0xFF0F0F10),
    aura = Color(0xFF8E8E96),
    attention = Color(0xFFF0B04A),
    success = Color(0xFF4CD98A),
    scheduled = Color(0xFFA9AEB8),
    voice = Ink,
    text = Ink,
    secondary = Ink.copy(alpha = 0.6f),
    tertiary = Ink.copy(alpha = 0.35f),
    surface = Color(0xFF1A1A1B),
    canvas = Color(0xFF0F0F10),
    composer = Color(0xFF1A1A1B),
    composerStroke = Color.White.copy(alpha = 0.08f),
    red = Color(0xFFE5484D),
    orange = Color(0xFFF0B04A),
    yellow = Color(0xFFE8C35A),
    blue = Color(0xFFC3CAD6),
    glow = Color.White,
    raised = Color(0xFF222224),
    hairline = Color.White.copy(alpha = 0.08f),
)

val LocalAfColors = staticCompositionLocalOf { AllFatherColors }

// Geist and Geist Mono are variable fonts (res/font, OFL in assets/licenses): one file, a weight axis per entry.
private fun variable(res: Int, weight: Int) = Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
val Geist = FontFamily(listOf(300, 400, 500, 600, 700).map { variable(R.font.geist, it) })
val GeistMono = FontFamily(listOf(400, 500, 600).map { variable(R.font.geist_mono, it) })

/** Type scale. Hierarchy comes from weight and tracking, not size: titles are light, labels are small and spaced. */
object CType {
    private fun style(size: Float, line: Float, weight: FontWeight = FontWeight.Normal, tracking: Float = 0f) =
        TextStyle(fontFamily = Geist, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = tracking.em)

    val largeTitle = style(30f, 36f, FontWeight.Light, -0.01f)
    val title2 = style(22f, 28f, FontWeight.Light)
    val title3 = style(19f, 24f)
    val headline = style(16f, 21f, FontWeight.Medium)
    val body = style(15f, 22f)
    val callout = style(14.5f, 20f)
    val subheadline = style(14f, 19f)
    val footnote = style(12.5f, 17f)
    val caption = style(12f, 16f)
    val caption2 = style(11f, 13f)
    /** Letterspaced label above a title or a group; callers pass the text in uppercase. */
    val eyebrow = style(11f, 14f, FontWeight.Medium, 0.28f)
    /** Times, counts, durations, ids: Geist Mono with tabular figures. */
    val data = TextStyle(fontFamily = GeistMono, fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = "tnum")
    val mono = GeistMono
}

/** Corner scale with continuous (G2) corners. */
object Radii {
    val sm = ContinuousRoundedRectangle(10.dp)
    val md = ContinuousRoundedRectangle(14.dp)
    val lg = ContinuousRoundedRectangle(20.dp)
    /** Floating cards that follow the screen's own corners (the assistant). */
    val xl = ContinuousRoundedRectangle(28.dp)
    val pill = ContinuousCapsule
}

/** Device-only display settings (Settings > Chat), read during composition; AppGraph loads and saves them. */
object DisplayPrefs {
    /** Settings > Chat text size (ChatTypography): 0.85…1.25. */
    var chatScale by mutableFloatStateOf(1f)
    var haptics by mutableStateOf(true)
}

/** Transcript body (16 sp, roomy lines: what the user reads most), scaled by Settings > Chat text size. */
fun chatTextStyle(scale: Float = 1f): TextStyle {
    val s = scale * DisplayPrefs.chatScale
    return CType.body.copy(fontSize = 16.sp * s, lineHeight = 25.sp * s)
}

fun TextStyle.semibold() = copy(fontWeight = FontWeight.SemiBold)
fun TextStyle.bold() = copy(fontWeight = FontWeight.Bold)
fun TextStyle.medium() = copy(fontWeight = FontWeight.Medium)

/** Motion: iOS-style springs as (dampingRatio, stiffness ≈ (2π/response)²). */
object Motion {
    fun <T> ease(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 158f)
    fun <T> overshoot(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.72f, stiffness = 195f)
    /** Screens sliding over the chat: Material 3's default spatial spring, the drawer's and the sheets' own (~0.3 s). */
    fun <T> page(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 700f)
}

object C {
    val colors: AfColors
        @Composable get() = LocalAfColors.current
}

// Every Material role is set: anything left at its default shows Material's purple (menus, switches, dialogs).
private val scheme = AllFatherColors.let { c ->
    darkColorScheme(
        primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.raised, onPrimaryContainer = c.text,
        inversePrimary = c.canvas,
        secondary = c.aura, onSecondary = c.canvas, secondaryContainer = c.raised, onSecondaryContainer = c.text,
        tertiary = c.scheduled, onTertiary = c.canvas, tertiaryContainer = c.raised, onTertiaryContainer = c.text,
        background = c.canvas, onBackground = c.text,
        surface = c.surface, onSurface = c.text, surfaceVariant = c.raised, onSurfaceVariant = c.secondary,
        surfaceTint = Color.Transparent, inverseSurface = c.text, inverseOnSurface = c.canvas,
        error = c.red, onError = c.text, errorContainer = c.red.copy(alpha = 0.18f), onErrorContainer = c.text,
        outline = Color.White.copy(alpha = 0.14f), outlineVariant = c.hairline, scrim = Color.Black.copy(alpha = 0.6f),
        surfaceBright = Color(0xFF2A2A2E), surfaceDim = c.canvas,
        surfaceContainerLowest = Color(0xFF0B0B0C), surfaceContainerLow = Color(0xFF151516), surfaceContainer = c.surface,
        surfaceContainerHigh = Color(0xFF202022), surfaceContainerHighest = Color(0xFF26262A),
    )
}
private val base = Typography()
private val type = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Geist, fontWeight = FontWeight.Light),
    displayMedium = base.displayMedium.copy(fontFamily = Geist, fontWeight = FontWeight.Light),
    displaySmall = CType.largeTitle, headlineLarge = base.headlineLarge.copy(fontFamily = Geist, fontWeight = FontWeight.Light),
    headlineMedium = base.headlineMedium.copy(fontFamily = Geist, fontWeight = FontWeight.Light), headlineSmall = CType.title2,
    titleLarge = CType.title3, titleMedium = CType.headline, titleSmall = CType.subheadline.medium(),
    bodyLarge = CType.body, bodyMedium = CType.subheadline, bodySmall = CType.footnote,
    labelLarge = CType.subheadline.medium(), labelMedium = CType.caption, labelSmall = CType.caption2,
)
private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun AfTheme(content: @Composable () -> Unit) {
    // Always dark: light status and nav bar icons.
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        (view.context as? android.app.Activity)?.window?.let { w ->
            androidx.core.view.WindowCompat.getInsetsController(w, view).apply { isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false }
        }
    }
    MaterialTheme(colorScheme = scheme, typography = type, shapes = shapes) {
        CompositionLocalProvider(LocalAfColors provides AllFatherColors, LocalContentColor provides AllFatherColors.text, content = content)
    }
}
