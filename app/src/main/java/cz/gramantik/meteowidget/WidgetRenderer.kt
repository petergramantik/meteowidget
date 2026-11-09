package cz.gramantik.meteowidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
        for (id in ids(ctx, MeteoWidgetProvider::class.java)) mgr.updateAppWidget(id, buildMain(ctx, s, id))
        for (id in ids(ctx, MiniWidgetProvider::class.java)) mgr.updateAppWidget(id, buildMini(ctx, s, id))
    }

    fun showRefreshing(ctx: Context) = updateAll(ctx, refreshing = true)

    /** Data sdílená všemi rozloženími při jednom překreslení. */
    class State(ctx: Context, val refreshing: Boolean) {
        val p = Prefs(ctx)
        /** jedna bitmapa sdílená všemi rozloženími (RemoteViews ji pošle jen jednou) */
        val graph: Bitmap by lazy { PressureHistory.render(ctx) }
        val trend: Double? = PressureHistory.trend3h(ctx)
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

    /** Klepnutí na widget otevře aplikaci. */
    private fun appIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            ctx, 2, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
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
    private fun buildMain(ctx: Context, s: State, id: Int): RemoteViews {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            return RemoteViews(
                mapOf(
                    SizeF(110f, 70f) to buildSmall(ctx, s),
                    SizeF(250f, 200f) to buildFull(ctx, s, withForecast = false, withGraph = false),
                    SizeF(300f, 320f) to buildFull(ctx, s, withForecast = true, withGraph = false),
                    SizeF(300f, 410f) to buildFull(ctx, s, withForecast = true, withGraph = true),
                )
            )
        }
        // starší Android: rozložení podle velikosti z možností widgetu
        val o = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(id)
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300)
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 410)
        return when {
            w < 250 || h < 200 -> buildSmall(ctx, s)
            h < 320 -> buildFull(ctx, s, withForecast = false, withGraph = false)
            h < 410 -> buildFull(ctx, s, withForecast = true, withGraph = false)
            else -> buildFull(ctx, s, withForecast = true, withGraph = true)
        }
    }

    private fun header(ctx: Context, v: RemoteViews, s: State) {
        v.setOnClickPendingIntent(R.id.root, appIntent(ctx))
        v.setTextViewText(R.id.label_out, s.p.channelName(Channel.OUT).uppercase(CZ))
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

    /** Celoobrazovkový panel pro televizi – stejné prvky, jiné rozložení. */
    fun buildTv(ctx: Context): RemoteViews {
        val s = State(ctx, false)
        return buildFull(
            ctx, s, withForecast = true, withGraph = true,
            layout = R.layout.tv_dashboard,
            graph = PressureHistory.render(ctx, widthDp = 300, heightDp = 110),
        )
    }

    private fun buildFull(
        ctx: Context, s: State, withForecast: Boolean, withGraph: Boolean,
        layout: Int = R.layout.widget_full, graph: Bitmap? = null,
    ): RemoteViews {
        val v = RemoteViews(ctx.packageName, layout)
        header(ctx, v, s)
        v.setOnClickPendingIntent(R.id.settings, settingsIntent(ctx))
        v.setViewVisibility(R.id.update_badge, if (s.updateBadge) View.VISIBLE else View.GONE)
        v.setOnClickPendingIntent(R.id.update_badge, settingsIntent(ctx))

        val p = s.p
        val w = s.w
        v.setTextViewText(R.id.label_out, p.channelName(Channel.OUT).uppercase(CZ))
        v.setTextViewText(R.id.label_in, p.channelName(Channel.IN).uppercase(CZ))
        fun num(x: Double?) = x?.let { Weather.n1(it) } ?: "--"

        v.setTextViewText(R.id.temp_out, Weather.t(w?.tempOut))
        v.setTextViewText(R.id.hum_out, Weather.h(w?.humOut))
        v.setTextViewText(R.id.temp_in, Weather.t(w?.tempIn))
        v.setTextViewText(R.id.hum_in, Weather.h(w?.humIn))

        v.setTextViewText(R.id.wind_speed, num(w?.windAvg))
        v.setTextViewText(R.id.wind_info, w?.windGust?.let { "km/h · nárazy ${Weather.n1(it)}" } ?: "km/h")
        val dir = w?.windDir
        v.setViewVisibility(R.id.wind_dir, if (dir == null) View.GONE else View.VISIBLE)
        v.setTextViewText(R.id.wind_dir, dir ?: "")

        v.setTextViewText(R.id.feels, Weather.t(w?.feelsLike))
        v.setTextViewText(R.id.uv, num(w?.uv))
        v.setTextViewText(R.id.light, num(w?.lightKlux))
        val arrow = when {
            s.trend == null -> ""
            s.trend >= 1.0 -> " ↗"
            s.trend <= -1.0 -> " ↘"
            else -> " →"
        }
        v.setTextViewText(R.id.pressure, w?.pressure?.let { String.format(CZ, "%.0f", it) + arrow } ?: "--")

        if (withGraph) {
            v.setViewVisibility(R.id.graph_section, View.VISIBLE)
            v.setImageViewBitmap(R.id.pressure_graph, graph ?: s.graph)
            v.setTextViewText(
                R.id.graph_title,
                "TLAK 24 H" + (s.trend?.let { " · ZA 3 H " + String.format(CZ, "%+.1f hPa", it) } ?: "")
            )
        } else {
            v.setViewVisibility(R.id.graph_section, View.GONE)
        }

        v.setTextViewText(R.id.rain, w?.rainDay?.let { "${Weather.n1(it)} mm" } ?: "--")
        val rate = w?.rainRate?.takeIf { it > 0 }
        v.setViewVisibility(R.id.rain_rate, if (rate == null) View.GONE else View.VISIBLE)
        v.setTextViewText(R.id.rain_rate, rate?.let { "${Weather.n1(it)} mm/h" } ?: "")
        v.setTextViewText(R.id.dew, Weather.t(w?.dewPoint))

        // čidla 1–3, nebo hláška, když data ještě nejsou
        if (w == null) {
            v.setViewVisibility(R.id.sensors_cell, View.VISIBLE)
            v.setTextViewText(R.id.sensors_label, "")
            v.setTextViewText(R.id.sensors, s.placeholder ?: "")
            if (!p.isConfigured) v.setOnClickPendingIntent(R.id.root, settingsIntent(ctx))
        } else {
            val sensors = listOf(Channel.S1, Channel.S2, Channel.S3).mapNotNull { ch ->
                val t = w.temp(ch.tempCode) ?: return@mapNotNull null
                "${p.channelName(ch)}  ${Weather.t(t)} · ${Weather.h(w.hum(ch.humCode))}"
            }
            v.setViewVisibility(R.id.sensors_cell, if (sensors.isEmpty()) View.GONE else View.VISIBLE)
            v.setTextViewText(R.id.sensors_label, "ČIDLA")
            v.setTextViewText(R.id.sensors, sensors.joinToString("\n"))
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
                days.isEmpty() && p.forecastError != null -> "PŘEDPOVĚĎ  ⚠ ${p.forecastError}"
                days.isEmpty() -> "PŘEDPOVĚĎ SE NAČÍTÁ…"
                else -> "PŘEDPOVĚĎ 10 DNÍ · ${p.locName.uppercase(CZ)} · $src"
            }
        )
        v.removeAllViews(R.id.forecast_row)
        val today = LocalDate.now()
        for (d in days.take(10)) {
            val c = RemoteViews(ctx.packageName, R.layout.forecast_cell)
            c.setTextViewText(R.id.fc_day, dayName(d.date, today))
            val res = iconRes(ctx, d.icon)
            if (res != 0) {
                c.setImageViewResource(R.id.fc_img, res)
            } else {
                c.setViewVisibility(R.id.fc_img, View.GONE)
                c.setViewVisibility(R.id.fc_icon, View.VISIBLE)
                c.setTextViewText(R.id.fc_icon, d.icon)
            }
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

    /** Název ikony Meteocons -> drawable (0 = neexistuje). */
    @android.annotation.SuppressLint("DiscouragedApi")
    fun iconRes(ctx: Context, key: String): Int =
        ctx.resources.getIdentifier("mc_" + key.replace('-', '_'), "drawable", ctx.packageName)

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
        v.setOnClickPendingIntent(R.id.root, appIntent(ctx))
        val ch = s.p.miniChannel(widgetId)
        v.setTextViewText(R.id.mini_name, if (s.refreshing) "obnovuji…" else s.p.channelName(ch).uppercase(CZ))
        val color = ctx.getColor(
            when (ch) {
                Channel.OUT -> R.color.lcd_out
                Channel.IN -> R.color.lcd_in
                else -> R.color.lcd_sensor
            }
        )
        v.setTextColor(R.id.mini_temp, color)
        v.setTextColor(R.id.mini_hum, color)
        val w = s.w
        v.setTextViewText(R.id.mini_temp, Weather.t(w?.temp(ch.tempCode)))
        v.setTextViewText(R.id.mini_hum, Weather.h(w?.hum(ch.humCode)))
        if (!s.p.isConfigured) v.setOnClickPendingIntent(R.id.root, settingsIntent(ctx))
        return v
    }
}
