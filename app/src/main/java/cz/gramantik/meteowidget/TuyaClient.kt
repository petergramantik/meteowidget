package cz.gramantik.meteowidget

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

open class TuyaException(msg: String) : Exception(msg)
class TokenInvalidException(msg: String) : TuyaException(msg)

/** Minimální klient Tuya Cloud OpenAPI (podepisování HMAC-SHA256). */
class TuyaClient(private val prefs: Prefs) {

    private val emptyBodyHash = sha256("")

    /** Vrátí aktuální stav zařízení jako mapu kód -> hodnota. */
    fun fetchStatus(): Map<String, Any> {
        val data = try {
            readStatus()
        } catch (e: TokenInvalidException) {
            prefs.clearToken()
            readStatus()
        }
        // Specifikaci stačí stáhnout jednou; když selže, použijí se výchozí měřítka.
        if (prefs.spec.isEmpty()) {
            try {
                prefs.spec = fetchSpec()
            } catch (e: Exception) {
                // ignorovat, platí výchozí měřítka
            }
        }
        return data
    }

    /** Stáhne specifikaci zařízení (měřítka a jednotky hodnot). */
    fun fetchSpec(): Map<String, Spec> {
        val json = try {
            request("/v1.0/devices/${prefs.deviceId}/specifications", getToken())
        } catch (e: TokenInvalidException) {
            prefs.clearToken()
            request("/v1.0/devices/${prefs.deviceId}/specifications", getToken())
        }
        val arr = json.getJSONObject("result").optJSONArray("status") ?: return emptyMap()
        val map = LinkedHashMap<String, Spec>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val values = try {
                JSONObject(o.optString("values", "{}"))
            } catch (e: Exception) {
                JSONObject()
            }
            if (values.has("scale") || values.has("unit")) {
                map[o.getString("code")] = Spec(values.optInt("scale", 0), values.optString("unit", ""))
            }
        }
        return map
    }

    /**
     * Historie hlášení jedné hodnoty z Tuya cloudu (event_time v ms, surová hodnota).
     * Parametry dotazu musí být seřazené podle abecedy kvůli podpisu.
     */
    fun fetchReportLogs(code: String, from: Long, to: Long, maxPages: Int = 15): List<Pair<Long, Double>> {
        val out = ArrayList<Pair<Long, Double>>()
        var rowKey: String? = null
        repeat(maxPages) {
            val q = sortedMapOf(
                "codes" to code,
                "end_time" to to.toString(),
                "size" to "100",
                "start_time" to from.toString(),
            )
            rowKey?.let { q["last_row_key"] = it }
            val path = "/v2.0/cloud/thing/${prefs.deviceId}/report-logs?" +
                q.entries.joinToString("&") { "${it.key}=${it.value}" }
            val json = request(path, getToken())
            val r = json.optJSONObject("result") ?: return out
            val logs = r.optJSONArray("logs")
            if (logs != null) {
                for (i in 0 until logs.length()) {
                    val o = logs.getJSONObject(i)
                    val v = o.optString("value").toDoubleOrNull() ?: continue
                    out.add(o.optLong("event_time") to v)
                }
            }
            if (!r.optBoolean("has_more")) return out
            rowKey = r.optString("last_row_key").ifEmpty { return out }
        }
        return out
    }

    /** Starší API pro logy zařízení (záložní, když v2 nic nevrátí). type=7 = hlášení hodnot. */
    fun fetchLogsV1(code: String, from: Long, to: Long, maxPages: Int = 15): List<Pair<Long, Double>> {
        val out = ArrayList<Pair<Long, Double>>()
        var rowKey: String? = null
        repeat(maxPages) {
            val q = sortedMapOf(
                "codes" to code,
                "end_time" to to.toString(),
                "size" to "100",
                "start_time" to from.toString(),
                "type" to "7",
            )
            rowKey?.let { q["start_row_key"] = it }
            val path = "/v1.0/devices/${prefs.deviceId}/logs?" +
                q.entries.joinToString("&") { "${it.key}=${it.value}" }
            val json = request(path, getToken())
            val r = json.optJSONObject("result") ?: return out
            val logs = r.optJSONArray("logs")
            if (logs != null) {
                for (i in 0 until logs.length()) {
                    val o = logs.getJSONObject(i)
                    if (o.optString("code") != code) continue
                    val v = o.optString("value").toDoubleOrNull() ?: continue
                    out.add(o.optLong("event_time") to v)
                }
            }
            if (!r.optBoolean("has_next")) return out
            rowKey = r.optString("next_row_key").ifEmpty { return out }
        }
        return out
    }

    private fun readStatus(): Map<String, Any> {
        val token = getToken()
        val json = request("/v1.0/devices/${prefs.deviceId}/status", token)
        val arr = json.getJSONArray("result")
        val map = LinkedHashMap<String, Any>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            map[o.getString("code")] = o.get("value")
        }
        return map
    }

    private fun getToken(): String {
        val now = System.currentTimeMillis()
        val cached = prefs.token
        if (cached != null && now < prefs.tokenExpiry - 60_000) return cached

        val json = request("/v1.0/token?grant_type=1", null)
        val r = json.getJSONObject("result")
        val token = r.getString("access_token")
        prefs.saveToken(token, now + r.getLong("expire_time") * 1000)
        return token
    }

    private fun request(path: String, token: String?): JSONObject {
        val t = System.currentTimeMillis().toString()
        val nonce = UUID.randomUUID().toString()
        val stringToSign = "GET\n$emptyBodyHash\n\n$path"
        val toSign = prefs.accessId + (token ?: "") + t + nonce + stringToSign
        val sign = hmacSha256(toSign, prefs.accessSecret)

        val conn = URL("https://${prefs.host}$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.requestMethod = "GET"
        conn.setRequestProperty("client_id", prefs.accessId)
        conn.setRequestProperty("sign", sign)
        conn.setRequestProperty("t", t)
        conn.setRequestProperty("nonce", nonce)
        conn.setRequestProperty("sign_method", "HMAC-SHA256")
        if (token != null) conn.setRequestProperty("access_token", token)

        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw TuyaException("HTTP $code")

            val json = JSONObject(body)
            if (!json.optBoolean("success")) {
                val errCode = json.optInt("code")
                val msg = "Tuya chyba $errCode: ${json.optString("msg")}"
                if (token != null && errCode in TOKEN_ERRORS) throw TokenInvalidException(msg)
                throw TuyaException(msg)
            }
            return json
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).toHex()

    private fun hmacSha256(data: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return mac.doFinal(data.toByteArray()).toHex().uppercase(Locale.ROOT)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private val TOKEN_ERRORS = setOf(1010, 1011, 1012)
    }
}
