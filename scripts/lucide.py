"""Generates app/src/main/java/app/hermesandroid/ui/icons/AF.kt from lucide-static SVGs (ISC licence).

Usage: python scripts/lucide.py <path to lucide-static package/icons>
Add an icon by adding a line to ICONS (Kotlin name -> Lucide file name), then re-run.
"""
import re
import sys
from pathlib import Path

ICONS = {
    # Tabs and shell
    "Chats": "message-circle", "Board": "square-kanban", "Bots": "bot", "Settings": "settings",
    "Mic": "mic", "MicOff": "mic-off", "Send": "arrow-up", "Close": "x", "Check": "check",
    "Search": "search", "Back": "chevron-left", "Forward": "chevron-right", "Down": "chevron-down", "More": "ellipsis",
    "Folder": "folder", "Grid": "layout-grid", "Filter": "list-filter", "Compose": "square-pen", "Pin": "pin",
    "Trash": "trash-2", "Copy": "copy", "Share": "share-2", "Refresh": "refresh-cw", "Attach": "paperclip",
    "Image": "image", "File": "file-text", "Clock": "clock", "Calendar": "calendar", "Bell": "bell",
    "User": "user", "Users": "users", "Play": "play", "Pause": "pause", "Stop": "square", "Phone": "phone",
    "PhoneOff": "phone-off", "Speaker": "volume-2", "Info": "info", "Warning": "triangle-alert",
    "Done": "circle-check", "Lock": "lock", "Eye": "eye", "Link": "link", "Globe": "globe", "Terminal": "terminal",
    "Sparkles": "sparkles", "Wrench": "wrench", "Brain": "brain", "Mail": "mail", "Fingerprint": "fingerprint",
    "Edit": "pencil", "Archive": "archive", "Logout": "log-out", "Server": "server", "Plug": "plug",
    "Menu": "menu", "Pulse": "activity",
}

# Material icon name -> Lucide file, so `Icons.Filled.X` / `Icons.Outlined.X` became `AF.X` in one sweep.
# ":fill" draws the shape filled (states that Material showed with a filled glyph: a set star, a dot).
MATERIAL = {
    "CheckCircle": "circle-check", "Delete": "trash-2", "Extension": "puzzle", "Description": "file-text", "Add": "plus",
    "Mic": "mic", "Image": "image", "GraphicEq": "audio-lines", "VerifiedUser": "shield-check",
    "Terminal": "terminal", "Sync": "refresh-cw", "Stop": "square", "Schedule": "clock", "PushPin": "pin",
    "Psychology": "brain", "Dns": "server", "ContentCopy": "copy", "Build": "wrench", "AutoAwesome": "sparkles",
    "Tune": "sliders-horizontal", "Memory": "cpu", "Lock": "lock", "Language": "globe", "Group": "users",
    "Folder": "folder", "Archive": "archive", "Search": "search", "RecordVoiceOver": "speech",
    "RadioButtonUnchecked": "circle", "PlayArrow": "play", "PhoneAndroid": "smartphone", "NotificationsActive": "bell-ring",
    "Navigation": "navigation", "Link": "link", "Info": "info", "Home": "house", "ForkRight": "git-fork",
    "ExpandMore": "chevron-down", "Circle": "circle:fill", "CellTower": "radio-tower", "ArrowUpward": "arrow-up",
    "ArrowBack": "arrow-left", "AccountTree": "network", "WifiOff": "wifi-off", "Web": "app-window",
    "Warning": "triangle-alert", "VisibilityOff": "eye-off", "Visibility": "eye", "UnfoldMore": "chevrons-up-down",
    "Unarchive": "archive-restore", "StarBorder": "star", "Star": "star:fill", "SettingsVoice": "mic-vocal",
    "Security": "shield", "RestartAlt": "rotate-ccw", "Report": "octagon-alert", "Person": "user",
    "Notifications": "bell", "MicOff": "mic-off", "Logout": "log-out", "LocationOn": "map-pin", "Inbox": "inbox",
    "History": "history", "FormatQuote": "quote", "Fingerprint": "fingerprint", "FavoriteBorder": "heart",
    "DonutLarge": "chart-pie", "Check": "check", "ChatBubble": "message-square", "Call": "phone",
    "CalendarMonth": "calendar", "Bolt": "zap", "Webhook": "webhook", "VolumeUp": "volume-2", "ViewInAr": "box",
    "Vibration": "vibrate", "Timer": "timer", "ThumbUp": "thumbs-up", "ThumbDown": "thumbs-down", "TextFields": "type",
    "SystemUpdate": "download", "SettingsSuggest": "settings-2", "Settings": "settings", "Sensors": "radar",
    "RemoveModerator": "shield-off", "Queue": "layers", "QrCodeScanner": "scan-qr-code", "PlayCircle": "circle-play",
    "Place": "map-pin", "PhotoCamera": "camera", "Pending": "circle-ellipsis", "Pause": "pause",
    "OpenInFull": "maximize-2", "Movie": "film", "MoreHoriz": "ellipsis", "MenuBook": "book-open",
    "MarkEmailUnread": "mail", "MarkEmailRead": "mail-open", "Login": "log-in", "ListAlt": "list",
    "KeyboardArrowRight": "chevron-right", "Keyboard": "keyboard", "InsertDriveFile": "file",
    "Groups": "users-round", "GridView": "layout-grid", "FolderOpen": "folder-open", "FilterList": "list-filter",
    "FactCheck": "list-checks", "Explore": "compass", "Error": "circle-alert", "Email": "mail", "EditNote": "notebook-pen",
    "DriveFileMove": "folder-input", "Drafts": "mail-open", "Download": "download", "Directions": "signpost",
    "ContentPaste": "clipboard", "Contacts": "contact", "ContactSupport": "message-circle-question", "Code": "code",
    "CloseFullscreen": "minimize-2", "Checklist": "list-checks", "ChatBubbleOutline": "message-square",
    "Chat": "message-circle", "Category": "shapes", "Cancel": "circle-x", "CallSplit": "split", "CallEnd": "phone-off",
    "BatteryChargingFull": "battery-charging", "BackHand": "hand", "Audiotrack": "music", "AttachFile": "paperclip",
    "Article": "newspaper", "ArrowDownward": "arrow-down", "Alarm": "alarm-clock",
    "AddReaction": "smile-plus", "AccountCircle": "circle-user", "Send": "send", "Close": "x", "Edit": "pencil",
}

