package com.learnanywhere

import com.learnanywhere.agent.Backend
import com.learnanywhere.agent.ModelFailover
import com.learnanywhere.agent.Route
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JVM tests for the model failover policy (DESIGN §8): the chain the user's
 * Settings chip implies, which HTTP failures are another model's problem, and
 * how long a burnt model stays out of rotation. No HTTP — [com.learnanywhere.agent.Gemini]
 * only walks the chain this class hands it.
 */
class ModelFailoverTest {

    private val pacific = ZoneId.of("America/Los_Angeles")

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun ids(routes: List<Route>) = routes.map { it.model }

    // ----------------------------------------------------------------
    // Chain derivation
    // ----------------------------------------------------------------

    /** The user's chip is always tried first; the rest follow in capability order. */
    @Test
    fun preferredModelLeadsTheChain() {
        assertEquals(
            listOf("gemini-flash-lite-latest", "gemini-3.8-flash", "gemini-3.6-flash"),
            ids(ModelFailover.chain("gemini-flash-lite-latest"))
        )
    }

    /** A preferred model that is also a fallback appears ONCE, at the front. */
    @Test
    fun chainDedupesThePreferredModel() {
        val c = ids(ModelFailover.chain("gemini-3.6-flash"))
        assertEquals(listOf("gemini-3.6-flash", "gemini-3.8-flash", "gemini-flash-lite-latest"), c)
        assertEquals(c.size, c.toSet().size, "no model may appear twice in the chain")
    }

    /** Blank prefs (or an id we no longer offer) still yield the three fallbacks. */
    @Test
    fun blankOrUnknownPreferredStillHasFallbacks() {
        assertEquals(ModelFailover.CAPABILITY_ORDER, ids(ModelFailover.chain("")))
        assertEquals(ModelFailover.CAPABILITY_ORDER, ids(ModelFailover.chain("   ")))
        assertEquals(
            listOf("gemini-2.5-flash") + ModelFailover.CAPABILITY_ORDER,
            ids(ModelFailover.chain("gemini-2.5-flash"))
        )
    }

    /** Every rung is a (backend, model) pair — an OpenAI backend slots in later. */
    @Test
    fun chainEntriesCarryTheirBackend() {
        assertTrue(ModelFailover.chain("gemini-3.8-flash").all { it.backend == Backend.GEMINI })
    }

    // ----------------------------------------------------------------
    // Failure classification
    // ----------------------------------------------------------------

    /** 503 = the model's capacity pool, not ours: another model can serve it. */
    @Test
    fun overloadIsWorthAnotherModel() {
        assertEquals(ModelFailover.Failure.OVERLOADED,
            ModelFailover.classify(503, "{\"error\":{\"code\":503,\"message\":\"high demand\"}}"))
    }

    /** RPD exhaustion is PER-MODEL — a sibling still has its own daily budget. */
    @Test
    fun dailyQuotaIsWorthAnotherModel() {
        val body = "{\"error\":{\"code\":429,\"details\":[{\"quotaId\":" +
                "\"GenerateRequestsPerDayPerProjectPerModel-FreeTier\"}]}}"
        assertEquals(ModelFailover.Failure.DAILY_QUOTA, ModelFailover.classify(429, body))
        assertEquals(ModelFailover.Failure.DAILY_QUOTA,
            ModelFailover.classify(429, "quota exceeded: 50 requests per day"))
    }

    /**
     * A bare 429 (no RetryInfo, no PerDay) is permanent for this request
     * SHAPE, not for the model — free-tier search grounding is the live
     * example. Failing over would burn the next model on the same shape.
     */
    @Test
    fun bare429DoesNotFailOver() {
        assertEquals(ModelFailover.Failure.NONE,
            ModelFailover.classify(429, "{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\"}}"))
        assertEquals(ModelFailover.Failure.NONE, ModelFailover.classify(429, ""))
    }

    /** Our bug or the user's key — no other model fixes those. */
    @Test
    fun clientErrorsAndTransportFailuresDoNotFailOver() {
        listOf(400, 403, 404, 200, 0, 500).forEach { code ->
            assertEquals(ModelFailover.Failure.NONE, ModelFailover.classify(code, "whatever"),
                "HTTP $code must not trigger failover")
        }
    }

    // ----------------------------------------------------------------
    // Stickiness
    // ----------------------------------------------------------------

    /** A 503'd model drops to the back of the chain for five minutes, then returns. */
    @Test
    fun overloadStickinessExpiresAfterFiveMinutes() {
        var now = at("2026-09-24T10:00:00Z")
        val fo = ModelFailover { now }
        val preferred = "gemini-3.8-flash"
        val route = Route(model = preferred)

        assertEquals(preferred, ids(fo.plan(preferred)).first())
        fo.markUnavailable(route, ModelFailover.Failure.OVERLOADED)

        // Still in the chain — never dropped — but last, so the next turn
        // does not re-probe a pool Google is shedding.
        assertEquals(listOf("gemini-3.6-flash", "gemini-flash-lite-latest", preferred),
            ids(fo.plan(preferred)))
        assertFalse(fo.isAvailable(route))

        now += ModelFailover.STICKY_MS - 1
        assertEquals("gemini-3.6-flash", ids(fo.plan(preferred)).first(),
            "still sticky one millisecond before the window closes")

        now += 1
        assertTrue(fo.isAvailable(route))
        assertEquals(preferred, ids(fo.plan(preferred)).first(),
            "the preferred model is probed again naturally once the window expires")
    }

