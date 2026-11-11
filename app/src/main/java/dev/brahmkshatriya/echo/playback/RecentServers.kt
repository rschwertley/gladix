package dev.brahmkshatriya.echo.playback

import android.os.SystemClock
import dev.brahmkshatriya.echo.common.models.Streamable

/**
 * [2026-11-11] PlayerState.servers' store: the [CAP] entries used most recently, and never one used in the last [IN_USE_MS].
 * Always reached through Collections.synchronizedMap (PlayerState.servers), which is what makes the access order and [usedAt]
 * safe across the loader, sidecar and cast threads.
 *
 * WHAT COUNTS AS USE: a write (each resolve, StreamableMediaSource.prepareSourceInternal) or a read. Every reader that needs an
 * entry reads it when it does: StreamableResolver on each open (main player, sidecar, CastStreamStore, LocalMediaServer),
 * CacheOnlyTailSource for the tail and the start look-ahead's head, resolveFailed for the next song, CastSessionController and
 * PlayerViewModel's quality line for the current one.
 * WHY NOTHING IN USE IS EVICTED: an entry goes only when it is beyond the newest [CAP] AND unused for [IN_USE_MS]. Only a
 * resolve adds an entry, one per song prepared, so passing a live song takes [CAP] other songs prepared after its last use.
 * The songs in use at once are a handful (current, the next one or two being prepared, the crossfade's outgoing song, the cast
 * one), and each is read again on every open. The time floor covers the one case recency does not: a long open with no
 * re-read while a skip storm resolves many others. With it, a burst can only push the map over [CAP] for a while, never take
 * a live entry.
 * The size: an entry is a Server with a source or two (a URL, or Deezer's InputProvider capturing its Streamable), around a
 * kilobyte at most (inferred, not measured), so [CAP] holds a few hundred KB.
 */
class RecentServers : LinkedHashMap<String, Result<Streamable.Media.Server>>(16, 0.75f, true) {

    private val usedAt = HashMap<String, Long>()

    override fun get(key: String): Result<Streamable.Media.Server>? =
        super.get(key)?.also { usedAt[key] = SystemClock.elapsedRealtime() }

    override fun put(key: String, value: Result<Streamable.Media.Server>): Result<Streamable.Media.Server>? {
        val old = super.put(key, value)
        usedAt[key] = SystemClock.elapsedRealtime()
        trim()
        return old
    }

    override fun remove(key: String): Result<Streamable.Media.Server>? {
        usedAt.remove(key)
        return super.remove(key)
    }

    // Oldest use first (access order); skips anything used within IN_USE_MS.
    private fun trim() {
        if (size <= CAP) return
        val now = SystemClock.elapsedRealtime()
        val oldest = entries.iterator()
        while (size > CAP && oldest.hasNext()) {
            val key = oldest.next().key
            if (now - (usedAt[key] ?: 0L) < IN_USE_MS) continue
            oldest.remove()
            usedAt.remove(key)
        }
    }

    private companion object {
        const val CAP = 100
        const val IN_USE_MS = 30L * 60 * 1000
    }
}
