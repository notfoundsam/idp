package idp.sharedkernel.outbox

import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.util.UUID

/** Releases overlap, so a handler can receive an event written by an older or a newer version of the code. */
internal val eventJson: JsonMapper =
    JsonMapper
        .builder()
        .addModule(KotlinModule.Builder().build())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

data class OutboxEvent(
    val id: UUID,
    val tenantId: UUID?,
    val aggregateType: String,
    val aggregateId: UUID,
    val aggregateVersion: Long,
    val eventType: String,
    val correlationId: UUID,
    val causationId: UUID?,
    val attempts: Int,
    val json: String,
) {
    fun <T : Any> payload(type: Class<T>): T = eventJson.readValue(json, type)

    inline fun <reified T : Any> payload(): T = payload(T::class.java)
}

enum class EventScope {
    /** Runs in a transaction of the event's tenant, and `processed_events` in that schema makes it idempotent. */
    TENANT,

    /**
     * For events before the tenant schema exists and after it is dropped: provisioning and offboarding.
     * No tenant context, no transaction and no `processed_events`, because there may be no schema to hold them.
     * The handler is idempotent on its own: it records each finished step in `platform.tenants` and resumes from there.
     */
    PLATFORM,
}

/** Delivery is at least once and in no fixed order: order by `aggregateVersion` where it matters. */
interface EventHandler {
    /** Stored in `processed_events`, so renaming a handler delivers its past events again. */
    val name: String
    val eventType: String
    val scope: EventScope

    fun handle(event: OutboxEvent)
}
