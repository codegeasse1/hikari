package eu.kanade.tachiyomi.util

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The `parseAs` helpers extensions-lib declares for sources that parse JSON.
 * Aniyomi's app-side copy is annotated with context receivers (a compiler opt-in
 * Hikari does not enable); this version takes the app's shared [Json] straight
 * out of Injekt, exactly like the extension-lib stub did.
 */
val defaultJson: Json get() = Injekt.get<Json>()

inline fun <reified T> Response.parseAs(transform: (String) -> String): T =
    defaultJson.decodeFromString(transform(body!!.string()))

inline fun <reified T> Response.parseAs(): T =
    defaultJson.decodeFromString(body!!.string())

inline fun <reified T> String.parseAs(transform: (String) -> String): T =
    defaultJson.decodeFromString(transform(this))

inline fun <reified T> String.parseAs(): T = defaultJson.decodeFromString(this)
