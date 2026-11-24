package com.sahl.adhan

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import java.time.ZonedDateTime
import java.time.chrono.HijrahDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private val dp get() = resources.displayMetrics.density
    private fun c(id: Int) = getColor(id)

    private var preview: MediaPlayer? = null
    private var volDownConsumed = false
    private val font by lazy { try { resources.getFont(R.font.thmanyah) } catch (e: Exception) { null } }

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(newBase.localized()) }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN && AdhanService.handleVolumeDown(this)) {
                volDownConsumed = true
                return true
            }
            if (event.action == KeyEvent.ACTION_UP && volDownConsumed) {
                volDownConsumed = false
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() { super.onPause(); stopPreview() }

    private fun stopPreview() { preview?.release(); preview = null }

    private fun play(key: String) {
        stopPreview()
        preview = try {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                setDataSource(this@MainActivity, Sounds.uri(this@MainActivity, key))
                setOnCompletionListener { stopPreview() }
                prepare(); start()
            }
        } catch (e: Exception) { null }
    }

    private fun soundPicker(parent: LinearLayout, title: Int, list: List<Sounds.Sound>, current: String,
                            customKey: String, pickCode: Int, save: (String) -> Unit) {
        parent.addView(text(getString(title), 13f, c(R.color.text)).apply { setPadding(0, (8 * dp).toInt(), 0, 0) })
        val avail = Sounds.available(this, list, customKey)
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val sel = avail.indexOfFirst { it.key == current }.coerceAtLeast(0)
        row.addView(spinner(avail.map { getString(it.label) }, sel) { save(avail[it].key); refreshAll() },
            LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply { text = getString(R.string.preview); setOnClickListener { play(avail[sel].key) } })
        row.addView(Button(this).apply { text = getString(R.string.stop); setOnClickListener { stopPreview() } })
        parent.addView(row)
        parent.addView(Button(this).apply {
            text = getString(R.string.pick_file)
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*"), pickCode)
            }
        })
    }

    @Deprecated("Activity result")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data ?: return
        if (res != RESULT_OK || req !in 10..13) return
        val key = when (req) {
            10 -> "custom_full"
            11 -> "custom_short"
            12 -> "custom_iqama"
            else -> "custom_pre_adhan"
        }
        try {
            contentResolver.openInputStream(uri)!!.use { inp -> Sounds.customFile(this, key).outputStream().use { inp.copyTo(it) } }
            when (req) {
                10 -> prefs.fullSound = key
                11 -> prefs.shortSound = key
                12 -> prefs.iqamaSound = key
                else -> prefs.preAdhanSound = key
            }
            Toast.makeText(this, getString(R.string.custom_saved), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(this, "✗", Toast.LENGTH_SHORT).show() }
        refreshAll()
    }

    // --- Location: GPS and search ---
    private fun useGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 20)
            return
        }
        Toast.makeText(this, getString(R.string.locating), Toast.LENGTH_SHORT).show()
        val lm = getSystemService(android.location.LocationManager::class.java)
        val got: (android.location.Location?) -> Unit = { loc ->
            if (loc == null) runOnUiThread { Toast.makeText(this, getString(R.string.no_location), Toast.LENGTH_LONG).show() }
            else saveLocation(loc.latitude, loc.longitude, null)
        }
        try {
            val provider = when {
                Build.VERSION.SDK_INT >= 31 && lm.hasProvider(android.location.LocationManager.FUSED_PROVIDER) -> android.location.LocationManager.FUSED_PROVIDER
                lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) -> android.location.LocationManager.GPS_PROVIDER
                else -> android.location.LocationManager.NETWORK_PROVIDER
            }
            if (Build.VERSION.SDK_INT >= 30) lm.getCurrentLocation(provider, null, mainExecutor) { got(it) }
            else lm.requestSingleUpdate(provider, { got(it) }, mainLooper)
        } catch (e: SecurityException) { got(null) } catch (e: Exception) { got(null) }
    }

    /** Name the place (reverse geocode), then save it as "my location" */
    private fun saveLocation(lat: Double, lng: Double, name: String?) {
        Thread {
            val label = name ?: try {
                @Suppress("DEPRECATION")
                android.location.Geocoder(this, resources.configuration.locales[0]).getFromLocation(lat, lng, 1)
                    ?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
            } catch (e: Exception) { null } ?: String.format(java.util.Locale.ENGLISH, "%.3f, %.3f", lat, lng)
            prefs.setCustom("📍 $label", lat, lng, java.time.ZoneId.systemDefault().id)
            runOnUiThread { Official.syncIfNeeded(this, force = true) { runOnUiThread { refreshAll() } }; refreshAll() }
        }.start()
    }

    private fun searchCity() {
        val input = EditText(this).apply { hint = getString(R.string.search_hint) }
        android.app.AlertDialog.Builder(this).setTitle(getString(R.string.search_city)).setView(input)
            .setPositiveButton("🔍") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isNotEmpty()) Thread {
                    val res = try {
                        @Suppress("DEPRECATION")
                        android.location.Geocoder(this, resources.configuration.locales[0]).getFromLocationName(q, 8) ?: emptyList()
                    } catch (e: Exception) { emptyList() }
                    runOnUiThread {
                        if (res.isEmpty()) Toast.makeText(this, getString(R.string.no_results), Toast.LENGTH_LONG).show()
                        else {
                            val labels = res.map { x -> listOfNotNull(x.locality ?: x.featureName, x.adminArea, x.countryName).distinct().joinToString("، ") }
                            android.app.AlertDialog.Builder(this).setItems(labels.toTypedArray()) { _, i ->
                                prefs.setOpt("auto_location", false)
                                saveLocation(res[i].latitude, res[i].longitude, res[i].locality ?: res[i].featureName ?: labels[i])
                            }.show()
                        }
                    }
                }.start()
            }.setNegativeButton("✕", null).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        SahlCore.catchCrashes(this)
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Scheduler.ensureChannels(this)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (32 * dp).toInt())
        }
        scroll = ScrollView(this).apply { addView(body) }
        setContentView(scroll)
    }

    private var newer: Updater.Release? = null
    private var checked = false
    private var updateMsg: String? = null

    override fun onResume() {
        super.onResume(); Scheduler.scheduleAll(this); Widgets.updateAll(this); render()
        Official.syncIfNeeded(this) { ok -> if (ok) runOnUiThread { Widgets.updateAll(this); render() } }
        AutoLocation.syncPassiveRegistration(this)
        AutoLocation.checkIfNeeded(this, onUpdated = { runOnUiThread { Widgets.updateAll(this); render() } })
        if (!checked) { checked = true; checkUpdates(false) }
    }

    private var updatePrompted = false

    private fun checkUpdates(manual: Boolean) {
        if (manual) { updateMsg = getString(R.string.checking); render() }
        Thread {
            val r = Updater.fetchNewer()
            runOnUiThread {
                newer = r
                updateMsg = if (r == null && manual) getString(R.string.up_to_date) else null
                render()
                if (r != null && (manual || !updatePrompted) && !isFinishing && !isDestroyed) {
                    updatePrompted = true
                    android.app.AlertDialog.Builder(this)
                        .setTitle(getString(R.string.update_available, r.name))
                        .setMessage(r.notes.ifBlank { getString(R.string.install_update) })
                        .setPositiveButton(R.string.install_update) { _, _ ->
                            Updater.downloadAndInstall(this, r)
                            Toast.makeText(this, getString(R.string.downloading), Toast.LENGTH_LONG).show()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                if (manual) {
                    Toast.makeText(this, if (r != null) getString(R.string.update_available, r.name)
                                         else getString(R.string.up_to_date), Toast.LENGTH_LONG).show()
                    if (r != null) scroll.post { scroll.smoothScrollTo(0, 0) }
                }
            }
        }.start()
    }

    private fun refreshAll() { Scheduler.scheduleAll(this); Widgets.updateAll(this); render() }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
        background = GradientDrawable().apply { setColor(c(R.color.card)); cornerRadius = 18 * dp }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * dp).toInt() }
        body.addView(this)
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); setTypeface(font ?: typeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
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
        val loc = resources.configuration.locales[0]

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
        header.addView(text("${getString(R.string.next)}: ${Names.of(this, nextI, nextT.toLocalDate())}  ${Widgets.fmt(nextT)}", 22f, 0xFFFFFFFF.toInt(), true).apply { setPadding(0, (8 * dp).toInt(), 0, 0) })

        val links = LinearLayout(this).apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * dp).toInt() } }
        links.addView(Button(this).apply { text = getString(R.string.qibla); setOnClickListener { startActivity(Qibla.intent(this@MainActivity)) } },
            LinearLayout.LayoutParams(0, -2, 1f))
        links.addView(Button(this).apply { text = getString(R.string.ruqyah); setOnClickListener { startActivity(Intent(this@MainActivity, RuqyahActivity::class.java)) } },
            LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(links)

        newer?.let { r ->
            val u = card()
            u.addView(text(getString(R.string.update_available, r.name), 16f, c(R.color.emerald), true))
            if (r.notes.isNotBlank()) u.addView(text(r.notes, 13f, c(R.color.sub)))
            u.addView(Button(this).apply {
                text = getString(R.string.install_update)
                setOnClickListener {
                    Updater.downloadAndInstall(this@MainActivity, r)
                    Toast.makeText(this@MainActivity, getString(R.string.downloading), Toast.LENGTH_LONG).show()
                }
            })
        }

        // Today's times
        val times = prefs.times(now.toLocalDate())
        val labels = listOf(R.string.fajr, R.string.sunrise, R.string.dhuhr, R.string.asr, R.string.maghrib, R.string.isha)
        val tCard = card()
        times.forEachIndexed { k, t ->
            val fardIndex = when (k) { 0 -> 0; 2 -> 1; 3 -> 2; 4 -> 3; 5 -> 4; else -> -1 }
            val isNext = fardIndex == nextI && nextT.toLocalDate() == now.toLocalDate()
            val row = LinearLayout(this).apply { setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt()) }
            val color = if (isNext) c(R.color.emerald) else c(R.color.text)
            val name = if (k == 2) Names.of(this, 1, now.toLocalDate()) else getString(labels[k])
            row.addView(text(name, 17f, color, isNext), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text(Widgets.fmt(t), 17f, color, true))
            tCard.addView(row)
        }
        // First Friday adhan: shown on Thursday and Friday
        val dow = now.dayOfWeek
        if (dow == java.time.DayOfWeek.THURSDAY || dow == java.time.DayOfWeek.FRIDAY) {
            val fa = prefs.firstAdhan(now)
            val row = LinearLayout(this).apply { setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt()) }
            row.addView(text(getString(R.string.first_adhan), 17f, c(R.color.gold), true), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text(Widgets.fmt(fa), 17f, c(R.color.gold), true))
            tCard.addView(row)
        }

        // Language
        val langCard = card()
        langCard.addView(text(getString(R.string.language), 14f, c(R.color.sub), true))
        val langs = listOf("ar", "en", "system")
        langCard.addView(spinner(listOf("العربية", "English", getString(R.string.lang_system)), langs.indexOf(prefs.lang).coerceAtLeast(0)) {
            prefs.lang = langs[it]; Widgets.updateAll(this); recreate()
        })

        // City
        val cityCard = card()
        cityCard.addView(text(getString(R.string.city), 14f, c(R.color.sub), true))
        val names = PrayerCalc.cities.map { if (ar) it.ar else it.en } + listOfNotNull(prefs.custom?.en)
        cityCard.addView(spinner(names, prefs.cityIndex.coerceIn(0, names.lastIndex)) {
            if (it < PrayerCalc.cities.size) prefs.setOpt("auto_location", false)
            prefs.cityIndex = it; Official.syncIfNeeded(this, force = true) { runOnUiThread { refreshAll() } }; refreshAll()
        })
        val locRow = LinearLayout(this)
        locRow.addView(Button(this).apply { text = getString(R.string.my_location); setOnClickListener { useGps() } }, LinearLayout.LayoutParams(0, -2, 1f))
        locRow.addView(Button(this).apply { text = getString(R.string.search_city); setOnClickListener { searchCity() } }, LinearLayout.LayoutParams(0, -2, 1f))
        cityCard.addView(locRow)
        cityCard.addView(Switch(this).apply {
            text = getString(R.string.auto_location); textSize = 14f; setTextColor(c(R.color.text)); font?.let { typeface = it }
            isChecked = prefs.opt("auto_location"); setPadding(0, (6 * dp).toInt(), 0, 0)
            setOnCheckedChangeListener { _, on ->
                prefs.setOpt("auto_location", on)
                AutoLocation.syncPassiveRegistration(this@MainActivity)
                if (on) {
                    if (!AutoLocation.hasPermission(this@MainActivity)) {
                        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 21)
                    } else {
                        AutoLocation.checkIfNeeded(this@MainActivity, force = true, onUpdated = { runOnUiThread { render() } })
                    }
                }
                render()
            }
        })
        if (prefs.opt("auto_location")) {
            when {
                !AutoLocation.hasPermission(this) -> cityCard.addView(text(getString(R.string.need_loc), 12f, 0xFFC0392B.toInt(), true).apply {
                    setPadding(0, (4 * dp).toInt(), 0, 0)
                    setOnClickListener {
                        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 21)
                    }
                })
                Build.VERSION.SDK_INT >= 29 && !AutoLocation.hasBackgroundPermission(this) -> cityCard.addView(text(getString(R.string.need_bg_loc), 12f, c(R.color.sub)).apply {
                    setPadding(0, (4 * dp).toInt(), 0, 0)
                    setOnClickListener {
                        requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 22)
                    }
                })
            }
        }
        cityCard.addView(Switch(this).apply {
            text = getString(R.string.official_sync); textSize = 14f; setTextColor(c(R.color.text)); font?.let { typeface = it }
            isChecked = prefs.officialSync; setPadding(0, (6 * dp).toInt(), 0, 0)
            setOnCheckedChangeListener { _, on -> prefs.officialSync = on; Official.syncIfNeeded(this@MainActivity, force = true) { runOnUiThread { refreshAll() } }; refreshAll() }
        })
        cityCard.addView(text(getString(if (prefs.isOfficial(now.toLocalDate())) R.string.src_official
            else if (prefs.officialSync && prefs.city.qch > 0) R.string.src_pending else R.string.src_calc), 12f, c(R.color.sub)))

        // Alerts
        val aCard = card()
        aCard.addView(text(getString(R.string.alerts), 14f, c(R.color.sub), true))
        val modes = listOf(getString(R.string.alert_full), getString(R.string.alert_short), getString(R.string.alert_off))
        for (i in listOf(0, 5, 6, 1, 2, 3, 4)) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val label = when (i) { 5 -> getString(R.string.sunrise); 6 -> getString(R.string.first_adhan); else -> Names.of(this, i) }
            row.addView(text(label, 16f, c(R.color.text)), LinearLayout.LayoutParams(0, -2, 1f))
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
        aCard.addView(Switch(this).apply {
            text = getString(R.string.banner_switch); textSize = 15f; setTextColor(c(R.color.text))
            font?.let { typeface = it }
            isChecked = prefs.showBanner
            setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
            setOnCheckedChangeListener { _, on -> prefs.showBanner = on; Banner.update(this@MainActivity) }
        })
        // Ways to stop the adhan
        for ((k, l) in listOf("stop_volume" to R.string.stop_volume, "stop_flip" to R.string.stop_flip)) aCard.addView(Switch(this).apply {
            text = getString(l); textSize = 15f; setTextColor(c(R.color.text)); font?.let { typeface = it }
            isChecked = prefs.opt(k); setPadding(0, (6 * dp).toInt(), 0, (2 * dp).toInt())
            setOnCheckedChangeListener { _, on -> prefs.setOpt(k, on) }
        })
        // Pre-adhan reminder
        aCard.addView(Switch(this).apply {
            text = getString(R.string.pre_adhan); textSize = 15f; setTextColor(c(R.color.text)); font?.let { typeface = it }
            isChecked = prefs.opt("pre_adhan"); setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
            setOnCheckedChangeListener { _, on -> prefs.setOpt("pre_adhan", on); refreshAll() }
        })
        if (prefs.opt("pre_adhan")) {
            val mins = (5..60).toList()
            for (i in 0..4) {
                val enabled = prefs.preAdhanEnabled(i)
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(CheckBox(this).apply {
                    text = Names.of(this@MainActivity, i) + " · " + getString(R.string.pre_adhan_before)
                    textSize = 14f; setTextColor(if (enabled) c(R.color.sub) else 0x805E6B66.toInt())
                    font?.let { typeface = it }
                    isChecked = enabled
                    setOnCheckedChangeListener { _, on -> prefs.setPreAdhanEnabled(i, on); refreshAll() }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(spinner(mins.map { "$it" }, mins.indexOf(prefs.preAdhanMin(i)).coerceAtLeast(0)) {
                    prefs.setPreAdhanMin(i, mins[it]); refreshAll()
                }.apply { isEnabled = enabled; alpha = if (enabled) 1f else 0.4f })
                aCard.addView(row)
            }
            soundPicker(aCard, R.string.sound_pre_adhan, Sounds.short, prefs.preAdhanSound, "custom_pre_adhan", 13) { prefs.preAdhanSound = it }
        }
        // Iqama
        aCard.addView(Switch(this).apply {
            text = getString(R.string.iqama); textSize = 15f; setTextColor(c(R.color.text)); font?.let { typeface = it }
            isChecked = prefs.opt("iqama"); setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
            setOnCheckedChangeListener { _, on -> prefs.setOpt("iqama", on); refreshAll() }
        })
        if (prefs.opt("iqama")) {
            val mins = (5..45).toList()
            for (i in 0..4) {
                val enabled = prefs.iqamaEnabled(i)
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(CheckBox(this).apply {
                    text = Names.of(this@MainActivity, i) + " · " + getString(R.string.iqama_after)
                    textSize = 14f; setTextColor(if (enabled) c(R.color.sub) else 0x805E6B66.toInt())
                    font?.let { typeface = it }
                    isChecked = enabled
                    setOnCheckedChangeListener { _, on -> prefs.setIqamaEnabled(i, on); refreshAll() }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(spinner(mins.map { "$it" }, mins.indexOf(prefs.iqamaMin(i)).coerceAtLeast(0)) {
                    prefs.setIqamaMin(i, mins[it]); refreshAll()
                }.apply { isEnabled = enabled; alpha = if (enabled) 1f else 0.4f })
                aCard.addView(row)
            }
            soundPicker(aCard, R.string.sound_iqama, Sounds.short, prefs.iqamaSound, "custom_iqama", 12) { prefs.iqamaSound = it }
        }
        soundPicker(aCard, R.string.sound_full, Sounds.full, prefs.fullSound, "custom_full", 10) { prefs.fullSound = it }
        soundPicker(aCard, R.string.sound_short, Sounds.short, prefs.shortSound, "custom_short", 11) { prefs.shortSound = it }
        aCard.addView(Button(this).apply { text = getString(R.string.test_full); setOnClickListener { test(ALERT_FULL) } })
        aCard.addView(Button(this).apply { text = getString(R.string.test_short); setOnClickListener { test(ALERT_SHORT) } })
        if (prefs.opt("pre_adhan")) {
            aCard.addView(Button(this).apply { text = getString(R.string.test_pre_adhan); setOnClickListener { test(ALERT_PRE_ADHAN) } })
        }
        if (prefs.opt("iqama")) {
            aCard.addView(Button(this).apply { text = getString(R.string.test_iqama); setOnClickListener { test(ALERT_IQAMA) } })
        }
        aCard.addView(text(getString(R.string.battery_tip), 12f, c(R.color.sub)).apply {
            setPadding(0, (6 * dp).toInt(), 0, 0)
            setOnClickListener { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        })

        // Widget and banner options
        val oCard = card()
        oCard.addView(text(getString(R.string.widget_options), 14f, c(R.color.sub), true))
        fun optSwitch(key: String, label: Int) = oCard.addView(Switch(this).apply {
            text = getString(label); textSize = 15f; setTextColor(c(R.color.text)); font?.let { typeface = it }
            isChecked = prefs.opt(key); setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
            setOnCheckedChangeListener { _, on -> prefs.setOpt(key, on); Scheduler.scheduleAll(this@MainActivity); Widgets.updateAll(this@MainActivity) }
        })
        oCard.addView(Switch(this).apply {
            text = getString(R.string.h24); textSize = 15f; setTextColor(c(R.color.text)); font?.let { typeface = it }
            isChecked = prefs.h24; setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
            setOnCheckedChangeListener { _, on -> prefs.h24 = on; refreshAll() }
        })
        Options.widget.forEach { (k, l) -> optSwitch(k, l) }
        oCard.addView(text(getString(R.string.banner_options), 14f, c(R.color.sub), true).apply { setPadding(0, (12 * dp).toInt(), 0, 0) })
        Options.banner.forEach { (k, l) -> optSwitch(k, l) }

        // Calculation settings
        val sCard = card()
        val goldHdr = if ((resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES) 0xFFF5C84C.toInt() else 0xFFB88922.toInt()
        sCard.addView(text(getString(R.string.settings), 14f, goldHdr, true))
        sCard.addView(text(getString(R.string.method), 13f, c(R.color.text)))
        sCard.addView(spinner(PrayerCalc.methods.map { if (ar) it.nameAr else it.name }, prefs.methodIndex) { prefs.methodIndex = it; refreshAll() })
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

        // Version and updates
        val vCard = card()
        vCard.addView(text(getString(R.string.version, BuildConfig.VERSION_NAME), 14f, c(R.color.sub), true))
        updateMsg?.let { vCard.addView(text(it, 13f, c(R.color.text))) }
        vCard.addView(Button(this).apply { text = getString(R.string.check_updates); setOnClickListener { checkUpdates(true) } })
        val uCard = card()
        uCard.addView(text(if (ar) "اتحاد سهل" else "Sahl Union", 13f, goldHdr, true))
        uCard.addView(SahlUnionApps.strip(this, c(R.color.sub)))
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

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        AutoLocation.syncPassiveRegistration(this)
        when {
            rc == 20 && r.any { it == PackageManager.PERMISSION_GRANTED } -> useGps()
            rc == 21 && r.any { it == PackageManager.PERMISSION_GRANTED } -> {
                AutoLocation.checkIfNeeded(this, force = true, onUpdated = { runOnUiThread { render() } })
                refreshAll()
            }
            else -> refreshAll()
        }
    }
}
