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
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView

class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var updateStatus: TextView
    private lateinit var updateButton: Button

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
        val googleKeyLabel = findViewById<View>(R.id.google_key_label)
        val locName = findViewById<EditText>(R.id.loc_name)
        val lat = findViewById<EditText>(R.id.loc_lat)
        val lon = findViewById<EditText>(R.id.loc_lon)
        val sources = listOf(
            "Open-Meteo (zdarma, bez klíče)" to Prefs.SOURCE_OPEN_METEO,
            "Google Weather API (vlastní klíč)" to Prefs.SOURCE_GOOGLE,
        )
        source.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sources.map { it.first })
        source.setSelection(sources.indexOfFirst { it.second == prefs.forecastSource }.coerceAtLeast(0))
        source.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val vis = if (sources[pos].second == Prefs.SOURCE_GOOGLE) View.VISIBLE else View.GONE
                googleKey.visibility = vis
                googleKeyLabel.visibility = vis
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        googleKey.setText(prefs.googleKey)
        locName.setText(prefs.locName)
        lat.setText(prefs.lat.toString())
        lon.setText(prefs.lon.toString())

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
            if (credentialsChanged) prefs.clearToken()
            prefs.clearSpec() // při testu vždy znovu načíst měřítka

            button.isEnabled = false
            result.text = "Připojuji se…"
            Thread {
                Sync.run(this, forceForecast = true)
                FetchWorker.schedulePeriodic(this)
                val text = report()
                runOnUiThread {
                    result.text = text
                    button.isEnabled = true
                }
            }.start()
        }
    }

    private fun report(): String = buildString {
        if (!prefs.isConfigured) {
            append("⚠ Stanice: vyplň Access ID, Access Secret a Device ID.\n")
        } else if (prefs.lastError != null) {
            append("❌ Stanice: ${prefs.lastError}\n")
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
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
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
