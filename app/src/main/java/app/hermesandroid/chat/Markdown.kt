package app.hermesandroid.chat

import android.annotation.SuppressLint
import android.content.Intent
import app.hermesandroid.ui.shell.decodeImage
import app.hermesandroid.ui.shell.imageCache
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.compose.elements.highlightedCodeBlock
import com.mikepenz.markdown.model.markdownPadding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import app.hermesandroid.ui.theme.bold
import app.hermesandroid.ui.theme.chatTextStyle
import app.hermesandroid.ui.theme.medium
import app.hermesandroid.ui.theme.pressable
import app.hermesandroid.ui.theme.semibold
import app.hermesandroid.ui.theme.settingsCard
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.hermesandroid.ui.icons.AF
import androidx.compose.ui.res.stringResource
import app.hermesandroid.R

/** Gateway file access for transcript rows: `/api/fs/read-data-url` (≤16 MB) and `/api/fs/read-text`. */
interface GatewayFiles {
    suspend fun dataUrl(path: String): String?
    suspend fun text(path: String): String?
}

val LocalGatewayFiles = staticCompositionLocalOf<GatewayFiles?> { null }

sealed interface Seg {
    data class Md(val text: String) : Seg
    data class Math(val tex: String) : Seg
    data class Mermaid(val code: String) : Seg
    data class Media(val path: String) : Seg
    data class Preview(val file: String) : Seg
}

// Hermes' MEDIA tag on its own line (anchored-path form) and `[label](MEDIA:/path)`.
private val MEDIA_LINE = Regex("""^[`"'*_]{0,3}MEDIA:[^\S\n]*((?:~/|/|[A-Za-z]:[/\\]).*?)[`"'*_]{0,3}$""", RegexOption.IGNORE_CASE)
// Android's ICU regex rejects unescaped `{`, `}` and `]` that the desktop JVM tolerates: escape them all.
private val MEDIA_LINK = Regex("""^!?\[[^\]]*\]\([^\S\n]*MEDIA:[^\S\n]*(.+?)[^\S\n]*\)$""", RegexOption.IGNORE_CASE)
private val PREVIEW = Regex("""(?:^|(?<=\s))::preview\{[^\{\}\n]*?file="([^"]+)"[^\{\}\n]*\}""")

/** Splits a reply into Markdown runs and the blocks Markdown can't draw (math, Mermaid, MEDIA, previews). */
fun segments(text: String): List<Seg> {
    val out = mutableListOf<Seg>()
    val md = StringBuilder()
    fun flush() {
        if (md.isNotBlank()) out += Seg.Md(md.toString().trim('\n'))
        md.clear()
    }
    val lines = text.lines()
    var i = 0
    var fence: String? = null // inside a non-mermaid code fence: the fence marker
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        if (fence != null) {
            md.appendLine(line)
            if (t.startsWith(fence)) fence = null
            i++
            continue
        }
        when {
            t.startsWith("```") && t.removePrefix("```").trim().equals("mermaid", true) -> {
                flush()
                val body = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) body.appendLine(lines[i++])
                out += Seg.Mermaid(body.toString().trim())
            }
            t.startsWith("```") || t.startsWith("~~~") -> {
                fence = t.take(3)
                md.appendLine(line)
            }
            t.startsWith("$$") && t.length > 4 && t.endsWith("$$") -> { flush(); out += Seg.Math(t.removeSurrounding("$$").trim()) }
            t == "$$" || t == "\\[" -> {
                flush()
                val close = if (t == "$$") "$$" else "\\]"
                val body = StringBuilder()
                i++
                while (i < lines.size && lines[i].trim() != close) body.appendLine(lines[i++])
                out += Seg.Math(body.toString().trim())
            }
            MEDIA_LINE.matches(t) -> { flush(); out += Seg.Media(MEDIA_LINE.find(t)!!.groupValues[1].trim()) }
            MEDIA_LINK.matches(t) -> { flush(); out += Seg.Media(MEDIA_LINK.find(t)!!.groupValues[1].trim()) }
            PREVIEW.containsMatchIn(t) -> {
                val rest = PREVIEW.replace(line, "").trim()
                if (rest.isNotEmpty()) md.appendLine(rest)
                flush()
                PREVIEW.findAll(t).forEach { out += Seg.Preview(it.groupValues[1]) }
            }
            else -> md.appendLine(line)
        }
        i++
    }
    flush()
    return out
}

