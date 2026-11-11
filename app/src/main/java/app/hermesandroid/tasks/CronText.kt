package app.hermesandroid.tasks

import app.hermesandroid.HermesApp
import app.hermesandroid.R
import app.hermesandroid.tr
import app.hermesandroid.trPlural

// CronScheduleText: the common five-field cron shapes in plain words. Anything else stays as written.

fun cronToWords(expr: String): String? {
    val f = expr.trim().split(Regex("\\s+"))
    if (f.size != 5) return null
    val (min, hour, dom, mon, dow) = f
    if (mon != "*") return null
    Regex("""\*/(\d+)""").matchEntire(min)?.let { if (hour == "*" && dom == "*" && dow == "*") return every(R.plurals.crontext_every_minutes, it.groupValues[1]) }
    if (min.toIntOrNull() != null) Regex("""\*/(\d+)""").matchEntire(hour)?.let { if (dom == "*" && dow == "*") return every(R.plurals.crontext_every_hours, it.groupValues[1]) }
    val m = min.toIntOrNull() ?: return null
    val h = hour.toIntOrNull() ?: return null
    if (m !in 0..59 || h !in 0..23) return null
    val at = "%d:%02d".format(h, m)
    return when {
        dom == "*" && dow == "*" -> tr(R.string.crontext_daily, at)
        dom == "*" && dow == "1-5" -> tr(R.string.crontext_weekdays, at)
        dom == "*" && (dow == "0,6" || dow == "6,0") -> tr(R.string.crontext_weekends, at)
        dom == "*" -> {
            // Day names from resources, not java.time: they must be in the language the rest of the sentence came in.
            val names = HermesApp.instance.resources.getStringArray(R.array.crontext_day_names)
            val days = dow.split(',').map { d -> d.toIntOrNull()?.let { names.getOrNull(it % 7) } ?: return null }
            val list = if (days.size == 1) days[0] else tr(R.string.crontext_and, days.dropLast(1).joinToString(", "), days.last())
            tr(R.string.crontext_days, list, at)
        }
        dow == "*" && dom.toIntOrNull() in 1..31 -> tr(R.string.crontext_monthly, dom, at)
        else -> null
    }
}

private fun every(id: Int, n: String): String? = n.toIntOrNull()?.let { trPlural(id, it, it) }
