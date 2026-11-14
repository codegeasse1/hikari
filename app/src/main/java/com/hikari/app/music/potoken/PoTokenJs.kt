package com.hikari.app.music.potoken

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/*
 * Helpers for the BotGuard / PO token flow. Ported from NewPipe (org.schabi.newpipe.util.potoken),
 * rewritten on org.json + android.util.Base64 so Hikari needs no extra libraries.
 */

/** Parses the raw challenge of the `Create` endpoint into a JSON object that can be pasted into JavaScript. */
internal fun parseChallengeData(rawChallengeData: String): String {
    val scrambled = JSONArray(rawChallengeData)
    val challengeData = if (scrambled.length() > 1 && scrambled.get(1) is String) {
        JSONArray(descramble(scrambled.getString(1)))
    } else {
        scrambled.getJSONArray(0)
    }
    val messageId = challengeData.optString(0, "")
    val interpreterHash = challengeData.optString(3, "")
    val program = challengeData.optString(4, "")
    val globalName = challengeData.optString(5, "")
    val clientExperimentsStateBlob = challengeData.optString(7, "")

    fun firstString(index: Int): Any {
        val arr = challengeData.optJSONArray(index) ?: return JSONObject.NULL
        for (i in 0 until arr.length()) {
            val v = arr.get(i)
            if (v is String) return v
        }
        return JSONObject.NULL
    }

    val interpreter = JSONObject()
        .put("privateDoNotAccessOrElseSafeScriptWrappedValue", firstString(1))
        .put("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue", firstString(2))
    return JSONObject()
        .put("messageId", messageId)
        .put("interpreterJavascript", interpreter)
        .put("interpreterHash", interpreterHash)
        .put("program", program)
        .put("globalName", globalName)
        .put("clientExperimentsStateBlob", clientExperimentsStateBlob)
        .toString()
}

/** The integrity token as a JavaScript `Uint8Array(...)` text, and how many seconds it stays valid. */
internal fun parseIntegrityTokenData(rawIntegrityTokenData: String): Pair<String, Long> {
    val data = JSONArray(rawIntegrityTokenData)
    return newUint8Array(base64ToBytes(data.getString(0))) to data.getLong(1)
}

internal fun stringToU8(identifier: String): String = newUint8Array(identifier.toByteArray())

/** "97,98,99" (what `Uint8Array.toString()` prints) -> YouTube's URL-safe base64 poToken. */
internal fun u8ToBase64(poToken: String): String {
    val bytes = poToken.split(",").map { it.trim().toInt().toByte() }.toByteArray()
    return Base64.encodeToString(bytes, Base64.NO_WRAP).replace('+', '-').replace('/', '_')
}

private fun descramble(scrambledChallenge: String): String {
    val bytes = base64ToBytes(scrambledChallenge)
    return String(ByteArray(bytes.size) { (bytes[it] + 97).toByte() }, Charsets.UTF_8)
}

private fun newUint8Array(contents: ByteArray): String =
    "new Uint8Array([" + contents.joinToString(separator = ",") { (it.toInt() and 0xFF).toString() } + "])"

private fun base64ToBytes(base64: String): ByteArray {
    val fixed = base64.replace('-', '+').replace('_', '/').replace('.', '=')
    return try {
        Base64.decode(fixed, Base64.DEFAULT)
    } catch (e: IllegalArgumentException) {
        throw PoTokenException("Cannot base64 decode")
    }
}