/** Assistant reply: Markdown (GFM tables, task lists, highlighted code) plus the special blocks. */
@Composable
fun ReplyBody(text: String, scale: Float = 1f) {
    val parts = remember(text) { segments(text) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        parts.forEach { seg ->
            when (seg) {
                is Seg.Md -> AfMarkdown(seg.text, scale)
                is Seg.Math -> HtmlBlock(katexHtml(seg.tex), minHeight = 48)
                is Seg.Mermaid -> HtmlBlock(mermaidHtml(seg.code), minHeight = 160)
                is Seg.Media -> MediaBlock(seg.path)
                is Seg.Preview -> PreviewBlock(seg.file)
            }
        }
    }
}

private val CODE = Regex("""```[\s\S]*?```|`[^`\n]*`""")
private val LONE_TILDE = Regex("(?<!~)~(?!~)")

/** "~16 min" means "about": a lone `~` stays a tilde (the parser pairs them into strikethrough); `~~x~~` still strikes. Code is left alone. */
internal fun escapeTildes(text: String): String {
    if ('~' !in text) return text
    val out = StringBuilder()
    var at = 0
    for (m in CODE.findAll(text)) {
        out.append(LONE_TILDE.replace(text.substring(at, m.range.first), Regex.escapeReplacement("\\~"))).append(m.value)
        at = m.range.last + 1
    }
    return out.append(LONE_TILDE.replace(text.substring(at), Regex.escapeReplacement("\\~"))).toString()
}

