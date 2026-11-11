package cz.gramantik.meteowidget

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import cz.gramantik.meteowidget.ui.C
import cz.gramantik.meteowidget.ui.WRAP
import cz.gramantik.meteowidget.ui.dp
import cz.gramantik.meteowidget.ui.hbox
import cz.gramantik.meteowidget.ui.label
import cz.gramantik.meteowidget.ui.lp
import cz.gramantik.meteowidget.ui.rounded
import cz.gramantik.meteowidget.ui.text
import cz.gramantik.meteowidget.ui.vbox
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/** Nastavení panelu s pozdravem a upozorněními (karta Dnes na TV). */
class MorningPrefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("meteo", Context.MODE_PRIVATE)

    var names: String
        get() = sp.getString("tmNames", "Beátko a Petře")!!
        set(v) = sp.edit().putString("tmNames", v.trim()).apply()
    var frostBelow: Float
        get() = sp.getFloat("tmFrost", 0f)
        set(v) = sp.edit().putFloat("tmFrost", v).apply()
    var windFrom: Float
        get() = sp.getFloat("tmWind", 50f)
        set(v) = sp.edit().putFloat("tmWind", v).apply()
    var rainMm: Float
        get() = sp.getFloat("tmRainMm", 0.5f)
        set(v) = sp.edit().putFloat("tmRainMm", v).apply()
    var rainPct: Float
        get() = sp.getFloat("tmRainPct", 50f)
        set(v) = sp.edit().putFloat("tmRainPct", v).apply()

    /** Pozdrav podle denní doby. */
    fun greeting(now: LocalTime = LocalTime.now()): Pair<String, String> {
        val m = now.hour * 60 + now.minute
        return when {
            m < 5 * 60 -> "Dobrou noc" to "🌙"
            m < 9 * 60 -> "Dobré ráno" to "☀"
            m < 11 * 60 + 30 -> "Hezké dopoledne" to "🌤"
            m < 13 * 60 -> "Hezký den" to "☀"
            m < 18 * 60 -> "Hezké odpoledne" to "🌤"
            m < 22 * 60 -> "Dobrý večer" to "🌙"
            else -> "Dobrou noc" to "🌙"
        }
    }
}

/** Jedno upozornění pro ranní panel. */
data class MorningWarn(val title: String, val sub: String, val color: Int)

object TvMorning {
    private val cz = Locale("cs", "CZ")
    private fun deg(v: Double) = "${v.roundToInt()}°"
    private fun n1(v: Double) = String.format(cz, "%.1f", v)

    /** Den, ke kterému se vztahuje kalendář a upozornění (od 20:00 zítřek). */
    fun targetDay(now: LocalDateTime = LocalDateTime.now()): LocalDate =
        if (now.hour >= 20) now.toLocalDate().plusDays(1) else now.toLocalDate()

    data class Result(val warns: List<MorningWarn>, val icons: List<Int>, val pulse: Set<Int> = emptySet())

    fun warnings(ctx: Context, day: LocalDate): Result {
        val mp = MorningPrefs(ctx)
        val f = ForecastFull.load(ctx)
        val now = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0)
        val hours = f?.hours?.filter { it.time.toLocalDate() == day && !it.time.isBefore(now) } ?: emptyList()
        val d = f?.days?.firstOrNull { it.date == day }
        val out = ArrayList<MorningWarn>()
        val icons = LinkedHashSet<Int>()
        val tomorrow = day != LocalDate.now()
        val pre = if (tomorrow) "Zítra " else ""
        fun hh(t: LocalDateTime) = "${t.hour}:00"