NUM = r"-?[\d.]+(?:e-?\d+)?"


def attrs(tag: str) -> dict:
    return dict(re.findall(r'([\w-]+)="([^"]*)"', tag))


def f(v: float) -> str:
    return f"{v:g}"


def to_path(tag: str, a: dict) -> str:
    """One SVG element as path data (Lucide uses path, circle, ellipse, rect, line, polyline, polygon)."""
    if tag == "path":
        return a["d"]
    if tag in ("circle", "ellipse"):
        cx, cy = float(a["cx"]), float(a["cy"])
        rx = float(a.get("r", a.get("rx", 0)))
        ry = float(a.get("r", a.get("ry", 0)))
        return f"M{f(cx - rx)} {f(cy)}a{f(rx)} {f(ry)} 0 1 0 {f(2 * rx)} 0a{f(rx)} {f(ry)} 0 1 0 {f(-2 * rx)} 0"
    if tag == "rect":
        x, y, w, h = (float(a.get(k, 0)) for k in ("x", "y", "width", "height"))
        rx = float(a.get("rx", a.get("ry", 0)))
        ry = float(a.get("ry", rx))
        if not rx:
            return f"M{f(x)} {f(y)}h{f(w)}v{f(h)}h{f(-w)}z"
        return (f"M{f(x + rx)} {f(y)}h{f(w - 2 * rx)}a{f(rx)} {f(ry)} 0 0 1 {f(rx)} {f(ry)}v{f(h - 2 * ry)}"
                f"a{f(rx)} {f(ry)} 0 0 1 {f(-rx)} {f(ry)}h{f(-(w - 2 * rx))}a{f(rx)} {f(ry)} 0 0 1 {f(-rx)} {f(-ry)}"
                f"v{f(-(h - 2 * ry))}a{f(rx)} {f(ry)} 0 0 1 {f(rx)} {f(-ry)}z")
    if tag == "line":
        return f"M{a['x1']} {a['y1']}L{a['x2']} {a['y2']}"
    if tag in ("polyline", "polygon"):
        pts = re.findall(NUM, a["points"])
        pairs = [f"{pts[i]} {pts[i + 1]}" for i in range(0, len(pts), 2)]
        return "M" + "L".join(pairs) + ("z" if tag == "polygon" else "")
    raise ValueError(tag)


def paths(svg: str) -> list[str]:
    return [to_path(t, attrs(body)) for t, body in re.findall(r"<(path|circle|ellipse|rect|line|polyline|polygon)\b([^>]*)/?>", svg)]


def main(icons_dir: str) -> None:
    out = Path(__file__).resolve().parent.parent / "app/src/main/java/app/hermesandroid/ui/icons/AF.kt"
    lines = []
    for name, spec in {**ICONS, **MATERIAL}.items():
        file, _, mode = spec.partition(":")
        ps = paths((Path(icons_dir) / f"{file}.svg").read_text(encoding="utf-8"))
        joined = ", ".join(f'"{p}"' for p in ps)
        fill = ", filled = true" if mode == "fill" else ""
        lines.append(f"    val {name} by lazy {{ icon(\"{name}\", {joined}{fill}) }}")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(f'''package app.hermesandroid.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

// Generated by scripts/lucide.py from lucide-static (ISC). Edit the script's ICONS list, not this file.
// Lucide's 24-unit grid, redrawn at a 1.5 stroke (Lucide ships 2) for the AllFather hairline look.

object AF {{
{chr(10).join(lines)}
}}

private fun icon(name: String, vararg paths: String, filled: Boolean = false): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {{
        paths.forEach {{
            addPath(
                PathParser().parsePathString(it).toNodes(), fill = if (filled) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black), strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }}
    }}.build()
''', encoding="utf-8", newline="\n")
    print(f"{len(lines)} icons -> {out}")


if __name__ == "__main__":
    main(sys.argv[1])
