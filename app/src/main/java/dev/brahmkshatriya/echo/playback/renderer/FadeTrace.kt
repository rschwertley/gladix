// ===================================================================================================
// FADEDBG — TEMPORARY INSTRUMENTATION. REVERT AFTER ONE DRIVE.
// One-pass fade-in/fade-out boundary capture. Audio-thread writes are primitive-only (no Log, no
// String, no allocation) into a rolling ring; formatting + Log happen OFF the audio thread after a
// delayed dump triggered by the first AUTO transition.
// To revert: delete this file and every line tagged `// FADEDBG` in AudioEffectsProcessor.kt and
// EffectsListener.kt (grep -rn "FADEDBG").
// ===================================================================================================
package dev.brahmkshatriya.echo.playback.renderer

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object FadeTrace {
    // Event codes
    const val QES = 1      // onQueueEndOfStream           a=pendingBefore b=fadeIn c=fadeOut d=crossEnabled e=skipFade
    const val CFG = 2      // onConfigure                  a=encoding b=sampleRate c=crossfadeFrames
    const val FLUSH = 3    // onFlush
    const val QIN = 4      // queueInput (per buffer, top) a=first b=fadeIn c=fadeOut d=flags(1=isPending,2=skipFade) e=crossfadeFrames
    const val SKIPBLK = 5  // skipFade zeroing block ran
    const val ARM = 6      // fade-in armed                a=crossfadeFrames(value set)
    const val FOS = 7      // onFadeOutStart               a=crossfadeFrames
    const val CANCEL = 8   // cancelFades()                a=fadeInBefore b=fadeOutBefore
    const val MIT = 9      // onMediaItemTransition        a=reason b=skipForAlbum
    const val SFO = 10     // scheduleFadeOut()            a=duration b=currentPos c=crossfadeDurationMs
    const val PDIS = 11    // onPositionDiscontinuity      a=reason

    private const val CAP = 2048           // power of two
    private const val MASK = CAP - 1
    private val ts = LongArray(CAP)
    private val tid = IntArray(CAP)
    private val ev = IntArray(CAP)
    private val f0 = LongArray(CAP); private val f1 = LongArray(CAP); private val f2 = LongArray(CAP)
    private val f3 = LongArray(CAP); private val f4 = LongArray(CAP)
    private val idx = AtomicInteger(0)
    @Volatile private var frozen = false
    private val dumped = AtomicBoolean(false)
    @Volatile private var triggerTs = 0L
    @Volatile @JvmField var lastContextName: String = "?"
    private val handler = Handler(Looper.getMainLooper())

    // Audio-thread-only boundary marker: set true in onConfigure, consumed on the next queueInput.
    @Volatile @JvmField var firstBufferPending = false

    // Cheap and allocation-free: safe to call on the audio thread per buffer.
    fun rec(event: Int, a: Long = 0, b: Long = 0, c: Long = 0, d: Long = 0, e: Long = 0) {
        if (frozen) return
        val i = idx.getAndIncrement() and MASK
        ts[i] = System.nanoTime()
        tid[i] = Thread.currentThread().id.toInt()
        ev[i] = event
        f0[i] = a; f1[i] = b; f2[i] = c; f3[i] = d; f4[i] = e
    }

    // Called from EffectsListener on the FIRST auto-advance transition. One-shot.
    fun triggerDumpOnAuto() {
        if (!dumped.compareAndSet(false, true)) return
        triggerTs = System.nanoTime()
        handler.postDelayed({ dump() }, 2000)
    }

    private data class Rec(
        val t: Long, val tid: Int, val ev: Int,
        val a: Long, val b: Long, val c: Long, val d: Long, val e: Long
    )

    private fun dump() {
        frozen = true                                    // stop audio-thread writes before reading
        val end = idx.get()
        val start = maxOf(0, end - CAP)
        val lo = triggerTs - 500_000_000L                // 0.5 s before the AUTO transition (seam pre-tail)
        val hi = triggerTs + 2_000_000_000L              // 2 s after
        val recs = (start until end).map { p ->
            val i = p and MASK
            Rec(ts[i], tid[i], ev[i], f0[i], f1[i], f2[i], f3[i], f4[i])
        }.filter { it.t in lo..hi }.sortedBy { it.t }
        val t0 = triggerTs
        val ctx = lastContextName
        Thread {
            Log.d("FADEDBG", "==== BEGIN  autoContext=$ctx  recs=${recs.size}  window=-0.5s..+2s ====")
            for (r in recs) {
                val us = (r.t - t0) / 1000L               // microseconds relative to the AUTO transition
                Log.d("FADEDBG", "us=%+d tid=%d %s".format(us, r.tid, fmt(r)))
            }
            Log.d("FADEDBG", "==== END ====")
        }.start()
    }

    private fun fmt(r: Rec): String = when (r.ev) {
        QES -> "QES     pendingBefore=${r.a} fadeIn=${r.b} fadeOut=${r.c} crossEnabled=${r.d} skipFade=${r.e}"
        CFG -> "CFG     encoding=${r.a} sampleRate=${r.b} crossfadeFrames=${r.c}"
        FLUSH -> "FLUSH"
        QIN -> "QIN     first=${r.a} fadeIn=${r.b} fadeOut=${r.c} flags(1=pend,2=skip)=${r.d} crossfadeFrames=${r.e}"
        SKIPBLK -> "SKIPBLK  <<< skipFade zeroing block EXECUTED"
        ARM -> "ARM     set fadeIn=${r.a}"
        FOS -> "FOS     set fadeOut=${r.a}"
        CANCEL -> "CANCEL  fadeInBefore=${r.a} fadeOutBefore=${r.b}"
        MIT -> "MIT     reason=${r.a} skipForAlbum=${r.b}"
        SFO -> "SFO     duration=${r.a} currentPos=${r.b} crossfadeMs=${r.c}"
        PDIS -> "PDIS    reason=${r.a}"
        else -> "?ev=${r.ev}"
    }
}
