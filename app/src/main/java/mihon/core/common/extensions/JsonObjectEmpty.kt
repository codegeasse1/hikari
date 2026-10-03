package mihon.core.common.extensions

import kotlinx.serialization.json.JsonObject

/**
 * The empty [JsonObject] — the app-side counterpart of the `EMPTY` extension the
 * Aniyomi source-api expects on `JsonObject`'s companion (Aniyomi gets it from
 * `mihon.core.common`, which Hikari does not ship).
 */
val JsonObject.Companion.EMPTY: JsonObject get() = JsonObject(emptyMap())
