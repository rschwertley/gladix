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
 */
class ExtensionNotFoundException(val id: String?) : Exception(
    if (id == null) "Media item has no extension stamp" else "Extension not found: $id"
)