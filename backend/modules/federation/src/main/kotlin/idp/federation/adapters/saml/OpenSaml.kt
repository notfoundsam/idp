package idp.federation.adapters.saml

import net.shibboleth.shared.xml.ParserPool
import net.shibboleth.shared.xml.impl.BasicParserPool
import org.opensaml.core.config.InitializationService
import org.opensaml.core.xml.XMLObject
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport
import org.opensaml.core.xml.util.XMLObjectSupport
import org.opensaml.saml.common.xml.SAMLSchemaBuilder
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.namespace.QName
import javax.xml.transform.dom.DOMSource
import javax.xml.validation.Schema

/** OpenSAML keeps its registry in a global, so it is set up once per JVM. */
internal object OpenSaml {
    private val initialized: Unit by lazy { InitializationService.initialize() }

    /** Refuses a DOCTYPE, so neither external entities nor entity expansion reach the parser. */
    private val parsers: ParserPool by lazy { BasicParserPool().apply { initialize() } }

    private val schema: Schema by lazy { SAMLSchemaBuilder(SAMLSchemaBuilder.SAML1Version.SAML_11).samlSchema }

    fun parse(xml: ByteArray): Document = parsers.parse(ByteArrayInputStream(xml))

    fun validate(document: Document) {
        val validator = schema.newValidator()
        validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        validator.validate(DOMSource(document))
    }

    fun unmarshall(element: Element): XMLObject? {
        initialize()
        return XMLObjectProviderRegistrySupport.getUnmarshallerFactory().getUnmarshaller(element)?.unmarshall(element)
    }

    inline fun <reified T : XMLObject> build(name: QName): T {
        initialize()
        return XMLObjectSupport.buildXMLObject(name) as T
    }

    fun initialize() = initialized
}
