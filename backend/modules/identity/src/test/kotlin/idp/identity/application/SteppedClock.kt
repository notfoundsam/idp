package idp.identity.application

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Stands still, so a delay of a second cannot run out under a slow test. */
internal class SteppedClock : Clock() {
    @Volatile var now: Instant = Instant.parse("2026-11-01T00:00:00Z")

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}
