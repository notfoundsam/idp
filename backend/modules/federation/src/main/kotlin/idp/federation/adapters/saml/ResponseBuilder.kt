package idp.federation.adapters.saml

import idp.federation.application.NameIdFormat
import idp.federation.application.SamlAttribute
import idp.federation.application.SamlSubject
import idp.federation.application.SignedPart
import idp.federation.application.SigningCredential
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport
import org.opensaml.core.xml.schema.XSString
import org.opensaml.saml.common.SAMLVersion
import org.opensaml.saml.saml2.core.Assertion
import org.opensaml.saml.saml2.core.Attribute
import org.opensaml.saml.saml2.core.AttributeStatement
import org.opensaml.saml.saml2.core.AttributeValue
import org.opensaml.saml.saml2.core.Audience
import org.opensaml.saml.saml2.core.AudienceRestriction
import org.opensaml.saml.saml2.core.AuthnContext
import org.opensaml.saml.saml2.core.AuthnContextClassRef
import org.opensaml.saml.saml2.core.AuthnStatement
import org.opensaml.saml.saml2.core.Conditions
import org.opensaml.saml.saml2.core.NameID
import org.opensaml.saml.saml2.core.StatusCode
import org.opensaml.saml.saml2.core.Subject
import org.opensaml.saml.saml2.core.SubjectConfirmation
import org.opensaml.saml.saml2.core.SubjectConfirmationData
import java.time.Duration
import java.time.Instant

/** Long enough for a slow network and a clock a little behind, short enough that a captured Response soon counts for nothing. */
private val LIFETIME: Duration = Duration.ofMinutes(5)

/** What a login tells one SP. [authnInstant] and [sessionNotOnOrAfter] come from the IdP session, [contextClass] from its factors. */
internal class Login(
    val issuer: String,
    val replyTo: ReplyTo,
    val subject: SamlSubject,
    val authnInstant: Instant,
    val sessionNotOnOrAfter: Instant,
    val contextClass: String,
)

/**
 * A Response with one bearer assertion for the checked ACS. The assertion holds only the audience of the SP, so another SP cannot
 * replay it, and only the attributes the SP was given. No SessionIndex: without Single Logout nothing would ever read it.
 */
internal object ResponseBuilder {
    fun write(
        login: Login,
        credential: SigningCredential,
        now: Instant,
    ): String {
        val sp = login.replyTo.sp
        val assertion =
            OpenSaml.build<Assertion>(Assertion.DEFAULT_ELEMENT_NAME).apply {
                id = samlId()
                version = SAMLVersion.VERSION_20
                issueInstant = now
                issuer = issuer(login.issuer)
                subject = subject(login, now)
                conditions = conditions(sp.entityId, now)
                authnStatements += authnStatement(login)
                if (login.subject.attributes.isNotEmpty()) attributeStatements += attributeStatement(login.subject.attributes)
            }
        val response = response(login.issuer, login.replyTo, status(StatusCode.SUCCESS), now).apply { assertions += assertion }
        val signed =
            when (sp.settings.signedPart) {
                SignedPart.ASSERTION -> listOf(assertion)
                SignedPart.RESPONSE -> listOf(response)
                SignedPart.BOTH -> listOf(assertion, response)
            }
        return signedBase64(response, signed, credential, sp.settings.signatureAlgorithm)
    }

    /** A persistent NameID names both parties, since it means something only between them. */
    private fun subject(
        login: Login,
        now: Instant,
    ): Subject =
        OpenSaml.build<Subject>(Subject.DEFAULT_ELEMENT_NAME).apply {
            nameID =
                OpenSaml.build<NameID>(NameID.DEFAULT_ELEMENT_NAME).apply {
                    value = login.subject.nameId
                    format = login.subject.nameIdFormat.uri
                    if (login.subject.nameIdFormat == NameIdFormat.PERSISTENT) {
                        nameQualifier = login.issuer
                        spNameQualifier = login.replyTo.sp.entityId
                    }
                }
            subjectConfirmations +=
                OpenSaml.build<SubjectConfirmation>(SubjectConfirmation.DEFAULT_ELEMENT_NAME).apply {
                    method = SubjectConfirmation.METHOD_BEARER
                    subjectConfirmationData =
                        OpenSaml.build<SubjectConfirmationData>(SubjectConfirmationData.DEFAULT_ELEMENT_NAME).apply {
                            recipient = login.replyTo.acsUrl
                            inResponseTo = login.replyTo.requestId
                            notOnOrAfter = now + LIFETIME
                        }
                }
        }

    private fun conditions(
        audience: String,
        now: Instant,
    ): Conditions =
        OpenSaml.build<Conditions>(Conditions.DEFAULT_ELEMENT_NAME).apply {
            notBefore = now
            notOnOrAfter = now + LIFETIME
            audienceRestrictions +=
                OpenSaml.build<AudienceRestriction>(AudienceRestriction.DEFAULT_ELEMENT_NAME).apply {
                    audiences += OpenSaml.build<Audience>(Audience.DEFAULT_ELEMENT_NAME).apply { uri = audience }
                }
        }

    private fun authnStatement(login: Login): AuthnStatement =
        OpenSaml.build<AuthnStatement>(AuthnStatement.DEFAULT_ELEMENT_NAME).apply {
            authnInstant = login.authnInstant
            sessionNotOnOrAfter = login.sessionNotOnOrAfter
            authnContext =
                OpenSaml.build<AuthnContext>(AuthnContext.DEFAULT_ELEMENT_NAME).apply {
                    authnContextClassRef =
                        OpenSaml.build<AuthnContextClassRef>(AuthnContextClassRef.DEFAULT_ELEMENT_NAME).apply { uri = login.contextClass }
                }
        }

    private fun attributeStatement(attributes: Map<SamlAttribute, String>): AttributeStatement =
        OpenSaml.build<AttributeStatement>(AttributeStatement.DEFAULT_ELEMENT_NAME).apply {
            this.attributes +=
                attributes.entries.sortedBy { it.key.ordinal }.map { (attribute, value) ->
                    OpenSaml.build<Attribute>(Attribute.DEFAULT_ELEMENT_NAME).apply {
                        name = attribute.oid
                        nameFormat = SamlAttribute.NAME_FORMAT
                        friendlyName = attribute.friendlyName
                        attributeValues += text(value)
                    }
                }
        }

    private fun text(value: String): XSString {
        OpenSaml.initialize()
        val builder = checkNotNull(XMLObjectProviderRegistrySupport.getBuilderFactory().getBuilder(XSString.TYPE_NAME))
        return (builder.buildObject(AttributeValue.DEFAULT_ELEMENT_NAME, XSString.TYPE_NAME) as XSString).also { it.value = value }
    }
}
