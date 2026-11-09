package cz.gramantik.meteowidget

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Jeden záznam ze stanice (hodnoty už v lidských jednotkách). */
data class Sample(
    val t: Long,
    val tin: Double?, val hin: Double?,
    val tout: Double?, val hout: Double?,
    val t1: Double?, val h1: Double?,
    val t2: Double?, val h2: Double?,
    val t3: Double?, val h3: Double?,
    val pres: Double?, val wind: Double?, val gust: Double?,
    val rain: Double?, val rrate: Double?,
    val uv: Double?, val light: Double?,
    val feels: Double?, val dew: Double?,
) {
    companion object {
        val COLS = listOf(
            "tin", "hin", "tout", "hout", "t1", "h1", "t2", "h2", "t3", "h3",
            "pres", "wind", "gust", "rain", "rrate", "uv", "light", "feels", "dew"
        )
        val LABELS = mapOf(
            "tin" to "Doma °C", "hin" to "Doma %", "tout" to "Venku °C", "hout" to "Venku %",
            "t1" to "Čidlo 1 °C", "h1" to "Čidlo 1 %", "t2" to "Čidlo 2 °C", "h2" to "Čidlo 2 %",
            "t3" to "Čidlo 3 °C", "h3" to "Čidlo 3 %", "pres" to "Tlak hPa", "wind" to "Vítr km/h",
            "gust" to "Nárazy km/h", "rain" to "Srážky dnes mm", "rrate" to "Intenzita mm/h",
            "uv" to "UV", "light" to "Světlo klux", "feels" to "Pocitově °C", "dew" to "Rosný bod °C",
        )

        fun from(t: Long, w: Weather) = Sample(
            t,
            w.tempIn, w.humIn?.toDouble(), w.tempOut, w.humOut?.toDouble(),
            w.tempSensor(1), w.humSensor(1)?.toDouble(),
            w.tempSensor(2), w.humSensor(2)?.toDouble(),
            w.tempSensor(3), w.humSensor(3)?.toDouble(),
            w.pressure, w.windAvg, w.windGust, w.rainDay, w.rainRate,
            w.uv, w.lightKlux, w.feelsLike, w.dewPoint,
        )
    }

    fun get(col: String): Double? = when (col) {
        "tin" -> tin; "hin" -> hin; "tout" -> tout; "hout" -> hout
        "t1" -> t1; "h1" -> h1; "t2" -> t2; "h2" -> h2; "t3" -> t3; "h3" -> h3
        "pres" -> pres; "wind" -> wind; "gust" -> gust; "rain" -> rain; "rrate" -> rrate
        "uv" -> uv; "light" -> light; "feels" -> feels; "dew" -> dew
        else -> null
    }
}

/** Denní souhrn ze stanice. */
data class DayStat(
    val date: LocalDate,
    val toutMin: Double?, val toutMax: Double?,
    val tinMin: Double?, val tinMax: Double?,
    val rain: Double?, val gustMax: Double?,
)

data class ForecastLogRow(val date: LocalDate, val source: String, val tMax: Double, val tMin: Double, val precip: Double)

