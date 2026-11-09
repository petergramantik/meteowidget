package cz.gramantik.meteowidget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Aktualizace aplikace z GitHub Releases (petergramantik/meteowidget). */
object Updater {
    private const val API = "https://api.github.com/repos/petergramantik/meteowidget/releases/latest"

    fun currentVersion(ctx: Context): Int =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).let {
            if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt()
            else @Suppress("DEPRECATION") it.versionCode
        }

    /** Zjistí poslední verzi na GitHubu a uloží ji. Vrací číslo verze nebo 0. */
    fun check(ctx: Context): Int {
        val p = Prefs(ctx)
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "meteowidget-android")
        try {
            if (conn.responseCode == 404) return 0 // zatím žádný release
            if (conn.responseCode !in 200..299) throw Exception("GitHub HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            // tag ve tvaru build-123
            val version = json.optString("tag_name").substringAfterLast('-').toIntOrNull() ?: 0
            val assets = json.optJSONArray("assets")
            var url = ""
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) {
                        url = a.optString("browser_download_url")
                        break
                    }
                }
            }
            p.updateCheckTime = System.currentTimeMillis()
            if (url.isNotEmpty()) {
                p.availableVersion = version
                p.availableUrl = url
            }
            return version
        } finally {
            conn.disconnect()
        }
    }

    fun updateAvailable(ctx: Context): Boolean {
        val p = Prefs(ctx)
        return p.availableUrl.isNotEmpty() && p.availableVersion > currentVersion(ctx)
    }

    const val RELEASES_PAGE = "https://github.com/petergramantik/meteowidget/releases/latest"

    class HttpError(val code: Int) : Exception("HTTP $code")

    private fun friendly(e: Exception): String = when {
        e is HttpError && e.code >= 500 -> "Server GitHub je dočasně nedostupný, zkus to za chvíli"
        e is HttpError && e.code == 404 -> "Soubor s novou verzí nebyl nalezen"
        e is java.net.UnknownHostException || e is java.net.SocketTimeoutException ||
            e is java.net.ConnectException || e is java.io.IOException -> "Stahování se přerušilo – zkontroluj připojení"
        else -> e.message ?: "Stahování selhalo"
    }

    private fun download(url: String, file: File, progress: (Int) -> Unit) {
        var conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "meteowidget-android")
        var redirects = 0
        while (conn.responseCode in 300..399 && redirects < 5) {
            val loc = conn.getHeaderField("Location")
            conn.disconnect()
            conn = URL(loc).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "meteowidget-android")
            redirects++
        }
        if (conn.responseCode !in 200..299) {
            val c = conn.responseCode
            conn.disconnect()
            throw HttpError(c)
        }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) progress((done * 100 / total).toInt())
                }
            }
        }
        conn.disconnect()
        if (total > 0 && file.length() != total) throw java.io.IOException("neúplný soubor")
    }

    /** Stáhne APK a předá ho systémovému instalátoru (Android požádá o potvrzení). */
    fun downloadAndInstall(ctx: Context, progress: (Int) -> Unit) {
        val url = Prefs(ctx).availableUrl
        if (url.isEmpty()) throw Exception("Není co instalovat")
        val file = File(ctx.cacheDir, "update.apk")
        // až 3 pokusy – GitHub občas vrátí 5xx nebo spojení spadne
        var lastErr: Exception? = null
        for (attempt in 1..3) {
            try {
                download(url, file, progress)
                lastErr = null
                break
            } catch (e: Exception) {
                lastErr = e
                if (attempt < 3) Thread.sleep(3_000L * attempt)
            }
        }
        lastErr?.let { throw Exception(friendly(it)) }

        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("app.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(ctx, InstallReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                ctx, sessionId, intent,
                (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0) or PendingIntent.FLAG_UPDATE_CURRENT
            )
            session.commit(pi.intentSender)
        }
    }
}
