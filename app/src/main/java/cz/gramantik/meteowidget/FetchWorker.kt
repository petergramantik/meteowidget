package cz.gramantik.meteowidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
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

    fun run(ctx: Context, forceForecast: Boolean = false) {
        val p = Prefs(ctx)
        if (p.isConfigured) {
            try {
                p.saveData(TuyaClient(p).fetchStatus(), System.currentTimeMillis())
            } catch (e: Exception) {
                p.lastError = e.message ?: e.javaClass.simpleName
            }
        }
        val now = System.currentTimeMillis()
        if (forceForecast || now - p.forecastTime > FORECAST_EVERY || p.forecastFrom != p.forecastSource) {
            try {
                p.forecast = ForecastClient.fetch(p)
            } catch (e: Exception) {
                p.forecastError = e.message ?: e.javaClass.simpleName
            }
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

        /** Zruší pravidelnou obnovu, jen pokud na ploše nezůstal žádný widget. */
        fun cancelIfNoWidgets(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val any = listOf(MeteoWidgetProvider::class.java, MiniWidgetProvider::class.java)
                .any { mgr.getAppWidgetIds(ComponentName(ctx, it)).isNotEmpty() }
            if (!any) {
                WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)
                WorkManager.getInstance(ctx).cancelUniqueWork(ONCE)
            }
        }
    }
}
