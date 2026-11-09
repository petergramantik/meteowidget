package cz.gramantik.meteowidget

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Jedno kolo stahování: stanice, předpověď (1× za hodinu), kontrola aktualizace (2× denně). */
object Sync {
    private const val FORECAST_EVERY = 60 * 60_000L
    private const val UPDATE_EVERY = 12 * 60 * 60_000L

    /** [heavy] = i doplňování historie z Tuya (pomalé kvůli limitům); test v nastavení ho vynechá. */
    fun run(ctx: Context, forceForecast: Boolean = false, heavy: Boolean = true) {
        val p = Prefs(ctx)
        val db = MeteoDb.get(ctx)
        if (p.isConfigured) {
            val client = TuyaClient(p)
            try {
                val data = client.fetchStatus()
                val t = System.currentTimeMillis()
                p.saveData(data, t)
                val w = Weather(data, p.spec)
                w.pressure?.let { PressureHistory.add(ctx, listOf(t to it)) }
                val prev = db.last()
                // mezera v historii (telefon uspal aplikaci) -> zkusit doplnit z Tuya
                if (heavy && prev != null && t - prev.t > 30 * 60_000L) {
                    try {
                        HistoryBackfill.fillGap(ctx, p, client, prev.t, t)
                    } catch (e: Exception) {
                        // nevadí
                    }
                }
                // do historie nejvýš 1 záznam za 5 minut
                if (prev == null || t - prev.t > 5 * 60_000L) db.insert(Sample.from(t, w))
                try {
                    Notify.checkStation(ctx, w, prev)
                } catch (e: Exception) {
                    // upozornění nesmí shodit stahování
                }
            } catch (e: Exception) {
                p.lastError = e.message ?: e.javaClass.simpleName
            }
            if (heavy) {
                backfillPressure(ctx, p, client)
                try {
                    HistoryBackfill.runIfNeeded(ctx, p, client)
                } catch (e: Exception) {
                    // zkusí se později
                }
            }
        }
        val now = System.currentTimeMillis()
        if (forceForecast || now - p.forecastTime > FORECAST_EVERY || p.forecastFrom != p.forecastSource) {
            try {
                p.forecast = ForecastClient.fetch(p)
                if (p.forecastSource == Prefs.SOURCE_GOOGLE) ForecastFull.logTomorrow(ctx, p.forecast, "google")
            } catch (e: Exception) {
                p.forecastError = e.message ?: e.javaClass.simpleName
            }
        }
        if (forceForecast || now - ForecastFull.fetchedAt(ctx) > FORECAST_EVERY) {
            try {
                ForecastFull.fetch(ctx)
            } catch (e: Exception) {
                // podrobná předpověď je jen pro aplikaci
            }
        }
        if (forceForecast || now - Alerts.fetchedAt(ctx) > 30 * 60_000L) {
            try {
                Alerts.fetch(ctx)
            } catch (e: Exception) {
                // výstrahy nejsou kritické
            }
        }
        // kalendář (iCal) – každých 30 min, nebo hned po půlnoci kvůli novému dni
        if (CalendarIcs.url(ctx).isNotEmpty() &&
            (forceForecast || now - CalendarIcs.fetchedAt(ctx) > 30 * 60_000L ||
                java.time.Instant.ofEpochMilli(CalendarIcs.fetchedAt(ctx)).atZone(java.time.ZoneId.systemDefault()).toLocalDate() != java.time.LocalDate.now())
        ) {
            CalendarIcs.fetch(ctx)
        }
        if (now - p.updateCheckTime > UPDATE_EVERY) {
            try {
                Updater.check(ctx)
            } catch (e: Exception) {
                // nevadí, zkusí se příště
            }
        }
        WidgetRenderer.updateAll(ctx)
    }

    /** Dotáhne historii tlaku z Tuya cloudu, pokud je místní krátká (nejvýš 1× za 6 h). */
    private fun backfillPressure(ctx: Context, p: Prefs, client: TuyaClient) {
        val sp = ctx.getSharedPreferences("meteo", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!PressureHistory.needsBackfill(ctx)) return
        if (now - sp.getLong("pBackfill", 0) < 6 * 60 * 60_000L) return
        sp.edit().putLong("pBackfill", now).apply()
        try {
            val code = "atmospheric_pressture"
            val scale = p.spec[code]?.scale ?: 1
            val div = Math.pow(10.0, scale.toDouble())
            val logs = client.fetchReportLogs(code, now - 25 * 60 * 60_000L, now)
            PressureHistory.add(ctx, logs.map { it.first to it.second / div })
        } catch (e: Exception) {
            // historie v cloudu nemusí být dostupná – graf se naplní postupně sám
        }
    }
}

class FetchWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        Sync.run(applicationContext)
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "meteo_periodic"
        private const val ONCE = "meteo_once"

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Pravidelná obnova každých 15 minut (minimum, které Android dovolí). */
        fun schedulePeriodic(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<FetchWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun refreshNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<FetchWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, req)
        }

        /** Sběr historie běží i bez widgetů, proto se plán už nikdy neruší. */
        @Suppress("UNUSED_PARAMETER")
        fun cancelIfNoWidgets(ctx: Context) = Unit
    }
}
