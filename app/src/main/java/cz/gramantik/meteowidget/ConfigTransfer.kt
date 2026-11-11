package cz.gramantik.meteowidget

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.random.Random

/**
 * Přenos nastavení z telefonu do boxu přes místní Wi-Fi.
 *
 * Telefon spustí na 5 minut malý server a ukáže kód „XXX-YYYY“:
 * XXX = poslední číslo IP adresy telefonu, YYYY = jednorázový PIN.
 * Box doplní zbytek adresy ze své sítě, připojí se a nastavení převezme.
 * Data neopustí domácí síť.
 */
object ConfigTransfer {
    private const val PORT = 48765
    private const val TIMEOUT = 5 * 60_000

    @Volatile private var server: ServerSocket? = null

    /** IPv4 adresa zařízení v místní síti (Wi-Fi / Ethernet). */
    fun localIp(): Inet4Address? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
    } catch (e: Exception) {
        null
    }

    // ---------- co se přenáší: vše kromě dat a stavu zařízení ----------
    private val SKIP_EXACT = setOf(
        "token", "tokenExpiry", "lastData", "lastUpdate", "lastError",
        "forecast", "forecastTime", "forecastFrom", "forecastError", "fullFcTime",
        "pHist", "pBackfill", "histBackfillDone", "histBackfillTry", "histBackfillStats", "histChunksDone", "histCounts",
        "gapTried", "updCheck", "updVersion", "updUrl", "alerts", "alertsTime", "seenAlerts",
        "batteryAsk", "icsEvents", "icsTime", "icsError", "stTuya", "stMapy", "stOwm", "stGoogle", "stIcs",
    )
    private val SKIP_PREFIX = listOf("nLast_", "mini_", "nLog")

    private fun export(ctx: Context): String {
        val sp = ctx.getSharedPreferences("meteo", Context.MODE_PRIVATE)
        val o = JSONObject()
        for ((k, v) in sp.all) {
            if (k in SKIP_EXACT || SKIP_PREFIX.any { k.startsWith(it) } || v == null) continue
            val e = JSONObject()
            when (v) {
                is String -> e.put("t", "s").put("v", v)
                is Boolean -> e.put("t", "b").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is Set<*> -> e.put("t", "set").put("v", org.json.JSONArray(v.map { it.toString() }))
                else -> continue
            }
            o.put(k, e)
        }
        return o.toString()
    }

    private fun import(ctx: Context, json: String) {
        val o = JSONObject(json)
        val ed = ctx.getSharedPreferences("meteo", Context.MODE_PRIVATE).edit()
        for (k in o.keys()) {
            val e = o.optJSONObject(k) ?: continue
            when (e.optString("t")) {
                "s" -> ed.putString(k, e.getString("v"))
                "b" -> ed.putBoolean(k, e.getBoolean("v"))
                "i" -> ed.putInt(k, e.getInt("v"))
                "l" -> ed.putLong(k, e.getLong("v"))
                "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                "set" -> {
                    val a = e.getJSONArray("v")
                    ed.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet())
                }
            }
        }
        // přihlášení k Tuya a stažená data si box vytvoří sám
        ed.remove("token").remove("tokenExpiry").remove("lastData")
        ed.apply()
        // nové plány spuštění TV apod. hned uplatnit
        TvSchedule.reschedule(ctx)
    }

    // ---------- telefon: odeslání ----------
    /**
     * Spustí server a vrátí kód k zadání do boxu (nebo null, když telefon není v síti).
     * [onDone] se zavolá po předání nastavení nebo vypršení (true = předáno).
     */
    fun startSending(ctx: Context, onDone: (Boolean) -> Unit): String? {
        stopSending()
        val ip = localIp() ?: return null
        val last = ip.address[3].toInt() and 0xFF
        val pin = String.format("%04d", Random.nextInt(10_000))
        val payload = export(ctx)
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(PORT))
        ss.soTimeout = TIMEOUT
        server = ss
        Thread {
            var ok = false
            try {
                val end = System.currentTimeMillis() + TIMEOUT
                while (!ok && System.currentTimeMillis() < end) {
                    val sock = ss.accept()
                    sock.use { s ->
                        s.soTimeout = 10_000
                        val req = BufferedReader(InputStreamReader(s.getInputStream())).readLine() ?: ""
                        val body = if (req.contains("pin=$pin")) payload else "{\"error\":\"pin\"}"
                        val bytes = body.toByteArray()
                        val out = s.getOutputStream()
                        out.write(("HTTP/1.0 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n").toByteArray())
                        out.write(bytes)
                        out.flush()
                        if (req.contains("pin=$pin")) ok = true
                    }
                }
            } catch (e: Exception) {
                // vypršelo nebo zrušeno
            } finally {
                try { ss.close() } catch (e: Exception) {}
                if (server === ss) server = null
                onDone(ok)
            }
        }.start()
        return String.format("%d-%s", last, pin)
    }

    fun stopSending() {
        try { server?.close() } catch (e: Exception) {}
        server = null
    }

    // ---------- box: načtení ----------
    /** Stáhne nastavení podle kódu z telefonu. Vrací null při úspěchu, jinak text chyby. */
    fun receive(ctx: Context, code: String): String? {
        val m = Regex("""^\s*(\d{1,3})\D*(\d{4})\s*$""").find(code) ?: return "Kód má tvar např. 123-4567"
        val last = m.groupValues[1].toInt()
        val pin = m.groupValues[2]
        if (last !in 1..254) return "Neplatný kód"
        val own = localIp() ?: return "Box není připojený k síti"
        val b = own.address
        val host = "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}.$last"
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, PORT), 8_000)
                s.soTimeout = 10_000
                s.getOutputStream().write("GET /config?pin=$pin HTTP/1.0\r\n\r\n".toByteArray())
                val text = s.getInputStream().bufferedReader().readText()
                val json = text.substringAfter("\r\n\r\n", "")
                val o = JSONObject(json)
                if (o.has("error")) return "Špatný PIN"
                import(ctx, json)
            }
            null
        } catch (e: java.net.SocketTimeoutException) {
            "Telefon neodpovídá – je ve stejné Wi-Fi a má otevřené Poslat nastavení?"
        } catch (e: java.net.ConnectException) {
            "Telefon nenalezen – zkontroluj kód a že jsou oba ve stejné Wi-Fi"
        } catch (e: Exception) {
            "Přenos selhal: ${e.message}"
        }
    }
}
