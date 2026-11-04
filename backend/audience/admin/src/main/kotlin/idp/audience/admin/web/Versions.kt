package idp.audience.admin.web

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

private val ETAG = Regex("\"(\\d{1,18})\"")

internal fun etag(version: Long) = "\"$version\""

/** A save without the version it read could undo a change of another admin unseen, so it is refused rather than let through. */
internal fun versionOf(ifMatch: String?): Long {
    ifMatch ?: throw ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED)
    val version = ETAG.matchEntire(ifMatch.trim())?.groupValues?.get(1)
    return version?.toLong() ?: throw ResponseStatusException(HttpStatus.PRECONDITION_FAILED)
}
