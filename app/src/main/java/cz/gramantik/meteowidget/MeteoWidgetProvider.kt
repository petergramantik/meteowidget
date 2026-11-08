package cz.gramantik.meteowidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

/** Velký widget – rozložení se mění podle velikosti. */
class MeteoWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        WidgetRenderer.updateAll(ctx)
        FetchWorker.schedulePeriodic(ctx)
        // Nestahovat znovu, pokud jsou data čerstvá (chrání i před opakovanými onUpdate).
        if (System.currentTimeMillis() - Prefs(ctx).lastUpdate > 2 * 60_000) {
            FetchWorker.refreshNow(ctx)
        }
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, opts: Bundle) {
        WidgetRenderer.updateAll(ctx)
    }

    override fun onEnabled(ctx: Context) = FetchWorker.schedulePeriodic(ctx)

    override fun onDisabled(ctx: Context) = FetchWorker.cancelIfNoWidgets(ctx)

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH) {
            WidgetRenderer.showRefreshing(ctx)
            FetchWorker.refreshNow(ctx)
        }
    }

    companion object {
        const val ACTION_REFRESH = "cz.gramantik.meteowidget.REFRESH"
    }
}

/** Malý widget 2×1 – teplota a vlhkost jednoho zvoleného kanálu. */
class MiniWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        WidgetRenderer.updateAll(ctx)
        FetchWorker.schedulePeriodic(ctx)
        if (System.currentTimeMillis() - Prefs(ctx).lastUpdate > 2 * 60_000) {
            FetchWorker.refreshNow(ctx)
        }
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        val p = Prefs(ctx)
        ids.forEach { p.removeMini(it) }
    }

    override fun onEnabled(ctx: Context) = FetchWorker.schedulePeriodic(ctx)

    override fun onDisabled(ctx: Context) = FetchWorker.cancelIfNoWidgets(ctx)
}
