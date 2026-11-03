package idp.ops.security

import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers assertion IDs for as long as the assertion could be accepted. It lives in memory: only signed assertions of the one IdP
 * get here, so it stays small, and an assertion replayed to another instance still fails, because its InResponseTo names an
 * AuthnRequest kept in the session of the browser that started the login.
 */
class AssertionReplayCache(
    private val clock: Clock = Clock.systemUTC(),
) {
    private val seen = ConcurrentHashMap<String, Instant>()

    /** False when [id] came before and has not expired. */
    fun firstUse(
        id: String?,
        keepUntil: Instant,
    ): Boolean {
        if (id.isNullOrBlank()) return false
        val now = clock.instant()
        seen.entries.removeIf { !it.value.isAfter(now) }
        return seen.putIfAbsent(id, keepUntil) == null
    }
}
