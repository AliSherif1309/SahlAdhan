package com.sahl.adhan

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import java.time.ZonedDateTime
import java.time.chrono.HijrahDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var body: LinearLayout
    private val dp get() = resources.displayMetrics.density
    private fun c(id: Int) = getColor(id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Scheduler.ensureChannels(this)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (32 * dp).toInt())
        }
        setContentView(ScrollView(this).apply { addView(body) })
    }

    override fun onResume() { super.onResume(); render() }

    private fun refreshAll() { Scheduler.scheduleAll(this); Widgets.updateAll(this); render() }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
        background = GradientDrawable().apply { setColor(c(R.color.card)); cornerRadius = 18 * dp }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * dp).toInt() }
        body.addView(this)
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun spinner(items: List<String>, selected: Int, onPick: (Int) -> Unit) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(selected, false)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { if (pos != selected) onPick(pos) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun render() {
        body.removeAllViews()
        val ar = Names.isArabic(this)
        val now = ZonedDateTime.now(prefs.zone)
        val (nextI, nextT) = prefs.next(now)
        val loc = Locale.getDefault()

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding((16 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt())
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(c(R.color.emerald), c(R.color.emerald_dark))).apply { cornerRadius = 22 * dp }
        }
        body.addView(header)
        header.addView(text(getString(R.string.app_name), 15f, c(R.color.gold), true))
        header.addView(text(now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", loc)), 14f, 0xFFFFFFFF.toInt()))
        header.addView(text(HijrahDate.from(now).format(DateTimeFormatter.ofPattern("d MMMM yyyy", loc)), 13f, 0xFFCFE3DC.toInt()))
        header.addView(text("${getString(R.string.next)}: ${Names.of(this, nextI)}  ${Widgets.fmt(this, nextT)}", 22f, 0xFFFFFFFF.toInt(), true).apply { setPadding(0, (8 * dp).toInt(), 0, 0) })

        // Today's times
        val times = prefs.times(now.toLocalDate())
        val labels = listOf(R.string.fajr, R.string.sunrise, R.string.dhuhr, R.string.asr, R.string.maghrib, R.string.isha)
        val tCard = card()
        times.forEachIndexed { k, t ->
            val fardIndex = when (k) { 0 -> 0; 2 -> 1; 3 -> 2; 4 -> 3; 5 -> 4; else -> -1 }
            val isNext = fardIndex == nextI && nextT.toLocalDate() == now.toLocalDate()
            val row = LinearLayout(this).apply { setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt()) }
            val color = if (isNext) c(R.color.emerald) else c(R.color.text)
            row.addView(text(getString(labels[k]), 17f, color, isNext), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text(Widgets.fmt(this, t), 17f, color, true))
            tCard.addView(row)
        }

        // City
        val cityCard = card()
        cityCard.addView(text(getString(R.string.city), 14f, c(R.color.sub), true))
        cityCard.addView(spinner(PrayerCalc.cities.map { if (ar) it.ar else it.en }, prefs.cityIndex) { prefs.cityIndex = it; refreshAll() })

        // Alerts
        val aCard = card()
        aCard.addView(text(getString(R.string.alerts), 14f, c(R.color.sub), true))
        val modes = listOf(getString(R.string.alert_full), getString(R.string.alert_short), getString(R.string.alert_off))
        for (i in 0..4) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(text(Names.of(this, i), 16f, c(R.color.text)), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(spinner(modes, prefs.alert(i)) { prefs.setAlert(i, it); refreshAll() })
            aCard.addView(row)
        }
        // readiness
        val nm = getSystemService(NotificationManager::class.java)
        val notifOk = nm.areNotificationsEnabled()
        val exactOk = Scheduler.canExact(this)
        val status = when {
            !notifOk -> text(getString(R.string.need_notif), 14f, 0xFFC0392B.toInt(), true).apply { setOnClickListener { askNotif() } }
            !exactOk -> text(getString(R.string.need_alarm), 14f, 0xFFC0392B.toInt(), true).apply {
                setOnClickListener { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) } }
            else -> text(getString(R.string.ready), 14f, c(R.color.emerald), true)
        }
        status.setPadding(0, (10 * dp).toInt(), 0, (6 * dp).toInt())
        aCard.addView(status)
        aCard.addView(Button(this).apply { text = getString(R.string.test_full); setOnClickListener { test(ALERT_FULL) } })
        aCard.addView(Button(this).apply { text = getString(R.string.test_short); setOnClickListener { test(ALERT_SHORT) } })
        aCard.addView(text(getString(R.string.battery_tip), 12f, c(R.color.sub)).apply {
            setPadding(0, (6 * dp).toInt(), 0, 0)
            setOnClickListener { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        })

        // Calculation settings
        val sCard = card()
        sCard.addView(text(getString(R.string.settings), 14f, c(R.color.sub), true))
        sCard.addView(text(getString(R.string.method), 13f, c(R.color.text)))
        sCard.addView(spinner(PrayerCalc.methods.map { it.name }, prefs.methodIndex) { prefs.methodIndex = it; refreshAll() })
        sCard.addView(text(getString(R.string.asr_method), 13f, c(R.color.text)))
        sCard.addView(spinner(listOf(getString(R.string.asr_std), getString(R.string.asr_hanafi)), if (prefs.hanafi) 1 else 0) { prefs.hanafi = it == 1; refreshAll() })
        sCard.addView(text(getString(R.string.adjust), 13f, c(R.color.text)).apply { setPadding(0, (8 * dp).toInt(), 0, 0) })
        val range = (-15..15).toList()
        for (i in 0..4) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(text(Names.of(this, i), 15f, c(R.color.text)), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(spinner(range.map { if (it > 0) "+$it" else "$it" }, range.indexOf(prefs.adj(i))) { prefs.setAdj(i, range[it]); refreshAll() })
            sCard.addView(row)
        }
    }

    private fun askNotif() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        } else {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    private fun test(mode: Int) {
        if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) { askNotif(); return }
        Scheduler.scheduleTest(this, mode)
        Toast.makeText(this, getString(R.string.test_set), Toast.LENGTH_LONG).show()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) { refreshAll() }
}
