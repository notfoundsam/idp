package idp.federation.adapters.persistence

import idp.db.jooq.tenant.tables.ServiceProviders.SERVICE_PROVIDERS
import idp.db.jooq.tenant.tables.SpAcsEndpoints.SP_ACS_ENDPOINTS
import idp.db.jooq.tenant.tables.records.ServiceProvidersRecord
import idp.federation.application.NameIdFormat
import idp.federation.application.SamlAttribute
import idp.federation.application.SignatureAlgorithm
import idp.federation.application.SignedPart
import idp.federation.application.SpSettings
import idp.federation.domain.AcsEndpoint
import idp.federation.domain.AcsUrl
import idp.federation.domain.ServiceProvider
import idp.sharedkernel.persistence.VersionedUpdate
import org.jooq.DSLContext
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

internal class ServiceProviders {
    private val versioned = VersionedUpdate(SERVICE_PROVIDERS, SERVICE_PROVIDERS.ID, SERVICE_PROVIDERS.VERSION)

    /** False when the tenant already has an SP with this EntityID: a Response must name one SP only. */
    fun add(
        dsl: DSLContext,
        sp: ServiceProvider,
        now: Instant,
        managed: Boolean = false,
    ): Boolean {
        val inserted =
            dsl
                .insertInto(SERVICE_PROVIDERS)
                .set(SERVICE_PROVIDERS.ID, sp.id)
                .set(SERVICE_PROVIDERS.ENTITY_ID, sp.entityId)
                .set(SERVICE_PROVIDERS.NAME, sp.name)
                .set(SERVICE_PROVIDERS.SIGNING_CERTIFICATES, sp.signingCertificates.toTypedArray())
                .set(SERVICE_PROVIDERS.AUTHN_REQUESTS_SIGNED, sp.authnRequestsSigned)
                .set(SERVICE_PROVIDERS.NAME_ID_FORMAT, sp.settings.nameIdFormat.name)
                .set(SERVICE_PROVIDERS.SIGNED_PART, sp.settings.signedPart.name)
                .set(SERVICE_PROVIDERS.SIGNATURE_ALGORITHM, sp.settings.signatureAlgorithm.name)
                .set(SERVICE_PROVIDERS.RELEASED_ATTRIBUTES, sp.settings.attributes.names())
                .set(SERVICE_PROVIDERS.CREATED_AT, now.atOffset(ZoneOffset.UTC))
                .set(SERVICE_PROVIDERS.MANAGED, managed)
                .onConflict(SERVICE_PROVIDERS.ENTITY_ID)
                .doNothing()
                .execute() == 1
        if (!inserted) return false
        sp.acs.forEach { endpoint ->
            dsl
                .insertInto(SP_ACS_ENDPOINTS)
                .set(SP_ACS_ENDPOINTS.SP_ID, sp.id)
                .set(SP_ACS_ENDPOINTS.ACS_INDEX, endpoint.index)
                .set(SP_ACS_ENDPOINTS.URL, endpoint.url.value)
                .set(SP_ACS_ENDPOINTS.IS_DEFAULT, endpoint.isDefault)
                .execute()
        }
        return true
    }

    fun find(
        dsl: DSLContext,
        id: UUID,
    ): ServiceProvider? =
        dsl
            .selectFrom(SERVICE_PROVIDERS)
            .where(SERVICE_PROVIDERS.ID.eq(id))
            .fetchOne()
            ?.let { toSp(dsl, it) }

    fun findByEntityId(
        dsl: DSLContext,
        entityId: String,
    ): ServiceProvider? =
        dsl
            .selectFrom(SERVICE_PROVIDERS)
            .where(SERVICE_PROVIDERS.ENTITY_ID.eq(entityId))
            .fetchOne()
            ?.let { toSp(dsl, it) }

    fun all(dsl: DSLContext): List<ServiceProvider> =
        dsl
            .selectFrom(SERVICE_PROVIDERS)
            .orderBy(SERVICE_PROVIDERS.NAME, SERVICE_PROVIDERS.ID)
            .fetch()
            .map { toSp(dsl, it) }

    fun save(
        dsl: DSLContext,
        sp: ServiceProvider,
    ): Long =
        versioned.execute(
            dsl,
            sp.id,
            sp.version,
            mapOf(
                SERVICE_PROVIDERS.NAME_ID_FORMAT to sp.settings.nameIdFormat.name,
                SERVICE_PROVIDERS.SIGNED_PART to sp.settings.signedPart.name,
                SERVICE_PROVIDERS.SIGNATURE_ALGORITHM to sp.settings.signatureAlgorithm.name,
                SERVICE_PROVIDERS.RELEASED_ATTRIBUTES to sp.settings.attributes.names(),
            ),
        )

    private fun Set<SamlAttribute>.names(): Array<String> = map { it.name }.sorted().toTypedArray()

    fun isManaged(
        dsl: DSLContext,
        id: UUID,
    ): Boolean =
        dsl
            .select(SERVICE_PROVIDERS.MANAGED)
            .from(SERVICE_PROVIDERS)
            .where(SERVICE_PROVIDERS.ID.eq(id))
            .fetchOne(SERVICE_PROVIDERS.MANAGED) == true

    /** Assignments and endpoints go with it by FK. */
    fun delete(
        dsl: DSLContext,
        id: UUID,
    ): Boolean = dsl.deleteFrom(SERVICE_PROVIDERS).where(SERVICE_PROVIDERS.ID.eq(id)).execute() == 1

    private fun toSp(
        dsl: DSLContext,
        row: ServiceProvidersRecord,
    ): ServiceProvider {
        val acs =
            dsl
                .selectFrom(SP_ACS_ENDPOINTS)
                .where(SP_ACS_ENDPOINTS.SP_ID.eq(row.id))
                .orderBy(SP_ACS_ENDPOINTS.ACS_INDEX)
                .fetch { AcsEndpoint(it.acsIndex!!, AcsUrl.of(it.url!!), it.isDefault!!) }
        return ServiceProvider(
            row.id!!,
            row.entityId!!,
            row.name!!,
            acs,
            row.signingCertificates!!.map { it!! },
            row.authnRequestsSigned!!,
            SpSettings(
                NameIdFormat.valueOf(row.nameIdFormat!!),
                SignedPart.valueOf(row.signedPart!!),
                SignatureAlgorithm.valueOf(row.signatureAlgorithm!!),
                row.releasedAttributes!!.map { SamlAttribute.valueOf(it!!) }.toSet(),
            ),
            row.version!!,
        )
    }
}
