package com.learnanywhere.audio

/**
 * Utterance bookkeeping for [AudiobookPlayer]: which utterance id is speaking
 * what text, and whether a chain of utterances is still allowed to report
 * completion.
 *
 * Kept free of Android types so the interrupt rules — the part that is easy to
 * get subtly wrong and impossible to test without a device — run as JVM tests.
 *
 * Staleness is handled by forgetting rather than by a generation counter:
 * utterance ids are UUIDs, so an id dropped by [interrupt] can never be
 * confused with a live one, and a late callback from an abandoned engine queue
 * simply finds nothing. (The one path that deliberately re-speaks an id — the
 * Piper→system fallback — re-registers it, which is what we want.)
 *
 * Not thread-safe, and does not need to be: the player posts every engine
 * callback onto its single main-thread scope before touching this.
 */
class SpeechBookkeeper {

    /** Result of [claimChainEnd]. Non-null even when the caller wanted no callback. */
    class ChainEnd(val onDone: (() -> Unit)?)

    private val live = HashMap<String, String>()
    private var chainIds: Set<String> = emptySet()
    private var chainLastId: String? = null
    private var chainOnDone: (() -> Unit)? = null

    /** Registered-and-not-yet-retired count; asserted by tests so it stays bounded. */
    val liveCount: Int get() = live.size

    val chainArmed: Boolean get() = chainLastId != null

    /**
     * Supersede everything in flight. Pair this with a flush of the engine
     * queue: afterwards no already-registered utterance can publish text, and
     * a pending chain can no longer complete.
     */
    fun interrupt() {
        live.clear()
        clearChain()
    }

    fun register(id: String, text: String) {
        live[id] = text
    }

    /** Full text of [id] while it is live; null for a retired or superseded id. */
    fun textOf(id: String?): String? = if (id == null) null else live[id]

    /** Retires [id], bounding the registry. False means the callback was stale. */
    fun retire(id: String?): Boolean = id != null && live.remove(id) != null

    fun forget(id: String?) {
        if (id != null) live.remove(id)
    }

    /** Arms the chain [ids] were spoken as. A later [armChain] or [interrupt] replaces it. */
    fun armChain(ids: List<String>, onDone: (() -> Unit)?) {
        chainIds = ids.toSet()
        chainLastId = ids.lastOrNull()
        chainOnDone = onDone
    }

    /** True while [id] is part of the armed chain — i.e. more sentences may follow. */
    fun isChainMember(id: String?): Boolean = id != null && id in chainIds

    /**
     * Non-null exactly once, for the final utterance of a chain nothing has
     * superseded. Claiming disarms the chain, so a repeated engine callback
     * cannot fire the completion twice.
     */
    fun claimChainEnd(id: String?): ChainEnd? {
        if (id == null || id != chainLastId) return null
        val cb = chainOnDone
        clearChain()
        return ChainEnd(cb)
    }

    fun clearChain() {
        chainIds = emptySet()
        chainLastId = null
        chainOnDone = null
    }
}
