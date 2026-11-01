package idp.audience.operator.web

import idp.audience.operator.application.command.ApproveApplication
import idp.audience.operator.application.command.ResumeTenant
import idp.audience.operator.application.command.SubmitApplication
import idp.audience.operator.application.command.SuspendTenant
import idp.audience.operator.application.query.ApplicationResponse
import idp.audience.operator.application.query.GetApplication
import idp.audience.operator.application.query.GetTenant
import idp.audience.operator.application.query.TenantResponse
import idp.sharedkernel.bus.CommandBus
import idp.sharedkernel.bus.QueryBus
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Not a data class: `toString` must not print the contact. */
class SubmitApplicationRequest(
    val companyName: String,
    val subdomain: String,
    val contact: String,
)

data class ApplicationCreatedResponse(
    val id: UUID,
)

@RestController
@RequestMapping("/api")
class TenantOpsController(
    private val commands: CommandBus,
    private val queries: QueryBus,
) {
    @PostMapping("/applications")
    @ResponseStatus(HttpStatus.CREATED)
    fun submit(
        @RequestBody request: SubmitApplicationRequest,
    ) = ApplicationCreatedResponse(commands.dispatch(SubmitApplication(request.companyName, request.subdomain, request.contact)))

    @GetMapping("/applications/{id}")
    fun application(
        @PathVariable id: UUID,
    ): ApplicationResponse = queries.ask(GetApplication(id))

    @PostMapping("/applications/{id}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun approve(
        @PathVariable id: UUID,
    ) = commands.dispatch(ApproveApplication(id))

    @GetMapping("/tenants/{id}")
    fun tenant(
        @PathVariable id: UUID,
    ): TenantResponse = queries.ask(GetTenant(id))

    @PostMapping("/tenants/{id}/suspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun suspend(
        @PathVariable id: UUID,
    ) = commands.dispatch(SuspendTenant(id))

    @PostMapping("/tenants/{id}/resume")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun resume(
        @PathVariable id: UUID,
    ) = commands.dispatch(ResumeTenant(id))
}
