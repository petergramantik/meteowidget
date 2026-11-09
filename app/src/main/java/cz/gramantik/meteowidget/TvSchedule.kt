package cz.gramantik.meteowidget

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.lang.ref.WeakReference
import java.util.Calendar

/**
 * Plánované spuštění a vypnutí panelu na televizi (set-top box).
 * Spuštění: budík (alarm clock) probudí box a otevře DashboardActivity.
 * Vypnutí: panel se zavře, box pak může přejít do pohotovosti.
 */
object TvSchedule {
    private const val ACTION_STOP = "cz.gramantik.meteowidget.TV_STOP"
    private const val REQ_START = 501
    private const val REQ_STOP = 502
    private const val REQ_TEST = 503

    private fun sp(ctx: Context) = ctx.getSharedPreferences("meteo", Context.MODE_PRIVATE)

    fun startEnabled(ctx: Context) = sp(ctx).getBoolean("tvStartOn", false)
    fun startTime(ctx: Context) = sp(ctx).getString("tvStartTime", "06:30") ?: "06:30"
    fun stopEnabled(ctx: Context) = sp(ctx).getBoolean("tvStopOn", false)
    fun stopTime(ctx: Context) = sp(ctx).getString("tvStopTime", "23:00") ?: "23:00"
    fun bootStart(ctx: Context) = sp(ctx).getBoolean("tvBootStart", false)
    /** spouštět jen v pracovní dny (mimo víkendy a státní svátky) */
    fun workdaysOnly(ctx: Context) = sp(ctx).getBoolean("tvWorkdays", false)
    fun setWorkdaysOnly(ctx: Context, v: Boolean) { sp(ctx).edit().putBoolean("tvWorkdays", v).apply(); reschedule(ctx) }
    /** automatické zavření panelu spuštěného budíkem (minuty, 0 = nezavírat) */
    fun autoCloseMin(ctx: Context) = sp(ctx).getInt("tvAutoClose", 30)
    fun setAutoCloseMin(ctx: Context, v: Int) = sp(ctx).edit().putInt("tvAutoClose", v.coerceIn(0, 24 * 60)).apply()

    /** Státní svátek v ČR (včetně Velkého pátku a Velikonočního pondělí). */
    fun isCzHoliday(d: java.time.LocalDate): Boolean {
        val fixed = setOf(101, 501, 508, 705, 706, 928, 1028, 1117, 1224, 1225, 1226)
        if (d.monthValue * 100 + d.dayOfMonth in fixed) return true
        val easter = easterSunday(d.year)
        return d == easter.minusDays(2) || d == easter.plusDays(1)
    }

    /** Velikonoční neděle (gregoriánský výpočet). */
    private fun easterSunday(y: Int): java.time.LocalDate {
        val a = y % 19; val b = y / 100; val c = y % 100
        val d = b / 4; val e = b % 4; val f = (b + 8) / 25; val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30; val i = c / 4; val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7; val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31; val day = (h + l - 7 * m + 114) % 31 + 1
        return java.time.LocalDate.of(y, month, day)
    }

    private fun isWorkday(d: java.time.LocalDate) =
        d.dayOfWeek != java.time.DayOfWeek.SATURDAY && d.dayOfWeek != java.time.DayOfWeek.SUNDAY && !isCzHoliday(d)

    fun save(ctx: Context, startOn: Boolean, start: String, stopOn: Boolean, stop: String, boot: Boolean) {
        sp(ctx).edit()
            .putBoolean("tvStartOn", startOn).putString("tvStartTime", normalize(start, "06:30"))
            .putBoolean("tvStopOn", stopOn).putString("tvStopTime", normalize(stop, "23:00"))
            .putBoolean("tvBootStart", boot)
            .apply()
        reschedule(ctx)
    }

    /** "6:5" -> "06:05"; neplatné -> výchozí. */
    fun normalize(s: String, def: String): String {
        val p = s.trim().replace('.', ':').split(':')
        val h = p.getOrNull(0)?.toIntOrNull() ?: return def
        val m = p.getOrNull(1)?.toIntOrNull() ?: 0
        if (h !in 0..23 || m !in 0..59) return def
        return String.format("%02d:%02d", h, m)
    }

