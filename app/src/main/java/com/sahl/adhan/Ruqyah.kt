package com.sahl.adhan

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
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

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(newBase.localized()) }

    private val dp get() = resources.displayMetrics.density
    private val font by lazy { try { resources.getFont(R.font.thmanyah) } catch (e: Exception) { null } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (32 * dp).toInt())
        }
        setContentView(ScrollView(this).apply { addView(body) })

        body.addView(label(getString(R.string.ruqyah), 26f, getColor(R.color.emerald), true).apply { gravity = Gravity.CENTER })
        body.addView(Button(this).apply {
            text = getString(R.string.ruqyah_listen)
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(VIDEO))) }
        })

        section(body, getString(R.string.ruqyah_sunnah))
        for ((t, n, src) in SUNNAH) {
            val meta = (if (n > 1) getString(R.string.times, n) + " · " else "") + src
            card(body, t, meta)
        }

        section(body, getString(R.string.ruqyah_quran))
        try {
            val arr = JSONArray(assets.open("ruqyah_quran.json").bufferedReader().readText())
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val ay = r.getJSONArray("ayahs")
                val sb = StringBuilder()
                for (k in 0 until ay.length()) {
                    val a = ay.getJSONObject(k)
                    sb.append(a.getString("t")).append(" ﴿").append(arabicDigits(a.getInt("n"))).append("﴾ ")
                }
                val range = if (r.getInt("from") == r.getInt("to")) arabicDigits(r.getInt("from"))
                            else "${arabicDigits(r.getInt("from"))}–${arabicDigits(r.getInt("to"))}"
                card(body, sb.toString().trim(), "${r.getString("surah")} · $range", quran = true)
            }
        } catch (e: Exception) {
            body.addView(label("—", 16f, getColor(R.color.sub), false))
        }
    }

    private fun arabicDigits(n: Int) = n.toString().map { "٠١٢٣٤٥٦٧٨٩"[it - '0'] }.joinToString("")

    private fun label(s: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        typeface = Typeface.create(font ?: Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun section(body: LinearLayout, title: String) {
        body.addView(label(title, 19f, getColor(R.color.gold), true).apply { setPadding(0, (18 * dp).toInt(), 0, (4 * dp).toInt()) })
    }

    private fun card(body: LinearLayout, text: String, meta: String, quran: Boolean = false) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply { setColor(getColor(R.color.card)); cornerRadius = 16 * dp }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * dp).toInt() }
        }
        // Quran and du'a with full diacritics read best in the system Naskh font
        c.addView(TextView(this).apply {
            this.text = text; textSize = if (quran) 21f else 20f; setTextColor(getColor(R.color.text))
            setLineSpacing(0f, 1.5f); gravity = Gravity.START
        })
        c.addView(label(meta, 13f, getColor(R.color.sub), false).apply { setPadding(0, (6 * dp).toInt(), 0, 0) })
        body.addView(c)
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
