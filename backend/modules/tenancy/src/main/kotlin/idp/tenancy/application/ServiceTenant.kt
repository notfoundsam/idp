package idp.tenancy.application

import idp.tenancy.domain.Subdomain

/** `ops` as an SP and `worker`, which registers it at the service tenant, derive the same addresses from the base domain. */
object ServiceTenant {
    const val REGISTRATION_ID = "ops-sso"
    const val ACS_PATH = "/login/saml2/sso/$REGISTRATION_ID"
    const val SP_METADATA_PATH = "/saml2/metadata"
    const val SP_NAME = "ops"

    fun idpHost(baseDomain: String): String = Subdomain.serviceTenant().host(baseDomain)

    fun opsHost(baseDomain: String): String = Subdomain.ops().host(baseDomain)

    fun spEntityId(baseDomain: String): String = "https://${opsHost(baseDomain)}$SP_METADATA_PATH"

    fun acsUrl(baseDomain: String): String = "https://${opsHost(baseDomain)}$ACS_PATH"
}