        // mráz – nejnižší teplota (ráno)
        val coldest = hours.minByOrNull { it.temp }
        val tMin = coldest?.temp ?: d?.tMin
        if (tMin != null && !tMin.isNaN() && tMin < mp.frostBelow) {
            val whenTxt = coldest?.let { if (it.time.hour <= 10) "ráno" else "kolem ${hh(it.time)}" } ?: "ráno"
            out += MorningWarn("${pre}${whenTxt} mráz až ${deg(tMin)}".replaceFirstChar { it.uppercase() },
                "škrabka na auto, teplé oblečení", 0xFF8CC4FF.toInt())
            icons += R.drawable.warn_frost
        }
        // náledí – mrznoucí déšť / mrholení
        val freeze = setOf(56, 57, 66, 67)
        val freezing = hours.any { it.code in freeze } || (hours.isEmpty() && d != null && d.code in freeze)
        // sníh
        val snow = if (hours.isNotEmpty()) hours.sumOf { it.snow } else d?.snow ?: 0.0
        if (snow >= 0.1) {
            out += MorningWarn("${pre}sněžení, až ${n1(snow)} cm".replaceFirstChar { it.uppercase() },
                "počítej s delší cestou", 0xFFE3F2FF.toInt())
            icons += R.drawable.warn_snow
        }
        // déšť
        val rain = if (hours.isNotEmpty()) hours.sumOf { it.precip } else d?.precip ?: 0.0
        val prob = if (hours.isNotEmpty()) hours.maxOf { it.prob } else d?.prob ?: 0
        if ((rain >= mp.rainMm || prob >= mp.rainPct) && rain - snow * 0.7 > 0.05) {
            val wettest = hours.maxByOrNull { it.precip }?.takeIf { it.precip > 0 }
            out += MorningWarn("${pre}déšť ${n1(rain)} mm".replaceFirstChar { it.uppercase() },
                "~ $prob %" + (wettest?.let { " · nejvíc kolem ${hh(it.time)}" } ?: ""), C.RAIN)
            icons += R.drawable.warn_rain
        }
        // vítr
        val gust = if (hours.isNotEmpty()) hours.maxOf { it.gust } else d?.gustMax ?: 0.0
        if (gust >= mp.windFrom) {
            out += MorningWarn("${pre}silný vítr".replaceFirstChar { it.uppercase() },
                "nárazy až ${gust.roundToInt()} km/h", C.WIND)
            icons += R.drawable.warn_wind
        }
        // bouřky
        val storm = hours.maxByOrNull { it.storm }?.takeIf { it.storm > 0 }
        if (storm != null) {
            out += MorningWarn((if (storm.storm >= 2) "${pre}vysoké riziko bouřek" else "${pre}možné bouřky").replaceFirstChar { it.uppercase() },
                "kolem ${hh(storm.time)}", 0xFFFFB74D.toInt())
        }
        // výstrahy ČHMÚ pro daný den
        val zone = ZoneId.systemDefault()
        val ds = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val de = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val alerts = Alerts.load(ctx).filter { it.onset < de && it.expires > ds }
        alerts.take(3).forEach { a ->
            val tf = java.text.SimpleDateFormat("EEE HH:mm", cz)
            out += MorningWarn("ČHMÚ: ${a.event}", "${a.level} · ${tf.format(java.util.Date(a.onset))} – ${tf.format(java.util.Date(a.expires))}", a.color)
        }
        val iceAlert = alerts.any { val e = it.event.lowercase(cz); "náledí" in e || "ledovk" in e }
        if (freezing || iceAlert) {
            if (freezing && !iceAlert) out.add(0, MorningWarn("${pre}hrozí náledí".replaceFirstChar { it.uppercase() },
                "mrznoucí déšť – opatrně na chodnících i silnici", 0xFFFFD54F.toInt()))
            icons += R.drawable.warn_ice
        }
        // ikonky odpovídající výstraze ČHMÚ pomalu pulzují (a ukážou se, i kdyby je předpověď nehlásila)
        val pulse = HashSet<Int>()
        alerts.forEach { al ->
            val e = (al.event + " " + al.description).lowercase(cz)
            if ("vítr" in e || "větr" in e || "vichřic" in e) pulse += R.drawable.warn_wind
            if ("náledí" in e || "ledovk" in e) pulse += R.drawable.warn_ice
            if ("sníh" in e || "sněh" in e || "sněž" in e || "závěj" in e) pulse += R.drawable.warn_snow
            if ("mráz" in e || "nízk" in e && "teplot" in e) pulse += R.drawable.warn_frost
            if ("déšť" in e || "dešť" in e || "srážk" in e || "povod" in e) pulse += R.drawable.warn_rain
        }
        icons += pulse
        return Result(out, icons.toList(), pulse)
    }

    /**
     * Ranní panel (místo dlaždic a panelu Dnes).
     * @param width šířka v dp, [height] výška v dp
     */
    fun build(ctx: Context, height: Int, forDay: LocalDate? = null): View {
        val c = ctx
        val mp = MorningPrefs(c)
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val day = forDay ?: targetDay(now)
        val tomorrow = day != today
        val f = ForecastFull.load(c)
        val dayF = f?.days?.firstOrNull { it.date == day }

        val box = c.vbox().apply {
            background = rounded(C.CARD, c.dp(10).toFloat(), c.dp(1), C.LINE) // stejně jako ostatní panely
            setPadding(c.dp(12), c.dp(7), c.dp(12), c.dp(7))
        }
        val dateFmt = java.time.format.DateTimeFormatter.ofPattern("EEEE d. MMMM", cz)
        val title = if (forDay != null) {
            (if (forDay == today.plusDays(1)) "Zítra, " else "") + dateFmt.format(forDay)
        } else {
            val (greet, emoji) = mp.greeting(now.toLocalTime())
            if (mp.names.isBlank()) "$greet $emoji" else "$greet, ${mp.names} $emoji"
        }
        // dlouhý pozdrav se zmenší, aby se vešel celý na jeden řádek
        box.addView(c.text(title, 17f, C.ACCENT, bold = true).apply {
            maxLines = 1
            ellipsize = null
            setAutoSizeTextTypeUniformWithConfiguration(9, 17, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }, c.lp(LinearLayout.LayoutParams.MATCH_PARENT, c.dp(24)))
        val subDay = forDay ?: today
        val sub = buildString {
            if (forDay == null) append(dateFmt.format(today)).append(" · ")
            append("svátek má ").append(NameDays.forDate(c, subDay))
            dayF?.let {
                append(if (tomorrow && forDay == null) " · zítra " else " · ")
                append("${deg(it.tMax)} / ${deg(it.tMin)}, ")
                append(ForecastFull.describe(it.code).lowercase(cz))
            }
        }
        box.addView(c.text(sub, 9.5f, C.DIM).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, c.lp(top = 2))

        val cols = c.hbox(Gravity.TOP)
        // ---------- kalendář ----------
        val cal = c.vbox()
        cal.addView(c.label(if (tomorrow) "Zítra v kalendáři" else "Dnes v kalendáři", C.BARO).apply { textSize = 8.5f })
        val list = c.vbox()
        val evs = CalendarIcs.events(c, day)
        if (CalendarIcs.url(c).isEmpty()) list.addView(c.text("Kalendář není nastavený", 10f, C.DIM))
        else {
            fun addEvents(list2: List<CalEvent>) = list2.forEach { e ->
                val r = c.hbox(Gravity.TOP)
                r.addView(c.text(e.time.ifEmpty { "celý den" }, 10.5f, C.ACCENT, bold = true, condensed = true), c.lp(c.dp(44), WRAP))
                r.addView(c.text(e.summary, 10.5f, C.WHITE).apply { maxLines = 2 }, c.lp(0, WRAP, 1f))
                list.addView(r, c.lp(bottom = 4))
            }
            if (evs.isNotEmpty()) addEvents(evs)
            else {
                list.addView(c.text(if (tomorrow) "Zítra nic v kalendáři 🎉" else "Dnes nic v kalendáři 🎉", 11f, C.WHITE), c.lp(bottom = 4))
                // dnes prázdno -> ukázat zítřek
                if (!tomorrow) {
                    val tmr = CalendarIcs.events(c, day.plusDays(1))
                    if (tmr.isNotEmpty()) {
                        list.addView(c.label("Zítra:", C.BARO).apply { textSize = 8.5f }, c.lp(top = 2, bottom = 2))
                        addEvents(tmr)
                    }
                }
            }
        }
        cal.addView(AutoScroll(c).apply { addView(list) }, c.lp(h = 0, weight = 1f, top = 3))
        cols.addView(cal, c.lp(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f, end = 10))
        cols.addView(View(c).apply { setBackgroundColor(C.LINE) }, c.lp(1, LinearLayout.LayoutParams.MATCH_PARENT, end = 10))

        // ---------- na co si dát pozor ----------
        val warnCol = c.vbox()
        warnCol.addView(c.label("Na co si dát pozor", 0xFFFFB74D.toInt()).apply { textSize = 8.5f })
        val res = warnings(c, day)
        val wl = c.vbox()
        if (res.warns.isEmpty()) {
            wl.addView(c.text(if (tomorrow) "Zítra žádná varování, hezký den! 🙂" else "Dnes žádná varování, hezký den! 🙂", 11f, C.WHITE))
        } else res.warns.forEach { w ->
            val card = c.vbox().apply {
                background = rounded(0xFF0C0F13.toInt(), c.dp(6).toFloat(), c.dp(1), (w.color and 0x00FFFFFF) or 0x88000000.toInt())
                setPadding(c.dp(6), c.dp(3), c.dp(6), c.dp(3))
                // dlouhý text roluje tam a zpět
                addView(c.text(w.title, 10.5f, w.color, bold = true).bounce(), c.lp(LinearLayout.LayoutParams.MATCH_PARENT, WRAP))
                if (w.sub.isNotEmpty()) addView(c.text(w.sub, 8.5f, C.DIM).bounce(), c.lp(LinearLayout.LayoutParams.MATCH_PARENT, WRAP))
            }
            wl.addView(card, c.lp(bottom = 3))
        }
        warnCol.addView(AutoScroll(c).apply { addView(wl) }, c.lp(h = 0, weight = 1f, top = 3))
        if (res.icons.isNotEmpty()) {
            val ir = c.hbox()
            res.icons.forEach { id ->
                val iv = ImageView(c).apply { setImageResource(id) }
                if (id in res.pulse) {
                    // pomalé pulzování – výstraha ČHMÚ
                    android.animation.ObjectAnimator.ofPropertyValuesHolder(iv,
                        android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.25f),
                        android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.25f),
                        android.animation.PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.55f),
                    ).apply {
                        duration = 1100
                        repeatCount = android.animation.ValueAnimator.INFINITE
                        repeatMode = android.animation.ValueAnimator.REVERSE
                        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                        start()
                    }
                }
                ir.addView(iv, c.lp(c.dp(26), c.dp(26), end = 8))
            }
            warnCol.addView(ir, c.lp(top = 2))
        }
        cols.addView(warnCol, c.lp(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.15f))
        box.addView(cols, c.lp(h = 0, weight = 1f, top = 5))
        box.layoutParams = c.lp(0, if (height > 0) c.dp(height) else LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        return box
    }
}

