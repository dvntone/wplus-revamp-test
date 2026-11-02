package com.wscanplus.android.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * OuiLookup – resolves the first 3 octets (OUI) of a MAC address to an
 * IEEE-registered manufacturer name using a local SQLite database.
 *
 * The database is expected to live at  assets/oui.db  with the schema:
 *
 *   CREATE TABLE oui (prefix TEXT PRIMARY KEY, vendor TEXT NOT NULL);
 *
 * The `prefix` column stores the OUI in uppercase without separators,
 * e.g. "001A2B".  A pre-populated oui.db can be generated from the IEEE
 * MA-L CSV export: https://regauth.standards.ieee.org/standards-ra-web/pub/view.html#registries
 */
class OuiLookup private constructor(private val db: SQLiteDatabase) {

    /**
     * Look up the vendor name for a given MAC address.
     *
     * @param mac MAC address in any common format (colons, dashes, or raw hex,
     *            upper- or lower-case).  Only the first 3 octets are used.
     * @return Vendor name, or null if not found in the database.
     */
    fun lookupVendor(mac: String): String? {
        val prefix = normalizeMacPrefix(mac) ?: return null
        return try {
            db.rawQuery("SELECT vendor FROM oui WHERE prefix = ?", arrayOf(prefix))
                .use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        } catch (e: Exception) {
            Log.w(TAG, "OUI lookup failed for prefix $prefix", e)
            null
        }
    }

    /**
     * Returns true when the vendor name associated with [mac] differs from
     * [expectedVendor] (case-insensitive).  Useful for spotting MAC spoofing
     * (e.g. a "Netgear" device sending from a Raspberry Pi OUI).
     */
    fun isSuspiciousVendor(mac: String, expectedVendor: String): Boolean {
        val actual = lookupVendor(mac) ?: return false
        return !actual.equals(expectedVendor, ignoreCase = true)
    }

    companion object {
        private const val TAG = "OuiLookup"
        private const val DB_ASSET = "oui.db"

        @Volatile
        private var instance: OuiLookup? = null

        fun getInstance(context: Context): OuiLookup =
            instance ?: synchronized(this) {
                instance ?: create(context).also { instance = it }
            }

        private fun create(context: Context): OuiLookup {
            val dbPath = context.getDatabasePath(DB_ASSET)
            if (!dbPath.exists()) {
                dbPath.parentFile?.mkdirs()
                context.assets.open(DB_ASSET).use { input ->
                    dbPath.outputStream().use { output -> input.copyTo(output) }
                }
            }
            val db = SQLiteDatabase.openDatabase(
                dbPath.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            return OuiLookup(db)
        }

        /**
         * Strip separators and return the first 6 hex characters (3 octets)
         * in uppercase, or null if the MAC address is too short / malformed.
         */
        internal fun normalizeMacPrefix(mac: String): String? {
            val clean = mac.replace(Regex("[:\\-.]"), "").uppercase()
            return if (clean.length >= 6) clean.take(6) else null
        }
    }
}
