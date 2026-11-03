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
import idp.sharedkernel.bus.CommandBus
import idp.sharedkernel.security.UserRole
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
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

@RestController
@RequestMapping("/api/admin")
class AdminUsersController(
    private val commands: CommandBus,
) {
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
