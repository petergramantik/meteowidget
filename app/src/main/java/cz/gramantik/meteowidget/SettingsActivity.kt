package cz.gramantik.meteowidget

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView

class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var updateStatus: TextView
    private lateinit var updateButton: Button
    private val numFields = ArrayList<Pair<EditText, (Float) -> Unit>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)

        // ---------- aktualizace ----------
        updateStatus = findViewById(R.id.update_status)
        updateButton = findViewById(R.id.update_button)
        updateButton.setOnClickListener { onUpdateClick() }
        refreshUpdateUi()
        Thread {
            try {
                Updater.check(this)
            } catch (e: Exception) {
                // offline apod.
            }
            runOnUiThread { refreshUpdateUi() }
        }.start()

        // ---------- Tuya ----------
        val accessId = findViewById<EditText>(R.id.access_id)
        val secret = findViewById<EditText>(R.id.access_secret)
        val deviceId = findViewById<EditText>(R.id.device_id)
        val dc = findViewById<Spinner>(R.id.data_center)
        dc.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            Prefs.DATA_CENTERS.map { "${it.first} (${it.second})" }
        )
        dc.setSelection(Prefs.DATA_CENTERS.indexOfFirst { it.second == prefs.host }.coerceAtLeast(0))
        accessId.setText(prefs.accessId)
        secret.setText(prefs.accessSecret)
        deviceId.setText(prefs.deviceId)

        // ---------- názvy ----------
        val nameFields = mapOf(
            Channel.IN to R.id.name_in, Channel.OUT to R.id.name_out,
            Channel.S1 to R.id.name_s1, Channel.S2 to R.id.name_s2, Channel.S3 to R.id.name_s3,
        ).mapValues { findViewById<EditText>(it.value) }
        nameFields.forEach { (ch, e) -> e.setText(prefs.channelName(ch)) }

        // ---------- předpověď ----------
        val source = findViewById<Spinner>(R.id.fc_source)
        val googleKey = findViewById<EditText>(R.id.google_key)
        val locName = findViewById<EditText>(R.id.loc_name)
        val lat = findViewById<EditText>(R.id.loc_lat)
        val lon = findViewById<EditText>(R.id.loc_lon)
        val sources = listOf(
            "Open-Meteo (zdarma, bez klíče)" to Prefs.SOURCE_OPEN_METEO,
            "Google Weather API (vlastní klíč)" to Prefs.SOURCE_GOOGLE,
        )
        source.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sources.map { it.first })
        source.setSelection(sources.indexOfFirst { it.second == prefs.forecastSource }.coerceAtLeast(0))
        googleKey.setText(prefs.googleKey)
        val mapy = findViewById<EditText>(R.id.mapy_key)
        mapy.setText(prefs.mapyKey)
        // 👁 – zobrazit / skrýt klíč
        listOf(
            R.id.eye_secret to R.id.access_secret, R.id.eye_mapy to R.id.mapy_key,
            R.id.eye_owm to R.id.owm_key, R.id.eye_google to R.id.google_key,
        ).forEach { (eyeId, fieldId) ->
            val f = findViewById<EditText>(fieldId)
            findViewById<TextView>(eyeId).setOnClickListener {
                val hidden = f.inputType and android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD != 0
                f.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    if (hidden) android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    else android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                f.setSelection(f.text.length)
            }
        }
        showKeyStatus()
        locName.setText(prefs.locName)
        lat.setText(prefs.lat.toString())
        lon.setText(prefs.lon.toString())

        // ---------- upozornění ----------
        val np = NotifyPrefs(this)
        fun bindCheck(id: Int, get: Boolean, set: (Boolean) -> Unit) {
            val cb = findViewById<CheckBox>(id)
            cb.isChecked = get
            cb.setOnCheckedChangeListener { _, v -> set(v) }
        }
        fun bindNum(id: Int, get: Float, set: (Float) -> Unit) {
            val e = findViewById<EditText>(id)
            e.setText(if (get % 1f == 0f) get.toInt().toString() else get.toString())
            e.setOnFocusChangeListener { _, has ->
                if (!has) e.text.toString().replace(',', '.').toFloatOrNull()?.let(set)
            }
            numFields.add(e to set)
        }
        bindCheck(R.id.n_frost, np.frost) { np.frost = it }
        bindNum(R.id.n_frost_lim, np.frostLimit) { np.frostLimit = it }
        bindCheck(R.id.n_rain, np.rain) { np.rain = it }
        bindCheck(R.id.n_wind, np.wind) { np.wind = it }
        bindNum(R.id.n_wind_lim, np.windLimit) { np.windLimit = it }
        bindCheck(R.id.n_pres, np.pressure) { np.pressure = it }
        bindNum(R.id.n_pres_lim, np.pressureLimit) { np.pressureLimit = it }
        bindCheck(R.id.n_uv, np.uv) { np.uv = it }
        bindNum(R.id.n_uv_lim, np.uvLimit) { np.uvLimit = it }
        bindCheck(R.id.n_alerts, np.alerts) { np.alerts = it }
        val region = findViewById<EditText>(R.id.alert_region)
        region.setText(Alerts.region(this))
        bindCheck(R.id.n_hum, np.hum) { np.hum = it }
        bindNum(R.id.n_hum_min, np.humMin) { np.humMin = it }
        bindNum(R.id.n_hum_max, np.humMax) { np.humMax = it }
        bindNum(R.id.comf_t_min, np.tempMin) { np.tempMin = it }
        bindNum(R.id.comf_t_max, np.tempMax) { np.tempMax = it }
        listOf(R.id.indoor_1, R.id.indoor_2, R.id.indoor_3).forEachIndexed { i, id ->
            bindCheck(id, np.indoor(i + 1)) { np.setIndoor(i + 1, it) }
        }

        // ---------- radar ----------
        val owm = findViewById<EditText>(R.id.owm_key)
        owm.setText(prefs.owmKey)

        // ---------- historie ----------
        val backfillResult = findViewById<TextView>(R.id.backfill_result)
        backfillResult.text = backfillStats()
        findViewById<Button>(R.id.backfill).setOnClickListener { btn ->
            if (!prefs.isConfigured) {
                backfillResult.text = "Nejdřív vyplň údaje z Tuya."
                return@setOnClickListener
            }
            btn.isEnabled = false
            backfillResult.text = "Stahuji historii z Tuya… (může trvat i minutu)"
            Thread {
                val n = try {
                    HistoryBackfill.run(this, prefs, TuyaClient(prefs))
                } catch (e: Exception) {
                    -1
                }
                runOnUiThread {
                    btn.isEnabled = true
                    backfillResult.text = (if (n >= 0) "Vloženo $n nových záznamů.\n\n" else "Doplnění selhalo.\n\n") + backfillStats()
                }
            }.start()
        }

        // ---------- televize ----------
        val tv = TvPrefs(this)
        val dayLabel = findViewById<TextView>(R.id.tv_day_label)
        val nightLabel = findViewById<TextView>(R.id.tv_night_label)
        val day = findViewById<SeekBar>(R.id.tv_day)
        val night = findViewById<SeekBar>(R.id.tv_night)
        val nightOn = findViewById<CheckBox>(R.id.tv_night_on)
        val nightFrom = findViewById<EditText>(R.id.tv_night_from)
        val nightTo = findViewById<EditText>(R.id.tv_night_to)
        day.progress = tv.dayBrightness
        night.progress = tv.nightBrightness
        nightOn.isChecked = tv.nightEnabled
        nightFrom.setText(tv.nightFrom.toString())
        nightTo.setText(tv.nightTo.toString())
        fun labels() {
            dayLabel.text = "Jas ve dne: ${day.progress} %"
            nightLabel.text = "Jas v noci: ${night.progress} %" + if (night.progress == 0) " (černá obrazovka)" else ""
        }
        labels()
        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                if (sb === day) tv.dayBrightness = value else tv.nightBrightness = value
                labels()
            }
            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) = Unit
        }
        day.setOnSeekBarChangeListener(seekListener)
        night.setOnSeekBarChangeListener(seekListener)
        day.keyProgressIncrement = 5
        night.keyProgressIncrement = 1
        nightOn.setOnCheckedChangeListener { _, on -> tv.nightEnabled = on }
        val animIcons = findViewById<CheckBox>(R.id.tv_anim_icons)
        animIcons.isChecked = tv.animatedIcons
        animIcons.setOnCheckedChangeListener { _, on -> tv.animatedIcons = on }
        val hourWatcher = { e: EditText, set: (Int) -> Unit ->
            e.setOnFocusChangeListener { _, has ->
                if (!has) e.text.toString().toIntOrNull()?.let { set(it) }
            }
        }
        hourWatcher(nightFrom) { tv.nightFrom = it }
        hourWatcher(nightTo) { tv.nightTo = it }

        // ---------- uložit ----------
        val result = findViewById<TextView>(R.id.result)
        val button = findViewById<Button>(R.id.save)
        button.setOnClickListener {
            val credentialsChanged = accessId.text.toString().trim() != prefs.accessId ||
                secret.text.toString().trim() != prefs.accessSecret ||
                Prefs.DATA_CENTERS[dc.selectedItemPosition].second != prefs.host

            prefs.accessId = accessId.text.toString()
            prefs.accessSecret = secret.text.toString()
            prefs.deviceId = deviceId.text.toString()
            prefs.host = Prefs.DATA_CENTERS[dc.selectedItemPosition].second
            nameFields.forEach { (ch, e) -> prefs.setChannelName(ch, e.text.toString()) }
            prefs.forecastSource = sources[source.selectedItemPosition].second
            prefs.googleKey = googleKey.text.toString()
            prefs.locName = locName.text.toString().ifBlank { "Těmice" }
            lat.text.toString().replace(',', '.').toDoubleOrNull()?.let { prefs.lat = it }
            lon.text.toString().replace(',', '.').toDoubleOrNull()?.let { prefs.lon = it }
            numFields.forEach { (e, set) -> e.text.toString().replace(',', '.').toFloatOrNull()?.let(set) }
            Alerts.setRegion(this, region.text.toString())
            prefs.owmKey = owm.text.toString()
            prefs.mapyKey = mapy.text.toString()
            nightFrom.text.toString().toIntOrNull()?.let { tv.nightFrom = it }
            nightTo.text.toString().toIntOrNull()?.let { tv.nightTo = it }
            if (credentialsChanged) prefs.clearToken()
            prefs.clearSpec() // při testu vždy znovu načíst měřítka

            button.isEnabled = false
            result.text = "Připojuji se…"
            Thread {
                Sync.run(this, forceForecast = true)
                FetchWorker.schedulePeriodic(this)
                testKeys()
                val text = report()
                runOnUiThread {
                    result.text = text
                    button.isEnabled = true
                    showKeyStatus()
                }
            }.start()
        }
    }

    // ---------- test klíčů ----------
    private fun httpCode(url: String): Int = try {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "meteowidget-android")
        val code = c.responseCode
        c.disconnect()
        code
    } catch (e: Exception) {
        -1
    }

    /** Ověří zadané klíče a výsledek uloží (✅/❌ u každého). */
    private fun testKeys() {
        val sp = getSharedPreferences("meteo", MODE_PRIVATE).edit()
        fun res(code: Int) = when (code) {
            in 200..299 -> "✅ funguje"
            401, 403 -> "❌ klíč nefunguje (neplatný nebo zatím neaktivní)"
            -1 -> "⚠ nelze ověřit – bez připojení"
            else -> "❌ chyba HTTP $code"
        }
        sp.putString("stTuya", when {
            !prefs.isConfigured -> "⚠ nevyplněno"
            prefs.lastError == null -> "✅ funguje"
            else -> "❌ " + (friendlyError(prefs.lastError, 0) ?: "")
        })
        sp.putString("stMapy", if (prefs.mapyKey.isEmpty()) "— nevyplněno (satelitní mapa nebude dostupná)"
            else res(httpCode("https://api.mapy.com/v1/maptiles/aerial/256/7/70/44?apikey=" + java.net.URLEncoder.encode(prefs.mapyKey, "UTF-8"))))
        sp.putString("stOwm", if (prefs.owmKey.isEmpty()) "— nevyplněno (nepovinné)"
            else res(httpCode("https://tile.openweathermap.org/map/clouds_new/3/4/2.png?appid=" + java.net.URLEncoder.encode(prefs.owmKey, "UTF-8"))))
        sp.putString("stGoogle", if (prefs.googleKey.isEmpty()) "— nevyplněno (nepovinné)"
            else res(httpCode("https://weather.googleapis.com/v1/forecast/days:lookup?days=1&location.latitude=49&location.longitude=17&key=" +
                java.net.URLEncoder.encode(prefs.googleKey, "UTF-8"))))
        sp.apply()
    }

    private fun showKeyStatus() {
        val sp = getSharedPreferences("meteo", MODE_PRIVATE)
        listOf(R.id.st_tuya to "stTuya", R.id.st_mapy to "stMapy", R.id.st_owm to "stOwm", R.id.st_google to "stGoogle")
            .forEach { (id, k) -> findViewById<TextView>(id).text = sp.getString(k, "") }
    }

    private fun backfillStats(): String {
        val st = HistoryBackfill.stats(this)
        if (st.isEmpty()) return "Doplnění historie zatím neproběhlo."
        val t = HistoryBackfill.lastRun(this)
        return "Poslední doplnění: " +
            java.text.SimpleDateFormat("d.M. HH:mm", java.util.Locale("cs", "CZ")).format(java.util.Date(t)) + "\n" +
            st.entries.joinToString("\n") { "${it.key}: ${it.value}" }
    }

    private fun report(): String = buildString {
        if (!prefs.isConfigured) {
            append("⚠ Stanice: vyplň Access ID, Access Secret a Device ID.\n")
        } else if (prefs.lastError != null) {
            append("❌ Stanice: ${friendlyError(prefs.lastError, 0)}\n   (${prefs.lastError})\n")
        } else {
            append("✅ Stanice funguje, obnova každých 15 min.\n")
        }
        val fc = prefs.forecast
        if (prefs.forecastError != null) append("❌ Předpověď: ${prefs.forecastError}\n")
        else append("✅ Předpověď: ${fc.size} dní\n")

        val data = prefs.lastData
        if (data != null && prefs.lastError == null) {
            val spec = prefs.spec
            if (spec.isEmpty()) append("(specifikaci se nepodařilo načíst – výchozí měřítka)\n")
            append("\n")
            data.forEach { (code, value) ->
                val s = spec[code]
                append(if (s == null) "$code: $value\n" else "$code: $value  [scale ${s.scale}, ${s.unit}]\n")
            }
        }
    }

    // ---------- aktualizace ----------
    private fun refreshUpdateUi() {
        val cur = Updater.currentVersion(this)
        if (Updater.updateAvailable(this)) {
            updateStatus.text = "Verze 1.$cur · k dispozici je nová verze 1.${prefs.availableVersion}"
            updateButton.text = "Nainstalovat 1.${prefs.availableVersion}"
        } else {
            updateStatus.text = "Verze 1.$cur · aktuální"
            updateButton.text = "Zkontrolovat aktualizace"
        }
        updateButton.isEnabled = true
    }

    private fun onUpdateClick() {
        if (!Updater.updateAvailable(this)) {
            updateButton.isEnabled = false
            updateStatus.text = "Kontroluji…"
            Thread {
                val err = try {
                    Updater.check(this); null
                } catch (e: Exception) {
                    e.message
                }
                runOnUiThread {
                    refreshUpdateUi()
                    if (err != null) updateStatus.text = "⚠ $err"
                }
            }.start()
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            updateStatus.text = "Povol aplikaci instalovat aktualizace a vrať se zpět."
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                // některé TV boxy tuto obrazovku nemají – povolení se hledá v Nastavení → Zabezpečení
                updateStatus.text = "Povol v Nastavení → Zabezpečení → Neznámé zdroje aplikaci Meteostanice."
            }
            return
        }
        updateButton.isEnabled = false
        Thread {
            try {
                Updater.downloadAndInstall(this) { pct ->
                    runOnUiThread { updateStatus.text = "Stahuji… $pct %" }
                }
                runOnUiThread { updateStatus.text = "Potvrď instalaci v systémovém okně." }
            } catch (e: Exception) {
                runOnUiThread {
                    updateStatus.text = "⚠ ${e.message}"
                    updateButton.isEnabled = true
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        if (::updateButton.isInitialized) refreshUpdateUi()
    }
}
