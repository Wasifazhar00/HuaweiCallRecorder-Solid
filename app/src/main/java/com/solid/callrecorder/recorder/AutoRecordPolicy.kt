package com.solid.callrecorder.recorder

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import com.solid.callrecorder.ui.MainActivity

/**
 * App-local policy inspired by Oplus shouldAutoRecord checks,
 * without system privileges.
 */
object AutoRecordPolicy {

    private const val TAG = "AutoRecordPolicy"

    fun shouldRecord(context: Context, phoneNumber: String?): Boolean {
        val prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(MainActivity.KEY_AUTO_RECORD, true)) return false

        val mode = prefs.getString(MainActivity.KEY_AUTO_MODE, MODE_ALL) ?: MODE_ALL
        return when (mode) {
            MODE_OFF -> false
            MODE_STRANGERS -> isStranger(context, phoneNumber)
            else -> true // MODE_ALL
        }
    }

    /** True if number is empty or not in contacts. */
    fun isStranger(context: Context, phoneNumber: String?): Boolean {
        val raw = phoneNumber?.trim().orEmpty()
        if (raw.isEmpty() || raw.equals("manual", true) || raw.equals("unknown", true)) {
            return true
        }
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(raw)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null,
                null,
                null
            )?.use { c -> !c.moveToFirst() } ?: true
        } catch (e: SecurityException) {
            Log.w(TAG, "No READ_CONTACTS – treating as stranger for safety of policy")
            true
        } catch (e: Exception) {
            Log.w(TAG, "contact lookup: ${e.message}")
            true
        }
    }

    const val MODE_ALL = "all"
    const val MODE_STRANGERS = "strangers"
    const val MODE_OFF = "off"
}
