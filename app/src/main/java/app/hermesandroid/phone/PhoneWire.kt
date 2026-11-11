package app.hermesandroid.phone

import app.hermesandroid.push.E2E
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// The phone channel's wire shapes (phone_store.py `_apply`): what the calendar and the phone's state become
// before they're sealed. Pure, so the JVM tests can pin them.

private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

/** ISO 8601 with the local offset and no fraction: the host refuses a time without an offset. */
fun iso(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String = ISO.format(Instant.ofEpochMilli(ms).atZone(zone))

/** An all-day event's day: Android stores it as UTC midnights; the host wants that day at local midnight. */
fun isoAllDay(utcMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    ISO.format(Instant.ofEpochMilli(utcMs).atZone(ZoneId.of("UTC")).toLocalDate().atStartOfDay(zone))

fun day(ms: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

/** The host's ids: a prefix and the first 22 chars of b64url(SHA-256(parts joined by U+001F)). */
fun wireId(prefix: String, vararg parts: Any): String =
    prefix + E2E.b64u(MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u001f").toByteArray())).take(22)

/** A stable digest of an item's wire JSON, to send only what changed. */
fun digest(text: String): String = E2E.b64u(MessageDigest.getInstance("SHA-256").digest(text.toByteArray())).take(16)

fun hexColor(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

/** CalendarContract.Events.AVAILABILITY / STATUS / Attendees status → the host's words. */
fun availability(v: Int?): String = when (v) { 0 -> "busy"; 1 -> "free"; 2 -> "tentative"; else -> "none" }
fun eventStatus(v: Int?): String = when (v) { 0 -> "tentative"; 1 -> "confirmed"; 2 -> "canceled"; else -> "none" }
fun myStatus(v: Int?): String = when (v) { 1 -> "accepted"; 2 -> "declined"; 3 -> "pending"; 4 -> "tentative"; else -> "none" }

/** Battery is resent in 5 % steps above 20 % (every 1 % below), so a full pass isn't spent on a percent. */
fun batteryStep(level: Float): Float = if (level > 0.2f) (Math.round(level * 20) / 20f) else Math.round(level * 100) / 100f
