package dev.brahmkshatriya.echo.extension

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * ⚠⚠ `?.jsonObject` IS NOT A GUARD, AND THE WHOLE FILE EXISTS BECAUSE IT LOOKS LIKE ONE.
 * kotlinx-serialization's accessor is
 *     public val JsonElement.jsonObject: JsonObject get() = this as? JsonObject ?: error("JsonObject")
 * with `error` throwing IllegalArgumentException("Element ${this::class} is not a $element")
 * (kotlinx-serialization-json 1.11.0, JsonElement.kt:231-232 and :320-321). So `?.` covers an ABSENT
 * key and nothing else: a key that is PRESENT and carries a literal still throws. The gateway does
 * exactly that - it answers a bare `false` for some empty or refused payloads.
 *
 * FIELD EVIDENCE: build 876 (June), IllegalArgumentException "Element class JsonLiteral is not a
 * JsonObject" from DeezerExtension.listEditablePlaylists. `JsonLiteral` is kotlinx's internal class
 * for NON-NULL primitives, so the field was present and primitive - an absent key would have failed
 * on the `!!` instead, and a null would have printed `JsonNull`.
 *
 * ⚠⚠ TWO HELPERS, AND CHOOSING BETWEEN THEM IS A JUDGEMENT ABOUT CONTENT, NOT A STYLE
 * PREFERENCE:
 *   [objOrNull] / [arrOrNull] - a wrong type behaves like an ABSENT key. Use where the caller
 *       already degrades to an empty result AND empty is a genuine state the user can be shown
 *       (no editable playlists, nothing followed, an empty library tab).
 *   [requireObj] / [requireArr] - a wrong type throws [DeezerShapeException]. Use where an empty
 *       result would MISSTATE CONTENT: an album with no tracks, a playlist that failed to load, a
 *       show with no episodes. The precedent is DeezerPlaylistClient.loadTracks, which already
 *       refuses to "degrade to empty, which would mask the failure as an empty playlist".
 *
 * ⚠️ THE NULLABLE RECEIVER IS WHAT MAKES ONE STRICT HELPER COVER TWO CASES. Called through
 * `?.` the absence is short-circuited before the helper runs, so `x["SONGS"]?.requireObj("SONGS")`
 * means "absent is fine, a literal is not" - which is what the album-section reads need, since their
 * absence behaviour was decided deliberately. Called directly, `x["results"].requireObj("results")`
 * throws on absent too, which is what the old `!!.jsonObject` sites did.
 *
 * ⚠️ BEHAVIOUR WITH A WELL-FORMED RESPONSE IS UNCHANGED AT EVERY SITE. These are the same
 * casts the accessors already perform; only the failure branch differs.
 */
internal fun JsonElement?.objOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement?.arrOrNull(): JsonArray? = this as? JsonArray

internal fun JsonElement?.requireObj(path: String): JsonObject =
    this as? JsonObject ?: throw DeezerShapeException(path, "object", describe())

internal fun JsonElement?.requireArr(path: String): JsonArray =
    this as? JsonArray ?: throw DeezerShapeException(path, "array", describe())

private fun JsonElement?.describe(): String =
    if (this == null) "absent" else this::class.simpleName ?: "unknown"

/**
 * ⚠️ TWO STRINGS, THE SAME SPLIT AS [DeezerGatewayException]: a readable sentence for the
 * user, and the detail in [toString] for a report. The message deliberately does not name a JSON
 * path - "results" means nothing to the person holding the phone - while the report needs exactly
 * that to be triaged, and App.kt's throwFlow collector records toString.
 * ⚠️ NO CRASHLYTICS KEY OF ITS OWN. deezer_gateway is written from a cause-chain walk for
 * DeezerGatewayException specifically (App.deezerGatewayDetail); this type arrives through the
 * ordinary non-fatal path with its toString in the trace, which is enough to say which field broke.
 * Add a key only if these ever arrive in a volume that needs grouping.
 */
internal class DeezerShapeException(
    val path: String,
    val expected: String,
    val got: String,
) : Exception("Deezer sent an unexpected response.") {
    override fun toString() =
        "DeezerShapeException(path=$path, expected=$expected, got=$got)"
}
