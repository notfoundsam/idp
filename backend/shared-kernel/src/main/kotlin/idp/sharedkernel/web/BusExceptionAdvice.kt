package idp.sharedkernel.web

import idp.sharedkernel.security.AccessDeniedException
import idp.sharedkernel.throttle.TooManyRequestsException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

private const val MILLIS_PER_SECOND = 1000L

@RestControllerAdvice
class BusExceptionAdvice {
    @ExceptionHandler
    fun tooManyRequests(failure: TooManyRequestsException): ResponseEntity<Void> =
        ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, secondsUp(failure).toString())
            .build()

    @ExceptionHandler
    fun accessDenied(failure: AccessDeniedException): ResponseEntity<Void> =
        ResponseEntity.status(if (failure.authenticated) HttpStatus.FORBIDDEN else HttpStatus.UNAUTHORIZED).build()

    private fun secondsUp(failure: TooManyRequestsException): Long =
        maxOf(1, (failure.retryAfter.toMillis() + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND)
}
