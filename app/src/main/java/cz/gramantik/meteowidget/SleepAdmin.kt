package cz.gramantik.meteowidget

import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Správa zařízení – jen kvůli zhasnutí obrazovky (box pak usne a přes HDMI-CEC vypne TV). */
class SleepAdminReceiver : DeviceAdminReceiver()

object SleepAdmin {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("meteo", Context.MODE_PRIVATE)
    private fun comp(ctx: Context) = ComponentName(ctx, SleepAdminReceiver::class.java)
    private fun dpm(ctx: Context) = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    /** uspat box po automatickém zavření panelu */
    fun enabled(ctx: Context) = sp(ctx).getBoolean("tvSleepAfter", false)
    fun setEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("tvSleepAfter", v).apply()

    fun isActive(ctx: Context) = try { dpm(ctx).isAdminActive(comp(ctx)) } catch (e: Exception) { false }

    /** Systémový dotaz na povolení. Vrací false, pokud ho systém neumí zobrazit. */
    fun request(a: Activity): Boolean = try {
        a.startActivity(
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp(a))
                .putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Meteostanice tím jen zhasne obrazovku po automatickém zavření panelu, aby box usnul a vypnul i televizi."
                )
        )
        true
    } catch (e: Exception) {
        false
    }

    /** Zhasne obrazovku (uspí box). Vrací true, pokud se to podařilo. */
    fun sleepNow(ctx: Context): Boolean = try {
        if (isActive(ctx)) { dpm(ctx).lockNow(); true } else false
    } catch (e: Exception) {
        false
    }
}
