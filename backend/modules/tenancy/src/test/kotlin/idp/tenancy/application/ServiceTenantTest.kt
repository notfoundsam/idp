package idp.tenancy.application

import idp.tenancy.domain.InvalidSubdomain
import idp.tenancy.domain.Subdomain
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ServiceTenantTest :
    FunSpec({
        test("ops and its IdP live on the reserved hosts of the base domain") {
            ServiceTenant.idpHost("idp.test") shouldBe "ops-sso.idp.test"
            ServiceTenant.opsHost("idp.test") shouldBe "ops.idp.test"
            ServiceTenant.spEntityId("idp.test") shouldBe "https://ops.idp.test/saml2/metadata"
            ServiceTenant.acsUrl("idp.test") shouldBe "https://ops.idp.test/login/saml2/sso/ops-sso"
        }

        test("no tenant can apply for the host of ops") {
            shouldThrow<InvalidSubdomain> { Subdomain.of("ops") }
        }
    })