/**
 * Svislé plynulé posouvání nahoru a dolů, když se obsah nevejde.
 * Poloha se počítá z času, takže překreslení panelu (každou minutu) plynule navazuje.
 */
class AutoScroll(ctx: Context) : ScrollView(ctx) {
    init {
        isVerticalScrollBarEnabled = false
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        overScrollMode = OVER_SCROLL_NEVER
    }

    private val speed = 14f * resources.displayMetrics.density // px/s
    private val holdMs = 3500L

    private val step = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            val child = getChildAt(0)
            val range = (child?.height ?: 0) - height
            if (range > 0) {
                val travel = (range / speed * 1000).toLong().coerceAtLeast(800)
                val period = 2 * (travel + holdMs)
                val ph = System.currentTimeMillis() % period
                val y = when {
                    ph < holdMs -> 0f
                    ph < holdMs + travel -> ease((ph - holdMs).toFloat() / travel) * range
                    ph < 2 * holdMs + travel -> range.toFloat()
                    else -> (1 - ease((ph - 2 * holdMs - travel).toFloat() / travel)) * range
                }
                scrollTo(0, y.toInt())
                postDelayed(this, 33)
            } else postDelayed(this, 1000)
        }
    }

    private fun ease(x: Float) = ((1 - cos(x.coerceIn(0f, 1f) * PI)) / 2).toFloat()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(step)
        post(step)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(step)
        super.onDetachedFromWindow()
    }
}

