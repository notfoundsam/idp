package idp.audience.admin.web

import idp.audience.admin.application.command.BlockUser
import idp.audience.admin.application.command.ChangeRole
import idp.audience.admin.application.command.EndAllSessions
import idp.audience.admin.application.command.EndUserSessions
import idp.audience.admin.application.command.EraseUser
import idp.audience.admin.application.command.InviteUser
import idp.audience.admin.application.command.ResendInvitation
import idp.audience.admin.application.command.ResetMfa
import idp.audience.admin.application.command.UnblockUser
import idp.audience.admin.application.query.FindUserByEmail
import idp.audience.admin.application.query.GetUserCard
import idp.audience.admin.application.query.UserCard
import idp.audit.application.AuditEntryView
import idp.sharedkernel.bus.CommandBus
import idp.sharedkernel.bus.QueryBus
import idp.sharedkernel.security.UserRole
import idp.sharedkernel.security.UserStatus
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** Not a data class: `toString` must not print the email. */
class InvitationRequest(
    val email: String,
    val role: UserRole,
)

data class RoleChangeRequest(
    val role: UserRole,
)

data class InvitedUserResponse(
    val id: UUID,
)

/** Not a data class: `toString` must not print the email. */
class UserSearchRequest(
    val email: String,
)

data class FoundUserResponse(
    val id: UUID,
    val role: UserRole,
    val status: UserStatus,
)

data class UserSearchResponse(
    val users: List<FoundUserResponse>,
)

data class AssignedServiceProviderResponse(
    val id: UUID,
    val entityId: String,
    val name: String,
)

/** The IP and the details of a record stay out of the card, see `docs/security/authorization.md`. */
data class UserEventResponse(
    val id: UUID,
    val occurredAt: Instant,
    val action: String,
    val actorType: ActorType,
    val actorId: UUID?,
)

/** Not a data class: `toString` must not print the email or the name. */
class UserCardResponse(
    val id: UUID,
    val email: String,
    val name: String?,
    val role: UserRole,
    val status: UserStatus,
    val serviceProviders: List<AssignedServiceProviderResponse>,
    val events: List<UserEventResponse>,
)

private fun AuditEntryView.response(): UserEventResponse {
    val (type, actorId) = actor.typeAndId()
    return UserEventResponse(id, occurredAt, action, type, actorId)
}

private fun UserCard.response() =
    UserCardResponse(
        user.id,
        email,
        name,
        user.role,
        user.status,
        serviceProviders.map { AssignedServiceProviderResponse(it.id, it.entityId, it.name) },
        events.map { it.response() },
    )

@RestController
@RequestMapping("/api/admin")
class AdminUsersController(
    private val commands: CommandBus,
    private val queries: QueryBus,
) {
    /** A body rather than a query string, so the address stays out of URLs and access logs. */
    @PostMapping("/users/search")
    fun search(
        @RequestBody request: UserSearchRequest,
    ): UserSearchResponse {
        val found = queries.ask(FindUserByEmail(request.email))
        return UserSearchResponse(listOfNotNull(found?.let { FoundUserResponse(it.id, it.role, it.status) }))
    }

    @GetMapping("/users/{id}")
    fun card(
        @PathVariable id: UUID,
    ): UserCardResponse = queries.ask(GetUserCard(id)).response()

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    fun invite(
        @RequestBody request: InvitationRequest,
    ) = InvitedUserResponse(commands.dispatch(InviteUser(request.email, request.role)))

    @PostMapping("/users/{id}/invitation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun resendInvitation(
        @PathVariable id: UUID,
    ) = commands.dispatch(ResendInvitation(id))

    @PutMapping("/users/{id}/role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun changeRole(
        @PathVariable id: UUID,
        @RequestBody request: RoleChangeRequest,
    ) = commands.dispatch(ChangeRole(id, request.role))

    @PostMapping("/users/{id}/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun block(
        @PathVariable id: UUID,
    ) = commands.dispatch(BlockUser(id))

    @PostMapping("/users/{id}/unblock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unblock(
        @PathVariable id: UUID,
    ) = commands.dispatch(UnblockUser(id))

    @PostMapping("/users/{id}/mfa-reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun resetMfa(
        @PathVariable id: UUID,
    ) = commands.dispatch(ResetMfa(id))

    @DeleteMapping("/users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun erase(
        @PathVariable id: UUID,
    ) = commands.dispatch(EraseUser(id))

    @DeleteMapping("/users/{id}/sessions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun endUserSessions(
        @PathVariable id: UUID,
    ) = commands.dispatch(EndUserSessions(id))

    @DeleteMapping("/sessions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun endAllSessions() = commands.dispatch(EndAllSessions())
}
