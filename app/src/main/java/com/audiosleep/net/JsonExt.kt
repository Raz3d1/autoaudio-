package com.audiosleep.net

import org.json.JSONObject

/** Returns "" when the value is JSON null (optString would return the literal "null"). */
fun JSONObject.str(key: String): String {
    if (isNull(key)) return ""
    val v = optString(key)
    return if (v == "null") "" else v
}

/** First non-empty string among [keys]. */
fun JSONObject.firstStr(vararg keys: String): String {
    for (k in keys) {
        val v = str(k)
        if (v.isNotBlank()) return v
    }
    return ""
}
