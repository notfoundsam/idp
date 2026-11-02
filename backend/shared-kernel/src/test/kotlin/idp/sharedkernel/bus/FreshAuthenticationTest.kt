package idp.sharedkernel.bus

import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.bus.decorators.SecurityDecorator
import idp.sharedkernel.security.Permission
import idp.sharedkernel.security.Principal
import idp.sharedkernel.security.ReauthenticationRequiredException
import idp.sharedkernel.security.RequiresFreshAuthentication
import idp.sharedkernel.security.Role
import idp.sharedkernel.security.SecurityConfig
import idp.sharedkernel.security.SecurityContext
import idp.sharedkernel.testing.MovableClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass

private val CHANGE = Permission("change")
private val MEMBER = Role("member")
private val WINDOW: Duration = Duration.ofMinutes(10)
private val USER = Actor.User(UUID.randomUUID())

private class ChangeEmail :
    Command<String>,
    RequiresFreshAuthentication

private class ChangeTheme : Command<String>

private object FreshConfig : SecurityConfig {
    override val permissions = mapOf<KClass<out Message<*>>, Permission?>(ChangeEmail::class to CHANGE, ChangeTheme::class to CHANGE)
    override val roles = mapOf(MEMBER to setOf(CHANGE))
}

private class Denials : AuditLog {
    val records = CopyOnWriteArrayList<AuditRecord>()

    override fun recordChange(record: AuditRecord) = error("A denial never joins a transaction")

    override fun recordDenial(record: AuditRecord) {
        records += record
    }
}

class FreshAuthenticationTest :
    FunSpec({
        val clock = MovableClock()
        val audit = Denials()
        val decorator = SecurityDecorator(listOf(FreshConfig), audit, clock, WINDOW)

        beforeTest { audit.records.clear() }

        fun send(
            message: Message<String>,
            principal: Principal,
        ) = SecurityContext.with(principal) { decorator.around(message) { "done" } }

        test("a login inside the window is enough") {
            send(ChangeEmail(), Principal(USER, MEMBER, clock.instant().minus(WINDOW).plusSeconds(1))) shouldBe "done"

            audit.records.shouldBeEmpty()
        }

        test("a login at the end of the window is not, and the refusal is audited") {
            val stale = Principal(USER, MEMBER, clock.instant().minus(WINDOW))

            shouldThrow<ReauthenticationRequiredException> { send(ChangeEmail(), stale) }

            audit.records.single().action shouldBe "access.reauthentication_required"
            audit.records.single().actor shouldBe USER
        }

        test("a principal that never logged in here does not count as fresh") {
            shouldThrow<ReauthenticationRequiredException> { send(ChangeEmail(), Principal(USER, MEMBER)) }
        }

        test("other messages do not care how old the login is") {
            send(ChangeTheme(), Principal(USER, MEMBER, clock.instant().minus(Duration.ofHours(8)))) shouldBe "done"
        }
    })