    /** Quota blocks until the NEXT Pacific midnight, not for a fixed few minutes. */
    @Test
    fun dailyQuotaBlocksUntilPacificMidnight() {
        // 23:30 Pacific on a summer (PDT, UTC-7) evening.
        var now = at("2026-09-25T06:30:00Z")
        assertEquals(23, ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), pacific).hour)
        val fo = ModelFailover { now }
        val preferred = "gemini-3.8-flash"
        fo.markUnavailable(Route(model = preferred), ModelFailover.Failure.DAILY_QUOTA)

        assertEquals("gemini-3.6-flash", ids(fo.plan(preferred)).first())
        // Well past the five-minute 503 window, still blocked.
        now += 20 * 60 * 1000L
        assertEquals("gemini-3.6-flash", ids(fo.plan(preferred)).first())
        // 00:01 Pacific the next day: the RPD counter has rolled over.
        now = at("2026-09-25T07:01:00Z")
        assertEquals(preferred, ids(fo.plan(preferred)).first())
    }

    /** Midnight is computed in wall-clock Pacific, so DST shifts the UTC instant. */
    @Test
    fun pacificMidnightFollowsDaylightSaving() {
        // PDT (UTC-7): midnight Pacific is 07:00 UTC.
        assertEquals(at("2026-09-25T07:00:00Z"),
            ModelFailover.nextPacificMidnight(at("2026-09-24T18:00:00Z")))
        // PST (UTC-8), after the November fall-back: midnight is 08:00 UTC.
        assertEquals(at("2026-12-01T08:00:00Z"),
            ModelFailover.nextPacificMidnight(at("2026-11-30T18:00:00Z")))
        // The spring-forward day itself (transition is at 02:00, not midnight).
        assertEquals(at("2027-03-14T08:00:00Z"),
            ModelFailover.nextPacificMidnight(at("2027-03-13T20:00:00Z")))
        // Just BEFORE midnight Pacific rolls over, the answer is tonight's.
        assertEquals(at("2026-09-25T07:00:00Z"),
            ModelFailover.nextPacificMidnight(at("2026-09-25T06:59:59Z")))
    }

    /** A late 503 must never shorten a quota block that runs past it. */
    @Test
    fun theLongerBlockWins() {
        var now = at("2026-09-24T18:00:00Z")   // 11:00 Pacific
        val fo = ModelFailover { now }
        val route = Route(model = "gemini-3.8-flash")
        fo.markUnavailable(route, ModelFailover.Failure.DAILY_QUOTA)
        fo.markUnavailable(route, ModelFailover.Failure.OVERLOADED)
        now += 10 * 60 * 1000L
        assertFalse(fo.isAvailable(route), "quota block must survive a later 503")
    }

    /** NONE is not a block — classification and marking agree. */
    @Test
    fun markingANonFailoverFailureDoesNothing() {
        val fo = ModelFailover { at("2026-09-24T18:00:00Z") }
        val route = Route(model = "gemini-3.8-flash")
        fo.markUnavailable(route, ModelFailover.Failure.NONE)
        assertTrue(fo.isAvailable(route))
    }

    /** Everything burnt: the chain is still walked in order, so a real error surfaces. */
    @Test
    fun allModelsBlockedStillYieldsTheFullChainInOrder() {
        val fo = ModelFailover { at("2026-09-24T18:00:00Z") }
        val preferred = "gemini-3.6-flash"
        ModelFailover.chain(preferred).forEach {
            fo.markUnavailable(it, ModelFailover.Failure.OVERLOADED)
        }
        assertEquals(ids(ModelFailover.chain(preferred)), ids(fo.plan(preferred)))
    }

    /** The chip can change between turns; the chain is derived per request. */
    @Test
    fun switchingChipsReordersTheChainWithoutLosingBlocks() {
        val fo = ModelFailover { at("2026-09-24T18:00:00Z") }
        fo.markUnavailable(Route(model = "gemini-3.8-flash"), ModelFailover.Failure.OVERLOADED)
        assertEquals(listOf("gemini-3.6-flash", "gemini-flash-lite-latest", "gemini-3.8-flash"),
            ids(fo.plan("gemini-3.8-flash")))
        assertEquals(listOf("gemini-flash-lite-latest", "gemini-3.6-flash", "gemini-3.8-flash"),
            ids(fo.plan("gemini-flash-lite-latest")),
            "the new chip leads, the burnt model still trails")
    }

    // ----------------------------------------------------------------
    // Concurrency
    // ----------------------------------------------------------------

    /** Turns run on concurrent coroutines: parallel marking must not corrupt state. */
    @Test
    fun concurrentMarkingIsSafe() {
        var now = at("2026-09-24T18:00:00Z")
        val fo = ModelFailover { now }
        val routes = ModelFailover.chain("gemini-3.8-flash")
        val threads = (1..8).map { n ->
            Thread {
                repeat(500) {
                    val r = routes[(n + it) % routes.size]
                    fo.markUnavailable(r, if (it % 2 == 0) ModelFailover.Failure.OVERLOADED
                                          else ModelFailover.Failure.DAILY_QUOTA)
                    fo.plan("gemini-3.8-flash")
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Every route saw a quota mark, so all are blocked past the 503 window
        // and until Pacific midnight — and the chain is still exactly three.
        routes.forEach { assertFalse(fo.isAvailable(it)) }
        assertEquals(3, fo.plan("gemini-3.8-flash").size)
        now = ModelFailover.nextPacificMidnight(now)
        routes.forEach { assertTrue(fo.isAvailable(it), "blocks expire cleanly after the storm") }
    }
}
