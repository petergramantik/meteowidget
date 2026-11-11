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
import cz.gramantik.meteowidget.ui.isTv
import cz.gramantik.meteowidget.ui.tvFocus

class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var updateStatus: TextView
    private lateinit var updateButton: Button
    private val numFields = ArrayList<Pair<EditText, (Float) -> Unit>>()
    private var scheduleSaver: (() -> Unit)? = null
    private var morningSaver: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)
        setupTransfer()

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
        val ics = findViewById<EditText>(R.id.ics_url)
        ics.setText(CalendarIcs.url(this))
        // 👁 – zobrazit / skrýt klíč
        listOf(
            R.id.eye_secret to R.id.access_secret, R.id.eye_mapy to R.id.mapy_key,
            R.id.eye_owm to R.id.owm_key, R.id.eye_google to R.id.google_key, R.id.eye_ics to R.id.ics_url,
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

        // ---------- sběr na pozadí ----------
        findViewById<Button>(R.id.battery_button).setOnClickListener {
            if (!Battery.isUnrestricted(this)) Battery.requestExemption(this) else Battery.openAppSettings(this)
        }

        // ---------- historie ----------
        val backfillResult = findViewById<TextView>(R.id.backfill_result)
        backfillResult.text = backfillStats()
        findViewById<Button>(R.id.backfill).setOnClickListener {
            if (!prefs.isConfigured) {
                backfillResult.text = "Nejdřív vyplň údaje z Tuya."
                return@setOnClickListener
            }
            startBackgroundBackfill(restart = true)
        }

        // ---------- televize: plánované spuštění ----------
        val tvStartOn = findViewById<CheckBox>(R.id.tv_start_on)
        val tvStart = findViewById<EditText>(R.id.tv_start_time)
        val tvStopOn = findViewById<CheckBox>(R.id.tv_stop_on)
        val tvStop = findViewById<EditText>(R.id.tv_stop_time)
        val tvBoot = findViewById<CheckBox>(R.id.tv_boot)
        tvStartOn.isChecked = TvSchedule.startEnabled(this)
        tvStart.setText(TvSchedule.startTime(this))
        tvStopOn.isChecked = TvSchedule.stopEnabled(this)
        tvStop.setText(TvSchedule.stopTime(this))
        tvBoot.isChecked = TvSchedule.bootStart(this)
        val saveSchedule = {
            TvSchedule.save(
                this, tvStartOn.isChecked, tvStart.text.toString(),
                tvStopOn.isChecked, tvStop.text.toString(), tvBoot.isChecked
            )
        }
        scheduleSaver = saveSchedule
        listOf(tvStartOn, tvStopOn, tvBoot).forEach { it.setOnCheckedChangeListener { _, _ -> saveSchedule() } }
        listOf(tvStart, tvStop).forEach { e -> e.setOnFocusChangeListener { _, has -> if (!has) saveSchedule() } }
        val workdays = findViewById<CheckBox>(R.id.tv_workdays)
        val nextStart = findViewById<TextView>(R.id.tv_next_start)
        fun showNext() {
            nextStart.text = TvSchedule.nextStart(this)?.let {
                "Příští spuštění: " + java.text.SimpleDateFormat("EEEE d. M. HH:mm", java.util.Locale("cs", "CZ")).format(java.util.Date(it))
            } ?: ""
        }
        workdays.isChecked = TvSchedule.workdaysOnly(this)
        workdays.setOnCheckedChangeListener { _, on -> TvSchedule.setWorkdaysOnly(this, on); showNext() }
        listOf(tvStartOn, tvStopOn, tvBoot).forEach { cb ->
            cb.setOnCheckedChangeListener { _, _ -> saveSchedule(); showNext() }
        }
        showNext()
        bindNum(R.id.tv_autoclose, TvSchedule.autoCloseMin(this).toFloat()) { TvSchedule.setAutoCloseMin(this, it.toInt()) }
        val idleSp = findViewById<Spinner>(R.id.tv_idle_return)
        val idleOpts = listOf("po 5 minutách" to 5, "po 15 minutách" to 15, "po 30 minutách" to 30, "po 1 hodině" to 60, "nikdy" to 0)
        idleSp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, idleOpts.map { it.first })
        idleSp.setSelection(idleOpts.indexOfFirst { it.second == TvSchedule.idleReturnMin(this) }.coerceAtLeast(0))
        idleSp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) = TvSchedule.setIdleReturnMin(this@SettingsActivity, idleOpts[pos].second)
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        val sleepCb = findViewById<CheckBox>(R.id.tv_sleep)
        sleepCb.isChecked = SleepAdmin.enabled(this)
        sleepCb.setOnCheckedChangeListener { _, on ->
            SleepAdmin.setEnabled(this, on)
            if (on && !SleepAdmin.isActive(this)) {
                if (android.os.Build.VERSION.SDK_INT >= 28) openSleepA11y() else SleepAdmin.request(this)
            }
            showSleepStatus()
        }
        findViewById<Button>(R.id.tv_sleep_a11y_btn).setOnClickListener { openSleepA11y() }
        findViewById<Button>(R.id.tv_sleep_btn).setOnClickListener {
            if (!SleepAdmin.request(this)) android.widget.Toast.makeText(this,
                "Tento box neumí zobrazit povolení Správy zařízení. Zkus Nastavení boxu → Zabezpečení → Správci zařízení.",
                android.widget.Toast.LENGTH_LONG).show()
        }
        showSleepStatus()
        findViewById<Button>(R.id.tv_autoclose_test).setOnClickListener {
            numFields.forEach { (e, set) -> e.text.toString().replace(',', '.').toFloatOrNull()?.let(set) }
            TvSchedule.testAutoClose(this)
            android.widget.Toast.makeText(this, "Panel se za 1 minutu zavře. Nech ovladač ležet – box pak usne podle svého nastavení.",
                android.widget.Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.tv_overlay_btn).setOnClickListener {
            if (!TvSchedule.requestOverlay(this)) {
                findViewById<TextView>(R.id.tv_overlay_status).text =
                    "⚠ Box nenabízí obrazovku povolení. Najdi v Nastavení → Aplikace → Zvláštní přístup → " +
                        "Zobrazovat přes jiné aplikace → Meteostanice."
            }
        }
        findViewById<Button>(R.id.tv_test_btn).setOnClickListener {
            saveSchedule()
            TvSchedule.testIn2Minutes(this)
            android.widget.Toast.makeText(
                this, "Za 2 minuty se spustí panel. Teď vypni box ovladačem do pohotovosti.", android.widget.Toast.LENGTH_LONG
            ).show()
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

        // ---------- televize: pozdrav a upozornění ----------
        val mp = MorningPrefs(this)
        val tmNames = findViewById<EditText>(R.id.tm_names)
        tmNames.setText(mp.names)
        val saveMorning = { mp.names = tmNames.text.toString() }
        morningSaver = saveMorning
        tmNames.setOnFocusChangeListener { _, has -> if (!has) saveMorning() }
        bindNum(R.id.tm_frost, mp.frostBelow) { mp.frostBelow = it }
        bindNum(R.id.tm_wind, mp.windFrom) { mp.windFrom = it }
        bindNum(R.id.tm_rain_mm, mp.rainMm) { mp.rainMm = it }
        bindNum(R.id.tm_rain_pct, mp.rainPct) { mp.rainPct = it }

        buildMenu()

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
            scheduleSaver?.invoke()
            morningSaver?.invoke()
            Alerts.setRegion(this, region.text.toString())
            prefs.owmKey = owm.text.toString()
            prefs.mapyKey = mapy.text.toString()
            CalendarIcs.setUrl(this, ics.text.toString())
            nightFrom.text.toString().toIntOrNull()?.let { tv.nightFrom = it }
            nightTo.text.toString().toIntOrNull()?.let { tv.nightTo = it }
            if (credentialsChanged) prefs.clearToken()
            prefs.clearSpec() // při testu vždy znovu načíst měřítka

            button.isEnabled = false
            result.text = "Připojuji se…"
            Thread {
                // rychlý test bez doplňování historie
                Sync.run(this, forceForecast = true, heavy = false)
                FetchWorker.schedulePeriodic(this)
                testKeys()
                val text = report()
                runOnUiThread {
                    result.text = text
                    button.isEnabled = true
                    showKeyStatus()
                }
                // historie se doplní odděleně na pozadí (s průběhem v sekci Historie)
                if (prefs.isConfigured && prefs.lastError == null) startBackgroundBackfill(restart = false)
            }.start()
        }
    }

    // ---------- ovládání ovladačem (TV) ----------
    /** Žlutý rámeček u vybraného prvku; ikonky 👁 se při procházení přeskočí. */
    private fun setupTvFocus() {
        listOf(R.id.eye_secret, R.id.eye_mapy, R.id.eye_owm, R.id.eye_google, R.id.eye_ics).forEach {
            findViewById<View>(it)?.isFocusable = false
        }
        fun walk(v: View) {
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            if (v is EditText || v is Button || v is CheckBox || v is Spinner || v is SeekBar) {
                v.tvFocus(scale = if (v is EditText || v is SeekBar || v is Spinner) 1.0f else 1.04f, light = true)
            }
        }
        walk(window.decorView)
    }


    private fun openSleepA11y() {
        if (SleepAdmin.openA11ySettings(this)) android.widget.Toast.makeText(this,
            "V Usnadnění vyber „Meteostanice – uspání“ a zapni ji.", android.widget.Toast.LENGTH_LONG).show()
        else android.widget.Toast.makeText(this, "Nastavení Usnadnění se nepodařilo otevřít.", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun showSleepStatus() {
        val st = findViewById<TextView>(R.id.tv_sleep_status) ?: return
        val a11y = SleepAdmin.a11yActive(this)
        val admin = SleepAdmin.adminActive(this)
        val how = if (a11y) "přes Usnadnění" else "přes Správu zařízení"
        st.text = when {
            (a11y || admin) && SleepAdmin.enabled(this) -> "✅ Box se po automatickém zavření uspí ($how)"
            a11y || admin -> "Povoleno $how – zapni přepínač výše"
            android.os.Build.VERSION.SDK_INT >= 28 -> "⚠ Uspání zatím není povolené – klikni na „Povolit uspání přes Usnadnění“"
            else -> "⚠ Uspání zatím není povolené – klikni na „Povolit přes Správu zařízení“"
        } + if (!(a11y || admin) && SleepAdmin.enabled(this))
            "\nBez povolení: po zavření se ukáže černá obrazovka a box usne podle svého časovače spánku." else ""
        findViewById<View>(R.id.tv_sleep_a11y_btn)?.visibility =
            if (a11y || android.os.Build.VERSION.SDK_INT < 28) View.GONE else View.VISIBLE
        findViewById<View>(R.id.tv_sleep_btn)?.visibility = if (a11y || admin) View.GONE else View.VISIBLE
    }

    // ---------- rozcestník podsekcí ----------
    private data class Sec(val id: Int, val title: String, val status: () -> String)

    private val sections by lazy {
        listOf(
            Sec(R.id.sec_keys, "🔑 Klíče a přístupy") { keysStatus() },
            Sec(R.id.sec_station, "📟 Stanice a čidla") {
                val np = NotifyPrefs(this)
                "${prefs.channelName(Channel.IN)} / ${prefs.channelName(Channel.OUT)} · komfort " +
                    "${np.humMin.toInt()}–${np.humMax.toInt()} % · ${np.tempMin.toInt()}–${np.tempMax.toInt()} °C"
            },
            Sec(R.id.sec_forecast, "📅 Předpověď a místo") {
                (if (prefs.forecastSource == Prefs.SOURCE_GOOGLE) "Google" else "Open-Meteo") +
                    " · ${prefs.locName} · výstrahy: ${Alerts.region(this).ifBlank { "vše" }}"
            },
            Sec(R.id.sec_notify, "🔔 Upozornění") {
                val np = NotifyPrefs(this)
                val on = listOf(np.frost, np.rain, np.wind, np.pressure, np.uv, np.alerts, np.hum).count { it }
                if (on == 0) "vše vypnuto" else "zapnuto $on ze 7"
            },
            Sec(R.id.sec_tv, "📺 Televize") {
                val mp = MorningPrefs(this)
                (if (TvSchedule.startEnabled(this)) "start ${TvSchedule.startTime(this)}" else "bez plánu") +
                    " · oslovení: ${mp.names.ifBlank { "—" }}" + if (isTv() && !TvSchedule.canDrawOverlays(this)) " · ⚠ chybí povolení" else ""
            },
            Sec(R.id.sec_data, "🗄 Data a historie") {
                (if (Battery.isUnrestricted(this)) "✅ sběr bez omezení" else "⚠ omezeno baterií") +
                    " · ${MeteoDb.get(this).count()} záznamů"
            },
            Sec(R.id.sec_app, "ℹ Aplikace") {
                val cur = Updater.currentVersion(this)
                if (Updater.updateAvailable(this)) "Verze 1.$cur · ⬆ nová 1.${prefs.availableVersion}" else "Verze 1.$cur · aktuální"
            },
        )
    }
    private val menuRows = ArrayList<View>()
    private var updateBanner: android.widget.LinearLayout? = null

    private fun refreshUpdateBanner() {
        val b = updateBanner ?: return
        val avail = try { Updater.updateAvailable(this) } catch (e: Exception) { false }
        b.visibility = if (avail) View.VISIBLE else View.GONE
        if (avail) b.findViewWithTag<TextView>("txt")?.text = "⬆ Nová verze 1.${prefs.availableVersion} je k dispozici"
    }
    private val menuStatus = ArrayList<TextView>()
    private var openSection: Int? = null

    private fun keysStatus(): String {
        val sp = getSharedPreferences("meteo", MODE_PRIVATE)
        fun mark(k: String, filled: Boolean): String {
            val st = sp.getString(k, "") ?: ""
            return when {
                !filled -> "—"
                st.startsWith("✅") -> "✅"
                st.startsWith("❌") -> "❌"
                st.isEmpty() -> "neověřeno"
                else -> "⚠"
            }
        }
        return "Tuya ${mark("stTuya", prefs.isConfigured)} · Mapy ${mark("stMapy", prefs.mapyKey.isNotEmpty())} · " +
            "OWM ${mark("stOwm", prefs.owmKey.isNotEmpty())} · Kalendář ${mark("stIcs", CalendarIcs.url(this).isNotEmpty())} · " +
            "Google ${mark("stGoogle", prefs.googleKey.isNotEmpty())}"
    }

    private fun buildMenu() {
        val box = findViewById<android.widget.LinearLayout>(R.id.sec_menu)
        box.addView(TextView(this).apply {
            text = "Nastavení"; textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
        })
        val d = resources.displayMetrics.density
        // nová verze – hned nahoře
        updateBanner = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFFFD54F.toInt()); cornerRadius = 12 * d; setStroke((1 * d).toInt(), 0xFFFFD54F.toInt())
            }
            isClickable = true
            isFocusable = true
            addView(TextView(this@SettingsActivity).apply {
                textSize = 16f; setTextColor(0xFF060709.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD)
                tag = "txt"
            }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@SettingsActivity).apply {
                text = "Aktualizovat ›"; textSize = 15f; setTextColor(0xFF060709.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            setOnClickListener {
                showSection(R.id.sec_app)
                updateButton.post { updateButton.performClick() }
            }
            tvFocus(restoreStroke = 0xFFFFD54F.toInt(), scale = 1.0f, light = true)
        }
        box.addView(updateBanner, android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (10 * d).toInt() })
        refreshUpdateBanner()
        // kontrola nové verze při každém otevření nastavení (na pozadí)
        Thread {
            try { Updater.check(this) } catch (e: Exception) { }
            runOnUiThread {
                refreshUpdateBanner()
                if (isTv() && openSection == null && updateBanner?.visibility == View.VISIBLE && currentFocus !is EditText) updateBanner?.requestFocus()
            }
        }.start()
        sections.forEach { sec ->
            val st = TextView(this).apply { textSize = 13f; setTextColor(0xFF8A8A8A.toInt()) }
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0x14888888); cornerRadius = 12 * d; setStroke((1 * d).toInt(), 0x33888888)
                }
                isClickable = true
                isFocusable = true
                addView(TextView(this@SettingsActivity).apply {
                    text = sec.title; textSize = 17f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(st)
                setOnClickListener { showSection(sec.id) }
            }
            row.tvFocus(restoreStroke = 0x33888888, scale = 1.0f, light = true)
            box.addView(row, android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * d).toInt() })
            menuRows.add(row)
            menuStatus.add(st)
        }
        refreshMenu()
    }

    private fun refreshMenu() {
        sections.forEachIndexed { i, sec ->
            menuStatus.getOrNull(i)?.text = try { sec.status() } catch (e: Exception) { "" }
        }
    }

    private fun showSection(id: Int?) {
        currentFocus?.clearFocus() // uloží rozepsané pole (posluchači při ztrátě focusu)
        openSection = id
        findViewById<View>(R.id.sec_menu).visibility = if (id == null) View.VISIBLE else View.GONE
        sections.forEach { findViewById<View>(it.id).visibility = if (it.id == id) View.VISIBLE else View.GONE }
        findViewById<View>(R.id.sec_footer).visibility = if (id == null) View.GONE else View.VISIBLE
        (findViewById<View>(R.id.sec_menu).parent.parent as? android.widget.ScrollView)?.scrollTo(0, 0)
        if (id == null) {
            refreshMenu()
            if (isTv()) menuRows.getOrNull(sections.indexOfFirst { it.id == lastSection })?.requestFocus()
        } else {
            lastSection = id
            if (isTv()) findViewById<View>(id).let { sec ->
                sec.post { sec.focusSearch(View.FOCUS_DOWN)?.requestFocus() ?: sec.requestFocus(View.FOCUS_DOWN) }
            }
        }
    }
    private var lastSection: Int? = null

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (openSection != null) showSection(null)
        else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ---------- přenos nastavení telefon -> box ----------
    private fun setupTransfer() {
        findViewById<Button>(R.id.send_cfg).setOnClickListener {
            val status = TextView(this).apply { textSize = 15f; setPadding(48, 24, 48, 0) }
            val dlg = android.app.AlertDialog.Builder(this)
                .setTitle("Poslat nastavení do TV")
                .setView(status)
                .setNegativeButton("Zavřít") { _, _ -> ConfigTransfer.stopSending() }
                .setOnCancelListener { ConfigTransfer.stopSending() }
                .create()
            val code = try {
                ConfigTransfer.startSending(this) { ok ->
                    runOnUiThread {
                        status.text = if (ok) "✅ Nastavení předáno do boxu." else "Platnost kódu vypršela."
                    }
                }
            } catch (e: Exception) {
                null
            }
            status.text = if (code == null) "Telefon není připojený k Wi-Fi."
            else "Na boxu otevři Nastavení → Načíst z telefonu a zadej kód:\n\n      $code\n\nKód platí 5 minut. Nech tuto obrazovku otevřenou."
            dlg.show()
        }
        findViewById<Button>(R.id.recv_cfg).setOnClickListener {
            val input = EditText(this).apply {
                hint = "např. 123-4567"
                inputType = android.text.InputType.TYPE_CLASS_PHONE
            }
            android.app.AlertDialog.Builder(this)
                .setTitle("Načíst z telefonu")
                .setMessage("V telefonu dej Nastavení → Poslat do TV a opiš sem zobrazený kód.")
                .setView(input)
                .setPositiveButton("Načíst") { _, _ ->
                    val code = input.text.toString()
                    Thread {
                        val err = ConfigTransfer.receive(this, code)
                        if (err == null) {
                            runOnUiThread {
                                android.widget.Toast.makeText(this, "✅ Nastavení načteno – ověřuji klíče…", android.widget.Toast.LENGTH_LONG).show()
                            }
                            // klíče na tomto zařízení rovnou otestovat
                            try {
                                Sync.run(this, forceForecast = true, heavy = false)
                                testKeys()
                            } catch (e: Exception) {
                                // stav se doplní při příštím „Uložit a otestovat“
                            }
                        }
                        runOnUiThread {
                            if (err == null) {
                                android.widget.Toast.makeText(this, "✅ Nastavení načteno a klíče ověřeny", android.widget.Toast.LENGTH_LONG).show()
                                recreate() // znovu načíst pole z nového nastavení
                            } else {
                                android.widget.Toast.makeText(this, "❌ $err", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }.start()
                }
                .setNegativeButton("Zrušit", null)
                .show()
        }
    }

    /** Zvýraznění až po nastavení všech posluchačů v onCreate (aby se zachovaly). */
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        setupTvFocus()
        if (isTv()) (updateBanner?.takeIf { it.visibility == View.VISIBLE } ?: menuRows.firstOrNull())?.requestFocus()
    }

    override fun onDestroy() {
        ConfigTransfer.stopSending()
        super.onDestroy()
    }

    // ---------- doplňování historie na pozadí ----------
    private var backfillRunning = false

    private fun startBackgroundBackfill(restart: Boolean) {
        if (backfillRunning) return
        backfillRunning = true
        val btn = findViewById<Button>(R.id.backfill)
        val out = findViewById<TextView>(R.id.backfill_result)
        btn.isEnabled = false
        val ticker = object : Runnable {
            override fun run() {
                if (!backfillRunning) return
                out.text = (HistoryBackfill.progress ?: "Doplňuji historii z Tuya…") +
                    "\n(kvůli limitům Tuya může trvat několik minut, nastavení můžeš zavřít)"
                out.postDelayed(this, 1_000)
            }
        }
        out.post(ticker)
        Thread {
            val n = try {
                HistoryBackfill.run(this, prefs, TuyaClient(prefs), restart)
            } catch (e: Exception) {
                -1
            }
            runOnUiThread {
                backfillRunning = false
                btn.isEnabled = true
                out.text = (if (n >= 0) "Doplněno nebo rozšířeno $n záznamů.\n\n" else "Doplnění selhalo.\n\n") + backfillStats()
            }
        }.start()
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
        sp.putString("stIcs", if (CalendarIcs.url(this).isEmpty()) "— nevyplněno (nepovinné)"
            else CalendarIcs.error(this)?.let { "❌ $it" }
                ?: "✅ funguje – dnes ${CalendarIcs.events(this, java.time.LocalDate.now()).size} událostí")
        sp.putString("stGoogle", if (prefs.googleKey.isEmpty()) "— nevyplněno (nepovinné)"
            else res(httpCode("https://weather.googleapis.com/v1/forecast/days:lookup?days=1&location.latitude=49&location.longitude=17&key=" +
                java.net.URLEncoder.encode(prefs.googleKey, "UTF-8"))))
        sp.apply()
    }

    private fun showKeyStatus() {
        val sp = getSharedPreferences("meteo", MODE_PRIVATE)
        listOf(R.id.st_tuya to "stTuya", R.id.st_mapy to "stMapy", R.id.st_owm to "stOwm", R.id.st_google to "stGoogle", R.id.st_ics to "stIcs")
            .forEach { (id, k) ->
                val st = sp.getString(k, "") ?: ""
                findViewById<TextView>(id).text = st.ifEmpty { "neověřeno – ověří se po „Uložit a otestovat“" }
            }
    }

    private fun backfillStats(): String {
        val st = HistoryBackfill.stats(this)
        if (st.isEmpty()) return "Doplnění historie zatím neproběhlo."
        val t = HistoryBackfill.lastRun(this)
        return "Poslední doplnění: " +
            java.text.SimpleDateFormat("d.M. HH:mm", java.util.Locale("cs", "CZ")).format(java.util.Date(t)) + "\n" +
            (st["_stav"]?.let { it + "\n" } ?: "") +
            st.entries.filter { it.key != "_stav" }.joinToString("\n") { "${it.key}: ${it.value}" }
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
        offerBrowser = false
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

    /** Po neúspěšném stažení tlačítko otevře stránku s APK v prohlížeči. */
    private var offerBrowser = false

    private fun onUpdateClick() {
        if (offerBrowser) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.RELEASES_PAGE)))
            } catch (e: Exception) {
                // bez prohlížeče
            }
            return
        }
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
                    offerBrowser = true
                    updateButton.text = "Stáhnout v prohlížeči"
                    updateButton.isEnabled = true
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.tv_overlay_status)?.text =
            if (TvSchedule.canDrawOverlays(this)) "Spouštění z pozadí: ✅ povoleno"
            else "Spouštění z pozadí: ⚠ není povoleno – bez něj se panel sám nespustí (Povolit spouštění)"
        findViewById<TextView>(R.id.battery_status)?.text =
            if (Battery.isUnrestricted(this)) "Sběr na pozadí: ✅ bez omezení baterie"
            else "Sběr na pozadí: ⚠ omezeno optimalizací baterie – v noci mohou chybět data. " +
                "Klepni níže a povol běh bez omezení. Na Samsungu zkontroluj i Péče o baterii → Uspané aplikace."
        if (::updateButton.isInitialized) refreshUpdateUi()
        if (openSection == null && menuStatus.isNotEmpty()) refreshMenu()
        refreshUpdateBanner()
        showSleepStatus()
    }
}
