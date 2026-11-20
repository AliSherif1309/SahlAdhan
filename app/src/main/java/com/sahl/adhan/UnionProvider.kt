package com.sahl.adhan

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import java.time.LocalDate

/**
 * Sahl Union door of Sahl Adhan (only apps signed with the Sahl key can open it):
 *   content://com.sahl.adhan.union/prayers -> index (0 Fajr..4 Isha), name, time (epoch ms) for today and tomorrow
 * Sahl Phone uses it to keep calls silent during prayer.
 */
class UnionProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val c = context ?: return null
        if (uri.pathSegments.firstOrNull() != "prayers") return null
        return try {
            val prefs = Prefs(c)
            val out = MatrixCursor(arrayOf("index", "name", "time"))
            val today = LocalDate.now(prefs.zone)
            for (d in listOf(today, today.plusDays(1))) {
                prefs.fard(d).forEachIndexed { i, t ->
                    out.addRow(arrayOf<Any>(i, Names.of(c.localized(), i, d), t.toInstant().toEpochMilli()))
                }
            }
            out
        } catch (e: Exception) { null }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
