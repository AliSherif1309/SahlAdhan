package com.sahl.adhan

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray

/** الرقية الشرعية: from the Sunnah, then from the Quran (verses bundled at build time). */
class RuqyahActivity : Activity() {
    companion object {
        const val VIDEO = "https://www.youtube.com/watch?v=BVtCjMyJ320"

        /** Text, how many times, source */
        val SUNNAH = listOf(
            Triple("أَعُوذُ بِكَلِمَاتِ اللَّهِ التَّامَّاتِ مِنْ شَرِّ مَا خَلَقَ", 3, "رواه مسلم"),
            Triple("بِسْمِ اللَّهِ الَّذِي لَا يَضُرُّ مَعَ اسْمِهِ شَيْءٌ فِي الْأَرْضِ وَلَا فِي السَّمَاءِ وَهُوَ السَّمِيعُ الْعَلِيمُ", 3, "رواه أبو داود والترمذي"),
            Triple("اللَّهُمَّ رَبَّ النَّاسِ، أَذْهِبِ الْبَأْسَ، اشْفِ أَنْتَ الشَّافِي، لَا شِفَاءَ إِلَّا شِفَاؤُكَ، شِفَاءً لَا يُغَادِرُ سَقَمًا", 1, "متفق عليه"),
            Triple("بِسْمِ اللَّهِ أَرْقِيكَ، مِنْ كُلِّ شَيْءٍ يُؤْذِيكَ، مِنْ شَرِّ كُلِّ نَفْسٍ أَوْ عَيْنِ حَاسِدٍ، اللَّهُ يَشْفِيكَ، بِسْمِ اللَّهِ أَرْقِيكَ", 1, "رواه مسلم"),
            Triple("أَسْأَلُ اللَّهَ الْعَظِيمَ، رَبَّ الْعَرْشِ الْعَظِيمِ، أَنْ يَشْفِيَكَ", 7, "رواه أبو داود والترمذي"),
            Triple("ضع يدك على موضع الألم وقل: بِسْمِ اللَّهِ (ثلاثًا)، ثم: أَعُوذُ بِاللَّهِ وَقُدْرَتِهِ مِنْ شَرِّ مَا أَجِدُ وَأُحَاذِرُ", 7, "رواه مسلم"),
            Triple("أَعُوذُ بِكَلِمَاتِ اللَّهِ التَّامَّةِ، مِنْ كُلِّ شَيْطَانٍ وَهَامَّةٍ، وَمِنْ كُلِّ عَيْنٍ لَامَّةٍ", 1, "رواه البخاري"),
            Triple("لَا إِلَهَ إِلَّا اللَّهُ الْعَظِيمُ الْحَلِيمُ، لَا إِلَهَ إِلَّا اللَّهُ رَبُّ الْعَرْشِ الْعَظِيمِ، لَا إِلَهَ إِلَّا اللَّهُ رَبُّ السَّمَاوَاتِ وَرَبُّ الْأَرْضِ وَرَبُّ الْعَرْشِ الْكَرِيمِ", 1, "متفق عليه"),
            Triple("اللَّهُمَّ إِنِّي أَعُوذُ بِكَ مِنَ الْهَمِّ وَالْحَزَنِ، وَالْعَجْزِ وَالْكَسَلِ، وَالْبُخْلِ وَالْجُبْنِ، وَضَلَعِ الدَّيْنِ وَغَلَبَةِ الرِّجَالِ", 1, "رواه البخاري"),
        )
    }

    private var volDownConsumed = false

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

    private val dp get() = resources.displayMetrics.density
    private val font by lazy { try { resources.getFont(R.font.thmanyah) } catch (e: Exception) { null } }