/** Text, který střídá několik informací s plynulým prolínáním (bez rolování). */
class FadeCycler(ctx: Context, private val items: List<String>) : TextView(ctx) {
    private var idx = ((System.currentTimeMillis() / 4500) % items.size.coerceAtLeast(1)).toInt()
    private val step = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || items.size < 2) return
            animate().alpha(0f).setDuration(450).withEndAction {
                idx = (idx + 1) % items.size
                text = items[idx]
                animate().alpha(1f).setDuration(450).start()
            }.start()
            postDelayed(this, 4500)
        }
    }

    init {
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        text = items.getOrNull(idx) ?: ""
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(step)
        postDelayed(step, 4500)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(step)
        animate().cancel()
        super.onDetachedFromWindow()
    }
}


/**
 * Text, který se nevejde, pomalu roluje tam a zpět (s pauzou na obou koncích). Kratší stojí.
 * Poloha se počítá z času, takže překreslení panelu plynule navazuje.
 */
fun TextView.bounce(): TextView {
    // pozor: setSingleLine()/vodorovné rolování se zarovnáním na střed text „odsune“ mimo dlaždici,
    // proto se rolování zapíná jen tehdy, když se text opravdu nevejde
    maxLines = 1
    ellipsize = null
    setHorizontallyScrolling(false)
    val speed = 22f * resources.displayMetrics.density // px/s
    val hold = 1500L
    val baseGravity = gravity
    val scrollGravity = (gravity and android.view.Gravity.VERTICAL_GRAVITY_MASK) or android.view.Gravity.START
    var scrolling = false
    val step = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            val avail = width - totalPaddingLeft - totalPaddingRight
            val over = if (avail <= 0) 0f else paint.measureText(text.toString()) - avail
            if (over > 1f) {
                if (!scrolling) {
                    scrolling = true
                    setHorizontallyScrolling(true)
                    gravity = scrollGravity
                }
                val travel = (over / speed * 1000).toLong().coerceAtLeast(400)
                val period = 2 * (travel + hold)
                val ph = System.currentTimeMillis() % period
                val x = when {
                    ph < hold -> 0f
                    ph < hold + travel -> (ph - hold).toFloat() / travel * over
                    ph < 2 * hold + travel -> over
                    else -> (1 - (ph - 2 * hold - travel).toFloat() / travel) * over
                }
                scrollTo(x.toInt(), 0)
                postDelayed(this, 40)
            } else {
                if (scrolling) {
                    scrolling = false
                    scrollTo(0, 0)
                    setHorizontallyScrolling(false)
                    gravity = baseGravity
                }
                postDelayed(this, if (avail <= 0) 200 else 1000)
            }
        }
    }
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { removeCallbacks(step); post(step) }
        override fun onViewDetachedFromWindow(v: View) { removeCallbacks(step) }
    })
    return this
}


/** Obal, který svůj obsah zmenší (rovnoměrně), když se nevejde do přidělené výšky. */
class FitBox(ctx: Context) : android.widget.FrameLayout(ctx) {
    init { clipChildren = true }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val child = getChildAt(0)
        if (child != null) {
            child.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val scale = if (h > 0 && child.measuredHeight > h) h.toFloat() / child.measuredHeight else 1f
            if (scale < 1f) {
                // změřit širší, aby po zmenšení vyplnil celou šířku
                child.measure(MeasureSpec.makeMeasureSpec((w / scale).toInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            }
            child.pivotX = 0f
            child.pivotY = 0f
            child.scaleX = scale
            child.scaleY = scale
        }
        val mh = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) (child?.measuredHeight ?: 0) else h
        setMeasuredDimension(w, mh)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        getChildAt(0)?.let { it.layout(0, 0, it.measuredWidth, it.measuredHeight) }
    }
}
