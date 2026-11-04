package archfixtures.audienceresponses.audience.admin.web

import archfixtures.audienceresponses.identity.application.UserView
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

class UserResponse(
    val id: String,
)

class WrappingResponse(
    val users: List<UserView>,
)

@RestController
class Violation {
    fun user(): UserView = UserView("id")

    fun users(): ResponseEntity<List<UserView>> = ResponseEntity.ok(listOf(UserView("id")))

    fun wrapped(): WrappingResponse = WrappingResponse(emptyList())

    fun save(
        @RequestBody user: UserView,
    ) = Unit

    fun allowed(): ResponseEntity<UserResponse> = ResponseEntity.ok(UserResponse(UserView("id").id))
}
