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

    // ---------- co se přenáší ----------
    private val STRING_KEYS = listOf(
        "accessId", "accessSecret", "deviceId", "host", "mapyKey", "owmKey", "googleKey",
        "locName", "lat", "lon", "fcSource", "alertRegion",
        "name_in", "name_out", "name_s1", "name_s2", "name_s3",
    )
    private val BOOL_KEYS = listOf("indoor1", "indoor2", "indoor3", "nHum")
    private val FLOAT_KEYS = listOf("nHumMin", "nHumMax", "comfTMin", "comfTMax")

    private fun export(ctx: Context): String {
        val sp = ctx.getSharedPreferences("meteo", Context.MODE_PRIVATE)
        val o = JSONObject()
        STRING_KEYS.forEach { k -> sp.getString(k, null)?.let { o.put(k, it) } }
        BOOL_KEYS.forEach { k -> if (sp.contains(k)) o.put(k, sp.getBoolean(k, true)) }
        FLOAT_KEYS.forEach { k -> if (sp.contains(k)) o.put(k, sp.getFloat(k, 0f).toDouble()) }
        // spec (měřítka hodnot) se převezme taky, ať box nemusí hádat
        sp.getString("spec", null)?.let { o.put("spec", it) }
        return o.toString()
    }

    private fun import(ctx: Context, json: String) {
        val o = JSONObject(json)
        val e = ctx.getSharedPreferences("meteo", Context.MODE_PRIVATE).edit()
        STRING_KEYS.forEach { k -> if (o.has(k)) e.putString(k, o.getString(k)) }
        BOOL_KEYS.forEach { k -> if (o.has(k)) e.putBoolean(k, o.getBoolean(k)) }
        FLOAT_KEYS.forEach { k -> if (o.has(k)) e.putFloat(k, o.getDouble(k).toFloat()) }
        if (o.has("spec")) e.putString("spec", o.getString("spec"))
        e.remove("token").remove("tokenExpiry")
        e.apply()
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
