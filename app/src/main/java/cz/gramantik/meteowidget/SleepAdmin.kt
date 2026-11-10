package cz.gramantik.meteowidget

import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Správa zařízení – jen kvůli zhasnutí obrazovky (box pak usne a přes HDMI-CEC vypne TV). */
class SleepAdminReceiver : DeviceAdminReceiver()

/** Služba Usnadnění – umí jen zamknout obrazovku (Android 9+). Nic nečte. */
class SleepA11yService : android.accessibilityservice.AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }

    companion object {
        @Volatile var instance: SleepA11yService? = null
    }
}

object SleepAdmin {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("meteo", Context.MODE_PRIVATE)
    private fun comp(ctx: Context) = ComponentName(ctx, SleepAdminReceiver::class.java)
    private fun dpm(ctx: Context) = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    /** uspat box po automatickém zavření panelu */
    fun enabled(ctx: Context) = sp(ctx).getBoolean("tvSleepAfter", false)
    fun setEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("tvSleepAfter", v).apply()

    fun adminActive(ctx: Context) = try { dpm(ctx).isAdminActive(comp(ctx)) } catch (e: Exception) { false }

    /** Je zapnutá služba Usnadnění? */
    fun a11yActive(ctx: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 28) return false
        if (SleepA11yService.instance != null) return true
        val list = android.provider.Settings.Secure.getString(ctx.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.split(':').any { it.startsWith(ctx.packageName + "/") && it.endsWith("SleepA11yService") }
    }

    fun isActive(ctx: Context) = a11yActive(ctx) || adminActive(ctx)

    /** Otevře systémové Usnadnění (tam se zapne „Meteostanice – uspání“). */
    fun openA11ySettings(a: Activity): Boolean = try {
        a.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)); true
    } catch (e: Exception) { false }

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
    fun sleepNow(ctx: Context): Boolean {
        // 1) Usnadnění – zamknutí obrazovky (jde ovladačem povolit i na TV)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val svc = SleepA11yService.instance
            if (svc != null && svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) return true
        }
        // 2) záloha: Správa zařízení
        return try {
            if (adminActive(ctx)) { dpm(ctx).lockNow(); true } else false
        } catch (e: Exception) {
            false
        }
    }
}
