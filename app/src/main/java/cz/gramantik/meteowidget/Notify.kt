package cz.gramantik.meteowidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.time.LocalDate
import java.util.Locale

/** Nastavení upozornění. */
class NotifyPrefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("meteo", Context.MODE_PRIVATE)
    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun setB(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
    private fun f(k: String, d: Float) = sp.getFloat(k, d)
    private fun setF(k: String, v: Float) = sp.edit().putFloat(k, v).apply()

    var frost: Boolean get() = b("nFrost", true); set(v) = setB("nFrost", v)
    var frostLimit: Float get() = f("nFrostLim", 0f); set(v) = setF("nFrostLim", v)
    var rain: Boolean get() = b("nRain", true); set(v) = setB("nRain", v)
    var wind: Boolean get() = b("nWind", true); set(v) = setB("nWind", v)
    var windLimit: Float get() = f("nWindLim", 50f); set(v) = setF("nWindLim", v)
    var pressure: Boolean get() = b("nPres", true); set(v) = setB("nPres", v)
    var pressureLimit: Float get() = f("nPresLim", 3f); set(v) = setF("nPresLim", v)
    var uv: Boolean get() = b("nUv", false); set(v) = setB("nUv", v)
    var uvLimit: Float get() = f("nUvLim", 6f); set(v) = setF("nUvLim", v)
    var alerts: Boolean get() = b("nAlerts", true); set(v) = setB("nAlerts", v)

    // hlídání vlhkosti doma
    var hum: Boolean get() = b("nHum", true); set(v) = setB("nHum", v)
    var humMin: Float get() = f("nHumMin", 40f); set(v) = setF("nHumMin", v)
    var humMax: Float get() = f("nHumMax", 60f); set(v) = setF("nHumMax", v)

    /** Která čidla zobrazit v grafu vlhkosti na obrazovce Stanice (1–3). */
    fun humChart(i: Int): Boolean = b("humChart$i", false)
    fun setHumChart(i: Int, v: Boolean) = setB("humChart$i", v)
}

object Notify {
    private const val CHANNEL = "meteo_alerts"

    private fun manager(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    private fun ensureChannel(ctx: Context) {
        val m = manager(ctx)
        if (m.getNotificationChannel(CHANNEL) == null) {
            m.createNotificationChannel(
                NotificationChannel(CHANNEL, "Upozornění na počasí", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    private fun canPost(ctx: Context) =
        ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun post(ctx: Context, id: Int, title: String, text: String) {
        if (!canPost(ctx)) return
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 10, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager(ctx).notify(id, n)
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences("meteo", Context.MODE_PRIVATE)

    /** Pošle upozornění nejvýš jednou za [cooldown] ms. */
    private fun once(ctx: Context, key: String, cooldown: Long, block: () -> Unit) {
        val now = System.currentTimeMillis()
        if (now - sp(ctx).getLong("nLast_$key", 0) < cooldown) return
        sp(ctx).edit().putLong("nLast_$key", now).apply()
        block()
    }

    private fun f1(v: Double) = String.format(Locale("cs", "CZ"), "%.1f", v)

    fun checkStation(ctx: Context, w: Weather, prev: Sample?) {
        val n = NotifyPrefs(ctx)
        val h = 3_600_000L
        val out = w.tempOut
        if (n.frost && out != null && out <= n.frostLimit) {
            once(ctx, "frost", 12 * h) {
                post(ctx, 1, "❄ Mráz", "Venku je ${f1(out)} °C.")
            }
        }
        val rate = w.rainRate
        if (n.rain && rate != null && rate > 0 && (prev?.rrate ?: 0.0) <= 0.0) {
            once(ctx, "rain", 3 * h) {
                post(ctx, 2, "🌧 Začalo pršet", "Intenzita ${f1(rate)} mm/h.")
            }
        }
        val gust = w.windGust
        if (n.wind && gust != null && gust >= n.windLimit) {
            once(ctx, "wind", 3 * h) {
                post(ctx, 3, "💨 Silný vítr", "Nárazy větru ${f1(gust)} km/h.")
            }
        }
        val trend = PressureHistory.trend3h(ctx)
        if (n.pressure && trend != null && trend <= -n.pressureLimit) {
            once(ctx, "pres", 6 * h) {
                post(ctx, 4, "⚡ Tlak rychle klesá", "Za 3 hodiny ${f1(trend)} hPa – možná změna počasí nebo bouřka.")
            }
        }
        val hin = w.humIn
        if (n.hum && hin != null) {
            if (hin < n.humMin) {
                once(ctx, "humLow", 6 * h) {
                    post(ctx, 6, "💧 Nízká vlhkost doma", "Vlhkost doma $hin % – pod ${n.humMin.toInt()} %. Zvlhči vzduch.")
                }
            } else if (hin > n.humMax) {
                once(ctx, "humHigh", 6 * h) {
                    post(ctx, 7, "💦 Vysoká vlhkost doma", "Vlhkost doma $hin % – nad ${n.humMax.toInt()} %. Vyvětrej.")
                }
            }
        }
        val uv = w.uv
        if (n.uv && uv != null && uv >= n.uvLimit) {
            val key = "uv_" + LocalDate.now()
            once(ctx, key, 24 * h) {
                post(ctx, 5, "☀ Vysoké UV", "UV index ${f1(uv)} – chraň se před sluncem.")
            }
        }
    }

    fun checkAlerts(ctx: Context, list: List<WarnAlert>) {
        if (!NotifyPrefs(ctx).alerts) return
        val seen = sp(ctx).getStringSet("seenAlerts", emptySet())!!.toMutableSet()
        for (a in list) {
            if (a.id in seen) continue
            seen.add(a.id)
            post(ctx, 100 + (a.id.hashCode() and 0xFFF), "⚠ Výstraha ČHMÚ: ${a.event}", a.level + ". " + a.description.take(200))
        }
        // držet jen posledních 50
        sp(ctx).edit().putStringSet("seenAlerts", seen.toList().takeLast(50).toSet()).apply()
    }
}
