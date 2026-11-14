package idp.audience.admin.web

import idp.sharedkernel.persistence.VersionConflictException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

private val ETAG = Regex("\"(\\d{1,18})\"")

internal fun etag(version: Long) = "\"$version\""

/** A save without the version it read could undo a change of another admin unseen, so it is refused rather than let through. */
internal fun versionOf(ifMatch: String?): Long {
    ifMatch ?: throw ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED)
    val version = ETAG.matchEntire(ifMatch.trim())?.groupValues?.get(1)
    return version?.toLong() ?: throw ResponseStatusException(HttpStatus.PRECONDITION_FAILED)
}

/** The version came in `If-Match`, so losing to a change made meanwhile fails that precondition. Ahead of the 409 of the bus. */
@RestControllerAdvice(assignableTypes = [AdminPolicyController::class, AdminServiceProvidersController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class IfMatchAdvice {
    @ExceptionHandler(VersionConflictException::class)
    fun stale(failure: VersionConflictException): ResponseEntity<Void> = ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).build()
}
