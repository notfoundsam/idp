package idp.worker.handlers

import idp.authentication.application.AuthenticationService
import idp.federation.application.FederationService
import idp.federation.application.NameIdFormat
import idp.federation.application.SpSettings
import idp.identity.application.IdentityService
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.outbox.EventHandler
import idp.sharedkernel.outbox.EventScope
import idp.sharedkernel.outbox.OutboxEvent
import idp.tenancy.application.ServiceTenant
import idp.tenancy.application.TenancyEvents

/**
 * Makes the service tenant the IdP of `ops`, which cannot reach a tenant schema itself. The event comes with the approval, so it can
 * arrive before provisioning ends: until the first admin exists this fails and the outbox brings it back. Every step is safe to repeat,
 * and an SP found by its EntityID is flagged as managed too, since one registered before the flag existed has none.
 * The email is the NameID, so the operator journal names a person.
 */
class OpsServiceProviderHandler(
    private val federation: FederationService,
    private val authentication: AuthenticationService,
    private val identity: IdentityService,
    private val baseDomain: String,
) : EventHandler {
    override val name: String = "ops-service-provider"
    override val eventType: String = TenancyEvents.SERVICE_TENANT_BOOTSTRAPPED
    override val scope: EventScope = EventScope.TENANT

    override fun handle(event: OutboxEvent) {
        val operator = checkNotNull(identity.firstAdmin()) { "The service tenant of event ${event.id} is still being provisioned" }
        val sp = federation.serviceProviderByEntityId(ServiceTenant.spEntityId(baseDomain))?.id ?: register()
        federation.manageServiceProvider(sp, Actor.System)
        authentication.requirePasskeys(Actor.System)
        federation.assign(sp, listOf(operator), Actor.System)
    }

    private fun register() =
        federation.registerServiceProvider(ServiceTenant.SP_NAME, metadata().toByteArray(), Actor.System, managed = true).also { id ->
            federation.changeSettings(id, SpSettings(nameIdFormat = NameIdFormat.EMAIL), Actor.System)
        }

    /** `ops` signs no request: the IdP sends the Response only to this ACS, and `ops` checks what it receives. */
    private fun metadata() =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <md:EntityDescriptor xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata" entityID="${ServiceTenant.spEntityId(baseDomain)}">
          <md:SPSSODescriptor protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol" AuthnRequestsSigned="false"
              WantAssertionsSigned="true">
            <md:AssertionConsumerService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST"
                Location="${ServiceTenant.acsUrl(baseDomain)}" index="0" isDefault="true"/>
          </md:SPSSODescriptor>
        </md:EntityDescriptor>
        """.trimIndent()
}