    private fun nextAt(time: String, workdays: Boolean = false): Long {
        val (h, m) = time.split(':').map { it.toInt() }
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= System.currentTimeMillis() + 5_000) c.add(Calendar.DAY_OF_YEAR, 1)
        if (workdays) {
            // přeskočit víkendy a svátky
            var guard = 0
            while (guard++ < 14) {
                val d = java.time.LocalDate.of(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
                if (isWorkday(d)) break
                c.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return c.timeInMillis
    }

    /** Čas příštího automatického spuštění (pro zobrazení v nastavení), nebo null. */
    fun nextStart(ctx: Context): Long? = if (startEnabled(ctx)) nextAt(startTime(ctx), workdaysOnly(ctx)) else null

    private fun startIntent(ctx: Context, req: Int): PendingIntent {
        val i = Intent(ctx, DashboardActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(DashboardActivity.EXTRA_SCHEDULED, true)
        return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun stopIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, TvAlarmReceiver::class.java).setAction(ACTION_STOP)
        return PendingIntent.getBroadcast(ctx, REQ_STOP, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun am(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    private fun canExact(ctx: Context) = Build.VERSION.SDK_INT < 31 || am(ctx).canScheduleExactAlarms()

    /** Budík, který probudí zařízení a otevře panel. */
    private fun setStartAlarm(ctx: Context, at: Long, req: Int) {
        val pi = startIntent(ctx, req)
        if (canExact(ctx)) {
            am(ctx).setAlarmClock(AlarmManager.AlarmClockInfo(at, pi), pi)
        } else {
            am(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Naplánuje budíky podle nastavení (volá se po uložení, po spuštění a po zapnutí boxu). */
    fun reschedule(ctx: Context) {
        val am = am(ctx)
        am.cancel(startIntent(ctx, REQ_START))
        am.cancel(stopIntent(ctx))
        if (startEnabled(ctx)) setStartAlarm(ctx, nextAt(startTime(ctx), workdaysOnly(ctx)), REQ_START)
        if (stopEnabled(ctx)) {
            val at = nextAt(stopTime(ctx))
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, stopIntent(ctx))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, stopIntent(ctx))
        }
    }

    /** Zkouška automatického vypnutí: otevře panel, který se za 1 minutu zavře. */
    fun testAutoClose(ctx: Context) {
        ctx.startActivity(
            Intent(ctx, DashboardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(DashboardActivity.EXTRA_AUTOCLOSE_MIN, 1)
        )
    }

    /** Zkušební spuštění za 2 minuty. */
    fun testIn2Minutes(ctx: Context) = setStartAlarm(ctx, System.currentTimeMillis() + 2 * 60_000L, REQ_TEST)

    // ---------- povolení „Zobrazovat přes jiné aplikace“ (spuštění z pozadí) ----------
    fun canDrawOverlays(ctx: Context) = Settings.canDrawOverlays(ctx)

    fun requestOverlay(ctx: Context): Boolean = try {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: Exception) {
        false
    }

    // ---------- běžící panel (kvůli vypnutí) ----------
    var running: WeakReference<Activity>? = null

    internal fun stopPanel(ctx: Context) {
        running?.get()?.finish()
        reschedule(ctx)
    }
}

/** Vypnutí panelu v naplánovaný čas. */
class TvAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        TvSchedule.stopPanel(ctx)
    }
}

/** Po zapnutí boxu: obnovit budíky, případně rovnou spustit panel. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        TvSchedule.reschedule(ctx)
        FetchWorker.schedulePeriodic(ctx)
        if (TvSchedule.bootStart(ctx)) {
            try {
                ctx.startActivity(
                    Intent(ctx, DashboardActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(DashboardActivity.EXTRA_SCHEDULED, true)
                )
            } catch (e: Exception) {
                // bez povolení „Zobrazovat přes jiné aplikace“ to systém nemusí dovolit
            }
        }
    }
}
