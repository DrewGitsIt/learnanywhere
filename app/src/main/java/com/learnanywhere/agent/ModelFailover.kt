package com.learnanywhere.agent

import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/** Which API shape a route speaks. Gemini only today (DESIGN.md §8). */
enum class Backend { GEMINI }

/**
 * One rung of the failover chain. Deliberately a (backend, model) pair rather
 * than a bare id: a later round adds an OpenAI-shaped backend and the chain
 * entries must not need reshaping then.
 */
data class Route(val backend: Backend = Backend.GEMINI, val model: String)

/**
 * Model failover policy (DESIGN.md §8) — pure, clock-injected, thread-safe,
 * and free of HTTP so the interesting behavior is JVM-testable.
 *
 * Two free-tier failures are worth another model rather than an error:
 *  - **503 "high demand"**, after [Gemini]'s own in-model retries are spent —
 *    Google shedding traffic from that model's capacity pool.
 *  - **daily-quota 429** (RPD), which is PER-MODEL and resets at midnight
 *    Pacific, so a sibling model still has its own budget.
 * Everything else keeps today's behavior: a bare 429 (no RetryInfo) is
 * permanent for that *request shape*, not the model — free-tier search
 * grounding is the live example, and it is handled by dropping the tool;
 * 400/403/404 and key errors are our bug or the user's; non-HTTP network
 * failures are the phone's.
 *
 * A failed model is marked unavailable for a while so consecutive turns stop
 * re-probing it: 503s stick for [STICKY_MS], quota until the next Pacific
 * midnight. The stickiness is about the prompt cache as much as latency —
 * Gemini's implicit cache is per-model, so alternating models turn every turn
 * into a cache miss (§5).
 */
class ModelFailover(private val now: () -> Long = { System.currentTimeMillis() }) {

    /** What a failed request says about the model that served it. */
    enum class Failure { NONE, OVERLOADED, DAILY_QUOTA }

    /** route -> epoch-ms at which it may be tried again. */
    private val blockedUntil = ConcurrentHashMap<Route, Long>()

    /**
     * The chain to walk for this request, freshest first: every known route,
     * with the ones currently marked unavailable pushed to the back (order
     * otherwise preserved). Nothing is ever dropped — when everything is
     * blocked the caller still makes a real attempt in chain order and the
     * server's own error surfaces, instead of a made-up local one.
     */
    fun plan(preferred: String): List<Route> {
        val t = now()
        val (up, down) = chain(preferred).partition { (blockedUntil[it] ?: 0L) <= t }
        return up + down
    }

    fun isAvailable(route: Route): Boolean = (blockedUntil[route] ?: 0L) <= now()

    /**
     * Note that [route] just failed with [kind]. Called from concurrent
     * coroutines: `merge` is atomic and keeps the LATER deadline, so a 503
     * landing after a quota block can never shorten it.
     */
    fun markUnavailable(route: Route, kind: Failure) {
        val until = when (kind) {
            Failure.OVERLOADED -> now() + STICKY_MS
            Failure.DAILY_QUOTA -> nextPacificMidnight(now())
            Failure.NONE -> return
        }
        blockedUntil.merge(route, until) { a, b -> maxOf(a, b) }
    }

    /** Test/diagnostic hook: forget all blocks. */
    fun reset() = blockedUntil.clear()

    companion object {
        /**
         * Fallback order after the user's chip, most capable first. These are
         * exactly the ids SettingsSheet offers — no id the user cannot also
         * pick deliberately.
         */
        val CAPABILITY_ORDER = listOf(
            "gemini-3.8-flash",
            "gemini-3.6-flash",
            "gemini-flash-lite-latest"
        )

        /** How long a 503'd model is left alone. Short: capacity comes back. */
        const val STICKY_MS = 5 * 60 * 1000L

        /** The user's model first, then the rest in capability order, deduped. */
        fun chain(preferred: String): List<Route> {
            val ids = LinkedHashSet<String>()
            preferred.trim().takeIf { it.isNotBlank() }?.let { ids.add(it) }
            ids.addAll(CAPABILITY_ORDER)
            return ids.map { Route(Backend.GEMINI, it) }
        }

        /** Classify an HTTP failure. Only these two are another model's problem. */
        fun classify(code: Int, body: String): Failure = when {
            code == 503 -> Failure.OVERLOADED
            code == 429 && isDailyQuota(body) -> Failure.DAILY_QUOTA
            else -> Failure.NONE
        }

        /** Next 00:00 America/Los_Angeles after [atMillis] — DST-correct. */
        fun nextPacificMidnight(atMillis: Long): Long {
            val zone = ZoneId.of("America/Los_Angeles")
            return Instant.ofEpochMilli(atMillis).atZone(zone)
                .toLocalDate().plusDays(1).atStartOfDay(zone)
                .toInstant().toEpochMilli()
        }
    }
}
