package com.weenas.castbay.service.desktop

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A desktop sender that entered the pairing code, by the fingerprint of its certificate's key. */
data class LinkPairing(val fingerprint: String, val name: String)

/** Desktop senders that paired: they connect again without a code. Kept apart from AirPlay's PIN pairings. */
class LinkPairings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("link_pairings", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): List<LinkPairing> = try {
        val array = JSONArray(preferences.getString(KEY, "[]"))
        (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            LinkPairing(item.getString("fp"), item.optString("name"))
        }
    } catch (e: org.json.JSONException) {
        emptyList()
    }

    fun contains(fingerprint: String) = load().any { it.fingerprint == fingerprint }

    @Synchronized
    fun add(pairing: LinkPairing) {
        val all = load().filterNot { it.fingerprint == pairing.fingerprint } + pairing
        val array = JSONArray()
        all.forEach { array.put(JSONObject().put("fp", it.fingerprint).put("name", it.name)) }
        preferences.edit().putString(KEY, array.toString()).apply()
    }

    @Synchronized
    fun clear() {
        preferences.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "pairings"
    }
}