@Composable
fun AfMarkdown(text: String, scale: Float = 1f, color: Color = C.colors.text) {
    val c = C.colors
    val body = chatTextStyle(scale)
    Markdown(
        // retainState: keep showing the last parse while the next one runs. Otherwise every streamed delta blanks
        // the reply for a frame, the row collapses and the list's follow-the-stream scroll jumps.
        com.mikepenz.markdown.model.rememberMarkdownState(remember(text) { escapeTildes(text) }, retainState = true),
        colors = markdownColor(text = color, codeBackground = c.raised, inlineCodeBackground = c.raised, dividerColor = c.hairline),
        typography = markdownTypography(
            h1 = CType.title2.medium(), h2 = CType.title3.medium(), h3 = CType.headline, h4 = CType.subheadline.medium(),
            h5 = CType.subheadline.medium(), h6 = CType.subheadline.medium(), text = body, paragraph = body,
            code = CType.footnote.copy(fontFamily = CType.mono), inlineCode = body.copy(fontFamily = CType.mono, fontSize = body.fontSize * 0.92f),
            quote = body.copy(fontStyle = FontStyle.Italic), ordered = body, bullet = body, list = body,
            table = body.copy(fontSize = body.fontSize * 0.9f, lineHeight = body.lineHeight * 0.9f),
        ),
        components = markdownComponents(codeBlock = highlightedCodeBlock, codeFence = fenceWithHeader, table = wrappingTable),
        padding = markdownPadding(listIndent = 14.dp),
        // The library defaults to fillMaxSize: in a bounded column it takes every pixel.
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Fenced code with the library's header (the language and a copy button), as Claude's app shows it. */
private val fenceWithHeader: com.mikepenz.markdown.compose.components.MarkdownComponent = { m ->
    com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence(m.content, m.node, m.typography.code, showHeader = true)
}

/** Tables whose cells wrap instead of ending in "…" after one line (the table still scrolls sideways). */
private val wrappingTable: com.mikepenz.markdown.compose.components.MarkdownComponent = { m ->
    com.mikepenz.markdown.compose.elements.MarkdownTable(
        m.content, m.node, m.typography.table,
        headerBlock = { content, header, width, style ->
            com.mikepenz.markdown.compose.elements.MarkdownTableHeader(content, header, width, style, maxLines = Int.MAX_VALUE, overflow = androidx.compose.ui.text.style.TextOverflow.Clip)
        },
        rowBlock = { content, row, width, style ->
            com.mikepenz.markdown.compose.elements.MarkdownTableRow(content, row, width, style, maxLines = Int.MAX_VALUE, overflow = androidx.compose.ui.text.style.TextOverflow.Clip)
        },
    )
}

// ---- WebView blocks: KaTeX 0.19.1 / Mermaid 11.17.2 bundled in assets/web, no network ----

/** Pages that load the assets/web files by relative URL; WebViews may read android_asset even with file access off. */
private const val ASSETS = "file:///android_asset/web/"

private fun pageCss(c: app.hermesandroid.ui.theme.AfColors): String {
    fun hex(color: Color) = "#%06X".format(0xFFFFFF and color.toArgb())
    return "html,body{margin:0;padding:0;background:transparent;color:${hex(c.text)};font-family:sans-serif;overflow-x:auto}"
}

@Composable
private fun katexHtml(tex: String): String {
    val css = pageCss(C.colors)
    val safe = tex.replace("\\", "\\\\").replace("`", "\\`").replace("$", "\\$")
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="stylesheet" href="katex.min.css"><script src="katex.min.js"></script><style>$css .katex{font-size:1.15em}</style></head>
<body><div id="m"></div><script>try{katex.render(`$safe`,document.getElementById('m'),{displayMode:true,throwOnError:false})}catch(e){document.body.textContent=`$safe`}
H.h(document.body.scrollHeight)</script></body></html>"""
}

@Composable
private fun mermaidHtml(code: String): String {
    val c = C.colors
    val css = pageCss(c)
    val safe = code.replace("&", "&amp;").replace("<", "&lt;")
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>$css</style>
<script src="mermaid.min.js"></script></head>
<body><pre class="mermaid">$safe</pre><script>mermaid.initialize({startOnLoad:false,theme:'dark'});
mermaid.run().then(()=>H.h(document.body.scrollHeight)).catch(()=>H.h(document.body.scrollHeight))</script></body></html>"""
}

/**
 * Measures itself through a tiny JS bridge so the block is exactly as tall as its content. Never touches the
 * network; host HTML (previews, artifacts) passes its own `baseUrl` so it doesn't run with the assets' origin.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlBlock(html: String, minHeight: Int, baseUrl: String = ASSETS) {
    var height by remember { mutableIntStateOf(minHeight) }
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.blockNetworkLoads = true
                settings.allowFileAccess = false
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun h(px: Int) = post { height = (px / resources.displayMetrics.density).toInt().coerceIn(minHeight, 2000) }
                }, "H")
                loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
            }
        },
        update = { if (it.tag != html) { it.tag = html; it.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null) } },
        modifier = Modifier.fillMaxWidth().height(height.dp),
    )
}

// ---- MEDIA:<path> and ::preview{file} ----

@Composable
private fun MediaBlock(path: String) {
    val c = C.colors
    val files = LocalGatewayFiles.current
    val ctx = LocalContext.current
    val name = path.substringAfterLast('/').substringAfterLast('\\')
    var data by remember(path) { mutableStateOf<String?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    var bitmap by remember(path) { mutableStateOf(imageCache[path]) }
    LaunchedEffect(path) {
        if (bitmap != null) return@LaunchedEffect
        data = files?.dataUrl(path)
        failed = data == null
        val du = data?.takeIf { it.startsWith("data:image/") } ?: return@LaunchedEffect
        // Up to 16 MB: decoded off the main thread, at most 2048 px on the longer side.
        bitmap = withContext(Dispatchers.Default) { decodeImage(Base64.decode(du.substringAfter(","), Base64.DEFAULT), 2048)?.asImageBitmap() }
            ?.also { imageCache.put(path, it) }
    }
    val url = data
    when {
        bitmap != null -> Image(bitmap, name, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(14.dp)))
        else -> Row(
            Modifier.fillMaxWidth().settingsCard(RoundedCornerShape(18.dp)).pressable(enabled = url != null) { url?.let { openDataUrl(ctx, it, name) } }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (url == null && !failed) CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
            else Icon(AF.Description, null, tint = c.accent, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = CType.subheadline.semibold(), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (failed) stringResource(R.string.markdown_unavailable) else path, style = CType.caption, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Writes a data URL to the cache and opens it with the system viewer. */
fun openDataUrl(ctx: android.content.Context, dataUrl: String, name: String) {
    runCatching {
        val mime = dataUrl.substringAfter("data:").substringBefore(";")
        val dir = File(ctx.cacheDir, "media").apply { mkdirs() }
        val file = File(dir, name).apply { writeBytes(Base64.decode(dataUrl.substringAfter(","), Base64.DEFAULT)) }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** An HTML file on the host in a sandboxed frame: no network, no file access. */
@Composable
private fun PreviewBlock(file: String) {
    val c = C.colors
    val files = LocalGatewayFiles.current
    var html by remember(file) { mutableStateOf<String?>(null) }
    LaunchedEffect(file) { html = withContext(Dispatchers.IO) { files?.text(file) } }
    Column(Modifier.fillMaxWidth().settingsCard(RoundedCornerShape(18.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 6.dp)) {
            Icon(AF.Web, null, tint = c.accent, modifier = Modifier.size(14.dp))
            Text(file.substringAfterLast('/'), style = CType.caption.semibold(), color = c.secondary)
        }
        val page = html
        if (page == null) Text(stringResource(R.string.markdown_loading_preview), style = CType.footnote, color = c.secondary, modifier = Modifier.padding(6.dp))
        else Column(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White)) {
            HtmlBlock(page.replace("</body>", "<script>H.h(document.body.scrollHeight)</script></body>"), minHeight = 200, baseUrl = "https://localhost/")
        }
    }
}
