package cz.gramantik.meteowidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

/** Výběr kanálu pro malý widget 2×1 (i při přenastavení dlouhým stiskem). */
class ChannelConfigActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val p = Prefs(this)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Který kanál má widget ukazovat?"
            textSize = 18f
        })

        val group = RadioGroup(this)
        val w = p.lastData?.let { Weather(it, p.spec) }
        val current = p.miniChannel(widgetId)
        Channel.entries.forEachIndexed { i, ch ->
            val now = w?.temp(ch.tempCode)
            group.addView(RadioButton(this).apply {
                id = 1000 + i
                textSize = 16f
                text = if (now != null) "${p.channelName(ch)}   (${Weather.t(now)})" else "${p.channelName(ch)}   (nepřipojeno)"
                isChecked = ch == current
            })
        }
        root.addView(group)

        root.addView(Button(this).apply {
            text = "Hotovo"
            setOnClickListener {
                val idx = group.checkedRadioButtonId - 1000
                val ch = Channel.entries.getOrNull(idx) ?: Channel.OUT
                p.setMiniChannel(widgetId, ch)
                WidgetRenderer.updateAll(this@ChannelConfigActivity)
                FetchWorker.schedulePeriodic(this@ChannelConfigActivity)
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                finish()
            }
        })
        setContentView(root)
    }
}
