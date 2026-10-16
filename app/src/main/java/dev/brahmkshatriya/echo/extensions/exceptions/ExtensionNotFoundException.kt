package dev.brahmkshatriya.echo.extensions.exceptions

/**
 * An extension that something needed is not available. [id] names it, or is null when the caller
 * never had an id to look up.
 *
 * ⚠⚠ TWO CONDITIONS, ONE TYPE, TOLD APART BY NULLNESS - AND THAT IS A DECISION, NOT AN
 * ACCIDENT:
 *   id NON-NULL  a CONFIG problem. The item named an extension that is not installed. Actionable:
 *                install or re-enable it.
 *   id NULL      a DATA problem. The item carries no extension stamp at all, so nothing was ever
 *                looked up. "Not found" is the wrong word for it - IT WAS NEVER ASKED.
 * ⚠️ WHY ONE TYPE AND NOT TWO, having argued both sides: NO CONSUMER BRANCHES ON THE
 * DIFFERENCE. Both are non-retryable, both take the skip-breaker exemption, both mean the item
 * cannot be played - a second type would be a second name for one behaviour. And the triage
 * benefit a second type would buy is ALREADY AVAILABLE: the two arise at different call sites
 * (UnifiedExtension's List<Extension<*>>.get vs its Map.extensionId accessor), so they group as
 * separate issues on stack trace alone. Split the MESSAGE, which is what differs to a reader; do
 * not split the type.
 * If a consumer ever genuinely needs to branch, revisit - that would be the evidence this decision
 * is currently missing.
 * ⚠⚠ [CORRECTED 2026-10-15] THE EVIDENCE ARRIVED, AND ONE CLAUSE ABOVE IS NOW FALSE:
 * "both take the skip-breaker exemption" no longer holds, and it was the dangerous half.
 * PlayerEventListener.onPlayerError now exempts only id != null. A null id is a DATA fault in our
 * own pipeline, and granting it the exemption written for an uninstalled extension is what let a
 * 25-track Unified queue fail completely, in under half a second, with no skip recorded and
 * nothing reported (build 1126; Cached's slim allowlist had been dropping `extension_id` since
 * d221f4c2, 2026-08-02).
 * ⚠️ THE ONE-TYPE DECISION ITSELF STANDS, which is why this is an amendment and not a
 * split. Its remaining argument is unchanged and still good: the two arise at different call
 * sites, so they group separately on stack trace alone, and [id] is a public val - a consumer
 * that needs to branch can, as onPlayerError now does. What expired was the PREMISE that none
 * would.
 */
class ExtensionNotFoundException(val id: String?) : Exception(
    if (id == null) "Media item has no extension stamp" else "Extension not found: $id"
)