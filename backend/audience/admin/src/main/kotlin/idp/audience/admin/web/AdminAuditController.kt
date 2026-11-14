package idp.audience.admin.web

import idp.audience.admin.application.query.ReadJournal
import idp.audit.application.AuditCursor
import idp.audit.application.AuditEntryView
import idp.sharedkernel.audit.AuditTarget
import idp.sharedkernel.bus.QueryBus
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

private const val DEFAULT_PAGE = 50
private const val MAX_PAGE = 100

enum class AuditTargetType(
    internal val stored: String,
) {
    USER(AuditTarget.USER),
    TENANT(AuditTarget.TENANT),
    SERVICE_PROVIDER(AuditTarget.SERVICE_PROVIDER),
    SIGNING_KEY(AuditTarget.SIGNING_KEY),
}

/** `ip` and `details` are null once the key of the actor is destroyed, and the fact stays. */
data class AuditEntryResponse(
    val id: UUID,
    val occurredAt: Instant,
    val action: String,
    val actorType: ActorType,
    val actorId: UUID?,
    val targetType: AuditTargetType?,
    val targetId: UUID?,
    val ip: String?,
    val details: Map<String, String>?,
)

data class AuditCursorResponse(
    val before: Instant,
    val beforeId: UUID,
)

data class AuditJournalResponse(
    val entries: List<AuditEntryResponse>,
    val next: AuditCursorResponse?,
)

private fun AuditEntryView.response(): AuditEntryResponse {
    val (actorType, actorId) = actor.typeAndId()
    return AuditEntryResponse(
        id,
        occurredAt,
        action,
        actorType,
        actorId,
        target?.let { target -> AuditTargetType.entries.single { it.stored == target.type } },
        target?.id,
        ip,
        details,
    )
}

/** A value that comes only with its pair would be ignored alone, so it is refused instead. */
private fun <A, B> both(
    first: A?,
    second: B?,
): Pair<A, B>? {
    if ((first == null) != (second == null)) throw ResponseStatusException(HttpStatus.BAD_REQUEST)
    return if (first != null && second != null) first to second else null
}

@RestController
@RequestMapping("/api/admin")
class AdminAuditController(
    private val queries: QueryBus,
) {
    /** One more record than asked tells whether there is a next page. */
    @GetMapping("/audit")
    fun journal(
        @RequestParam("limit", required = false) limit: Int?,
        @RequestParam("before", required = false) before: Instant?,
        @RequestParam("beforeId", required = false) beforeId: UUID?,
        @RequestParam("targetType", required = false) targetType: AuditTargetType?,
        @RequestParam("targetId", required = false) targetId: UUID?,
    ): AuditJournalResponse {
        val size = limit ?: DEFAULT_PAGE
        if (size !in 1..MAX_PAGE) throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        val cursor = both(before, beforeId)?.let { (time, id) -> AuditCursor(time, id) }
        val target = both(targetType, targetId)?.let { (type, id) -> AuditTarget(type.stored, id) }
        val page = queries.ask(ReadJournal(size + 1, cursor, target))
        val entries = page.take(size)
        val next = if (page.size > size) entries.last().let { AuditCursorResponse(it.occurredAt, it.id) } else null
        return AuditJournalResponse(entries.map { it.response() }, next)
    }
}
