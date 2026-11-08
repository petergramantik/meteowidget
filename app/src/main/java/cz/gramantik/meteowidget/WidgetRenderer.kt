package cz.gramantik.meteowidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

object WidgetRenderer {

    private val CZ = Locale("cs", "CZ")

    private fun ids(ctx: Context, cls: Class<*>): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, cls))

    fun updateAll(ctx: Context, refreshing: Boolean = false) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val s = State(ctx, refreshing)
        for (id in ids(ctx, MeteoWidgetProvider::class.java)) mgr.updateAppWidget(id, buildMain(ctx, s))
        for (id in ids(ctx, MiniWidgetProvider::class.java)) mgr.updateAppWidget(id, buildMini(ctx, s, id))
    }

    fun showRefreshing(ctx: Context) = updateAll(ctx, refreshing = true)

    /** Data sdílená všemi rozloženími při jednom překreslení. */
    private class State(ctx: Context, val refreshing: Boolean) {
        val p = Prefs(ctx)
        val data = p.lastData
        val w = data?.let { Weather(it, p.spec) }
        val updateBadge = Updater.updateAvailable(ctx)
        val timeText: String = when {
            refreshing -> "obnovuji…"
            data == null -> ""
            else -> {
                val age = System.currentTimeMillis() - p.lastUpdate
                val fmt = if (age > 12 * 3_600_000) "d.M. HH:mm" else "HH:mm"
                val t = SimpleDateFormat(fmt, Locale("cs", "CZ")).format(Date(p.lastUpdate))
                if (p.lastError != null) "⚠ $t" else t
            }
        }
        val placeholder: String? = when {
            !p.isConfigured -> "Klepni na ⚙ a zadej údaje z Tuya"
            data == null -> p.lastError?.let { "⚠ $it" } ?: "Načítám…"
            else -> null
        }
    }

    // ---------- intenty ----------
    private fun refreshIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, MeteoWidgetProvider::class.java)
            .setAction(MeteoWidgetProvider.ACTION_REFRESH)
        return PendingIntent.getBroadcast(
            ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun settingsIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            ctx, 1, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // ---------- velký widget ----------
    private fun buildMain(ctx: Context, s: State): RemoteViews =
        RemoteViews(
            mapOf(
                SizeF(110f, 70f) to buildSmall(ctx, s),
                SizeF(250f, 150f) to buildFull(ctx, s, withForecast = false),
                SizeF(300f, 270f) to buildFull(ctx, s, withForecast = true),
            )
        )

    private fun header(ctx: Context, v: RemoteViews, s: State) {
        v.setOnClickPendingIntent(R.id.root, refreshIntent(ctx))
        v.setTextViewText(R.id.label_out, s.p.channelName(Channel.OUT))
        v.setTextViewText(R.id.updated, s.timeText)
    }

    private fun buildSmall(ctx: Context, s: State): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_small)
        header(ctx, v, s)
        val w = s.w
        if (w == null) {
            v.setTextViewText(R.id.temp_out, "--°")
            v.setTextViewText(R.id.hum_out, "")
            v.setTextViewText(R.id.line_in, s.placeholder ?: "")
            if (!s.p.isConfigured) v.setOnClickPendingIntent(R.id.root, settingsIntent(ctx))
            return v
        }
        v.setTextViewText(R.id.temp_out, Weather.t(w.tempOut))
        v.setTextViewText(R.id.hum_out, Weather.h(w.humOut))
        v.setTextViewText(R.id.line_in, "${s.p.channelName(Channel.IN)}  ${Weather.t(w.tempIn)} · ${Weather.h(w.humIn)}")
        return v
    }

    private fun setLine(v: RemoteViews, id: Int, parts: List<String>, sep: String = " · ") {
        v.setViewVisibility(id, if (parts.isEmpty()) View.GONE else View.VISIBLE)
        v.setTextViewText(id, parts.joinToString(sep))
    }

    private fun buildFull(ctx: Context, s: State, withForecast: Boolean): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_full)
        header(ctx, v, s)
        v.setOnClickPendingIntent(R.id.settings, settingsIntent(ctx))
        v.setViewVisibility(R.id.update_badge, if (s.updateBadge) View.VISIBLE else View.GONE)
        v.setOnClickPendingIntent(R.id.update_badge, settingsIntent(ctx))

        val p = s.p
        val w = s.w
        if (w == null) {
            v.setTextViewText(R.id.temp_out, "--°")
            v.setTextViewText(R.id.hum_out, "")
            v.setTextViewText(R.id.line_in, s.placeholder ?: "")
            for (id in listOf(R.id.line_sensors, R.id.line_wind, R.id.line_rain, R.id.line_extra)) {
                v.setViewVisibility(id, View.GONE)
            }
        } else {
            v.setTextViewText(R.id.temp_out, Weather.t(w.tempOut))
            v.setTextViewText(R.id.hum_out, Weather.h(w.humOut))
            v.setTextViewText(R.id.line_in, "${p.channelName(Channel.IN)}  ${Weather.t(w.tempIn)} · ${Weather.h(w.humIn)}")

            val sensors = listOf(Channel.S1, Channel.S2, Channel.S3).mapNotNull { ch ->
                val t = w.temp(ch.tempCode) ?: return@mapNotNull null
                "${p.channelName(ch)}  ${Weather.t(t)} · ${Weather.h(w.hum(ch.humCode))}"
            }
            setLine(v, R.id.line_sensors, sensors, "\n")

            setLine(v, R.id.line_wind, buildList {
                w.windAvg?.let { add("💨 ${Weather.n1(it)} km/h") }
                w.windGust?.let { add("nárazy ${Weather.n1(it)}") }
                w.windDir?.let { add(it) }
            })
            setLine(v, R.id.line_rain, buildList {
                w.rainDay?.let { add("🌧 ${Weather.n1(it)} mm") }
                w.rainRate?.takeIf { it > 0 }?.let { add("${Weather.n1(it)} mm/h") }
                w.uv?.let { add("UV ${Weather.n1(it)}") }
                w.lightKlux?.let { add("☀ ${Weather.n1(it)} klux") }
            })
            setLine(v, R.id.line_extra, buildList {
                w.pressure?.let { add(Weather.hpa(it)) }
                w.feelsLike?.let { add("pocitově ${Weather.t(it)}") }
                w.dewPoint?.let { add("rosný bod ${Weather.t(it)}") }
            })
        }

        if (!withForecast) {
            v.setViewVisibility(R.id.forecast_section, View.GONE)
            return v
        }
        v.setViewVisibility(R.id.forecast_section, View.VISIBLE)
        val src = if (p.forecastFrom == Prefs.SOURCE_GOOGLE) "Google" else "Open-Meteo"
        val days = p.forecast
        v.setTextViewText(
            R.id.forecast_title,
            when {
                days.isEmpty() && p.forecastError != null -> "Předpověď: ⚠ ${p.forecastError}"
                days.isEmpty() -> "Předpověď se načítá…"
                else -> "Předpověď · ${p.locName} · $src"
            }
        )
        v.removeAllViews(R.id.forecast_row)
        val today = LocalDate.now()
        for (d in days.take(10)) {
            val c = RemoteViews(ctx.packageName, R.layout.forecast_cell)
            c.setTextViewText(R.id.fc_day, dayName(d.date, today))
            c.setTextViewText(R.id.fc_icon, d.icon)
            c.setTextViewText(R.id.fc_max, deg(d.tMax))
            c.setTextViewText(R.id.fc_min, deg(d.tMin))
            c.setTextViewText(
                R.id.fc_rain,
                if (d.precipMm >= 0.1) Weather.n1(d.precipMm) else ""
            )
            v.addView(R.id.forecast_row, c)
        }
        return v
    }

    private fun deg(v: Double) = if (v.isNaN()) "--" else "${v.roundToInt()}°"

    private fun dayName(date: String, today: LocalDate): String {
        val d = try {
            LocalDate.parse(date)
        } catch (e: Exception) {
            return "?"
        }
        if (d == today) return "Dnes"
        return when (d.dayOfWeek) {
            DayOfWeek.MONDAY -> "Po"
            DayOfWeek.TUESDAY -> "Út"
            DayOfWeek.WEDNESDAY -> "St"
            DayOfWeek.THURSDAY -> "Čt"
            DayOfWeek.FRIDAY -> "Pá"
            DayOfWeek.SATURDAY -> "So"
            else -> "Ne"
        }
    }

    // ---------- mini widget ----------
    private fun buildMini(ctx: Context, s: State, widgetId: Int): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_mini)
        v.setOnClickPendingIntent(R.id.root, refreshIntent(ctx))
        val ch = s.p.miniChannel(widgetId)
        v.setTextViewText(R.id.mini_name, if (s.refreshing) "obnovuji…" else s.p.channelName(ch))
        val w = s.w
        v.setTextViewText(R.id.mini_temp, Weather.t(w?.temp(ch.tempCode)))
        v.setTextViewText(R.id.mini_hum, Weather.h(w?.hum(ch.humCode)))
        if (!s.p.isConfigured) v.setOnClickPendingIntent(R.id.root, settingsIntent(ctx))
        return v
    }
}