class MeteoDb private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "meteo.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE samples (t INTEGER PRIMARY KEY, " +
                Sample.COLS.joinToString(", ") { "$it REAL" } + ")"
        )
        db.execSQL(
            "CREATE TABLE forecast_log (date TEXT, source TEXT, tmax REAL, tmin REAL, precip REAL, " +
                "PRIMARY KEY(date, source))"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // ---------- zápis ----------
    fun insert(s: Sample, replace: Boolean = true) {
        val cv = ContentValues()
        cv.put("t", s.t)
        for (c in Sample.COLS) s.get(c)?.let { cv.put(c, it) }
        writableDatabase.insertWithOnConflict(
            "samples", null, cv,
            if (replace) SQLiteDatabase.CONFLICT_REPLACE else SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    /**
     * Sloučí záznam s nejbližším existujícím (±tol): doplní jen chybějící hodnoty.
     * Když v okolí žádný není, vloží nový. Vrací true, pokud se něco změnilo.
     */
    fun mergeNear(s: Sample, tol: Long): Boolean {
        val existing = readableDatabase.rawQuery(
            "SELECT t FROM samples WHERE t BETWEEN ? AND ? ORDER BY ABS(t - ?) LIMIT 1",
            arrayOf((s.t - tol).toString(), (s.t + tol).toString(), s.t.toString())
        ).use { if (it.moveToFirst()) it.getLong(0) else null }
        if (existing == null) {
            insert(s, replace = false)
            return true
        }
        val cols = Sample.COLS.filter { s.get(it) != null }
        if (cols.isEmpty()) return false
        val sql = "UPDATE samples SET " + cols.joinToString(", ") { "$it = COALESCE($it, ?)" } + " WHERE t = ?"
        val args: Array<Any> = (cols.map { s.get(it)!! as Any } + existing).toTypedArray()
        writableDatabase.execSQL(sql, args)
        return true
    }

    /** Je v okolí času t (±tol) už nějaký záznam? */
    fun hasNear(t: Long, tol: Long): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM samples WHERE t BETWEEN ? AND ? LIMIT 1",
            arrayOf((t - tol).toString(), (t + tol).toString())
        ).use { it.moveToFirst() }

    fun logForecast(row: ForecastLogRow) {
        val cv = ContentValues().apply {
            put("date", row.date.toString()); put("source", row.source)
            put("tmax", row.tMax); put("tmin", row.tMin); put("precip", row.precip)
        }
        writableDatabase.insertWithOnConflict("forecast_log", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---------- čtení ----------
    private fun Cursor.d(i: Int): Double? = if (isNull(i)) null else getDouble(i)

    private fun Cursor.sample(): Sample = Sample(
        getLong(0), d(1), d(2), d(3), d(4), d(5), d(6), d(7), d(8), d(9), d(10),
        d(11), d(12), d(13), d(14), d(15), d(16), d(17), d(18), d(19),
    )

    /** Záznamy v rozsahu, zředěné průměrováním na nejvýš [maxPoints] bodů. */
    fun range(from: Long, to: Long, maxPoints: Int = 700): List<Sample> {
        val raw = ArrayList<Sample>()
        readableDatabase.rawQuery(
            "SELECT t, ${Sample.COLS.joinToString(",")} FROM samples WHERE t BETWEEN ? AND ? ORDER BY t",
            arrayOf(from.toString(), to.toString())
        ).use { c -> while (c.moveToNext()) raw.add(c.sample()) }
        if (raw.size <= maxPoints) return raw
        val bucket = (to - from) / maxPoints
        return raw.groupBy { (it.t - from) / bucket }.values.map { g ->
            fun avg(col: String): Double? {
                val v = g.mapNotNull { it.get(col) }
                return if (v.isEmpty()) null else v.average()
            }
            fun mx(col: String): Double? = g.mapNotNull { it.get(col) }.maxOrNull()
            Sample(
                g[g.size / 2].t, avg("tin"), avg("hin"), avg("tout"), avg("hout"),
                avg("t1"), avg("h1"), avg("t2"), avg("h2"), avg("t3"), avg("h3"),
                avg("pres"), avg("wind"), mx("gust"), mx("rain"), mx("rrate"),
                avg("uv"), avg("light"), avg("feels"), avg("dew"),
            )
        }
    }

    fun last(): Sample? = readableDatabase.rawQuery(
        "SELECT t, ${Sample.COLS.joinToString(",")} FROM samples ORDER BY t DESC LIMIT 1", null
    ).use { if (it.moveToFirst()) it.sample() else null }

    fun first(): Long? = readableDatabase.rawQuery("SELECT MIN(t) FROM samples", null)
        .use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM samples", null)
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Minimum / maximum sloupce v rozsahu i s časem. */
    fun extreme(col: String, from: Long, to: Long, max: Boolean): Pair<Long, Double>? =
        readableDatabase.rawQuery(
            "SELECT t, $col FROM samples WHERE t BETWEEN ? AND ? AND $col IS NOT NULL " +
                "ORDER BY $col ${if (max) "DESC" else "ASC"} LIMIT 1",
            arrayOf(from.toString(), to.toString())
        ).use { if (it.moveToFirst()) it.getLong(0) to it.getDouble(1) else null }

    /** Denní souhrny (podle místního času). */
    fun days(from: LocalDate, to: LocalDate): List<DayStat> {
        val z = ZoneId.systemDefault()
        val start = from.atStartOfDay(z).toInstant().toEpochMilli()
        val end = to.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli() - 1
        val res = LinkedHashMap<LocalDate, MutableList<Sample>>()
        readableDatabase.rawQuery(
            "SELECT t, ${Sample.COLS.joinToString(",")} FROM samples WHERE t BETWEEN ? AND ? ORDER BY t",
            arrayOf(start.toString(), end.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val s = c.sample()
                val d = Instant.ofEpochMilli(s.t).atZone(z).toLocalDate()
                res.getOrPut(d) { ArrayList() }.add(s)
            }
        }
        return res.map { (d, list) ->
            DayStat(
                d,
                list.mapNotNull { it.tout }.minOrNull(), list.mapNotNull { it.tout }.maxOrNull(),
                list.mapNotNull { it.tin }.minOrNull(), list.mapNotNull { it.tin }.maxOrNull(),
                // srážky stanice = "dnešní" součet, který o půlnoci padá na nulu -> maximum za den
                list.mapNotNull { it.rain }.maxOrNull(),
                list.mapNotNull { it.gust }.maxOrNull(),
            )
        }
    }

    fun forecastLog(from: LocalDate, to: LocalDate): List<ForecastLogRow> {
        val out = ArrayList<ForecastLogRow>()
        readableDatabase.rawQuery(
            "SELECT date, source, tmax, tmin, precip FROM forecast_log WHERE date BETWEEN ? AND ? ORDER BY date",
            arrayOf(from.toString(), to.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(ForecastLogRow(LocalDate.parse(c.getString(0)), c.getString(1), c.getDouble(2), c.getDouble(3), c.getDouble(4)))
            }
        }
        return out
    }

    /** CSV všech sloupců v rozsahu (bez ředění). */
    fun csv(from: Long, to: Long): String {
        val z = ZoneId.systemDefault()
        val sb = StringBuilder()
        sb.append("cas;").append(Sample.COLS.joinToString(";") { Sample.LABELS[it] ?: it }).append("\n")
        readableDatabase.rawQuery(
            "SELECT t, ${Sample.COLS.joinToString(",")} FROM samples WHERE t BETWEEN ? AND ? ORDER BY t",
            arrayOf(from.toString(), to.toString())
        ).use { c ->
            while (c.moveToNext()) {
                sb.append(Instant.ofEpochMilli(c.getLong(0)).atZone(z).toLocalDateTime().toString().replace('T', ' '))
                for (i in 1..Sample.COLS.size) {
                    sb.append(';')
                    if (!c.isNull(i)) sb.append(c.getDouble(i).toString().replace('.', ','))
                }
                sb.append('\n')
            }
        }
        return sb.toString()
    }

    companion object {
        @Volatile private var inst: MeteoDb? = null
        fun get(ctx: Context): MeteoDb =
            inst ?: synchronized(this) { inst ?: MeteoDb(ctx).also { inst = it } }
    }
}