    /** One card: section title, text, info line, is Quran, times to repeat */
    private data class Item(val section: String, val text: String, val meta: String, val quran: Boolean, val times: Int)
    private val items = mutableListOf<Item>()
    private var pos = 0
    private var left = 0
    private lateinit var cardText: TextView
    private lateinit var cardMeta: TextView
    private lateinit var cardSection: TextView
    private lateinit var progress: TextView
    private lateinit var bar: android.widget.ProgressBar
    private lateinit var counter: Button
    private lateinit var cardScroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadItems()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
        }
        setContentView(root)

        root.addView(label(getString(R.string.ruqyah), 24f, getColor(R.color.emerald), true).apply { gravity = Gravity.CENTER })
        root.addView(Button(this).apply {
            text = getString(R.string.ruqyah_listen)
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(VIDEO))) }
        })
        progress = label("", 14f, getColor(R.color.sub), true).apply { gravity = Gravity.CENTER }
        root.addView(progress)
        bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = items.size; progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.gold))
        }
        root.addView(bar)

        // The card
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * dp).toInt(), (16 * dp).toInt(), (18 * dp).toInt(), (16 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(getColor(R.color.card)); cornerRadius = 22 * dp; setStroke((1 * dp).toInt(), getColor(R.color.gold))
            }
        }
        cardSection = label("", 15f, getColor(R.color.gold), true)
        cardText = TextView(this).apply { textSize = 22f; setTextColor(getColor(R.color.text)); setLineSpacing(0f, 1.6f) }
        cardMeta = label("", 13f, getColor(R.color.sub), false).apply { setPadding(0, (10 * dp).toInt(), 0, 0) }
        cardScroll = ScrollView(this).apply {
            addView(LinearLayout(this@RuqyahActivity).apply {
                orientation = LinearLayout.VERTICAL; addView(cardText); addView(cardMeta)
            })
        }
        card.addView(cardSection)
        card.addView(cardScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        counter = Button(this).apply { setOnClickListener { count() } }
        card.addView(counter)
        root.addView(card, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = (10 * dp).toInt() })

        // Swipe between cards
        val swipe = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                if (kotlin.math.abs(vx) < kotlin.math.abs(vy) * 1.5f) return false
                go(if (vx > 0) 1 else -1)   // Arabic reading: swipe right → next
                return true
            }
        })
        cardScroll.setOnTouchListener { _, e -> swipe.onTouchEvent(e); false }

        // Previous / next
        val nav = LinearLayout(this).apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * dp).toInt() } }
        nav.addView(Button(this).apply { text = getString(R.string.prev_card); setOnClickListener { go(-1) } }, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(Button(this).apply { text = getString(R.string.next_card); setOnClickListener { go(1) } }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(nav)

        pos = getSharedPreferences("sahl", MODE_PRIVATE).getInt("ruqyahPos", 0).coerceIn(0, (items.size - 1).coerceAtLeast(0))
        show()
    }

    private fun loadItems() {
        // 1) Quran first, in order
        try {
            val arr = JSONArray(assets.open("ruqyah_quran.json").bufferedReader().readText())
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val ay = r.getJSONArray("ayahs")
                val sb = StringBuilder()
                for (k in 0 until ay.length()) {
                    val x = ay.getJSONObject(k)
                    sb.append(x.getString("t")).append(" ﴿").append(arabicDigits(x.getInt("n"))).append("﴾ ")
                }
                val range = if (r.getInt("from") == r.getInt("to")) arabicDigits(r.getInt("from"))
                            else "${arabicDigits(r.getInt("from"))}–${arabicDigits(r.getInt("to"))}"
                items += Item(getString(R.string.ruqyah_quran), sb.toString().trim(), "${r.getString("surah")} · $range", true, 1)
            }
        } catch (e: Exception) { }
        // 2) Then the Sunnah
        for ((t, n, src) in SUNNAH) items += Item(getString(R.string.ruqyah_sunnah), t, src, false, n)
    }

    private fun go(step: Int) {
        val p = (pos + step).coerceIn(0, items.size - 1)
        if (p != pos) { pos = p; show() }
    }

    private fun show() {
        if (items.isEmpty()) return
        val it = items[pos]
        left = it.times
        cardSection.text = it.section
        cardText.text = it.text
        cardMeta.text = it.meta
        progress.text = "${pos + 1} / ${items.size}"
        bar.progress = pos + 1
        updateCounter()
        cardScroll.scrollTo(0, 0)
        getSharedPreferences("sahl", MODE_PRIVATE).edit().putInt("ruqyahPos", pos).apply()
    }

    /** Tap to count repetitions; moves on by itself when done */
    private fun count() {
        if (left > 1) { left--; updateCounter() } else go(1)
    }

    private fun updateCounter() {
        counter.text = if (left > 1) getString(R.string.remaining_times, left) else getString(R.string.done_next)
    }

    private fun arabicDigits(n: Int) = n.toString().map { "٠١٢٣٤٥٦٧٨٩"[it - '0'] }.joinToString("")

    private fun label(s: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        typeface = Typeface.create(font ?: Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
}

/** Opens Sahl Qibla if installed, otherwise its download page. */
object Qibla {
    const val PKG = "com.aly.trueqibla"
    const val RELEASES = "https://github.com/AliSherif1309/TrueQibla-Android/releases/latest"
    fun intent(ctx: Context): Intent =
        (ctx.packageManager.getLaunchIntentForPackage(PKG) ?: Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
