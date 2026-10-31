package idp.sharedkernel.testing

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class MovableClock : Clock() {
    @Volatile
    var offset: Duration = Duration.ZERO

    override fun instant(): Instant = Instant.now().plus(offset)

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}
