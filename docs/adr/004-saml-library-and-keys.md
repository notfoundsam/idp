# ADR-004: SAML library and signing keys

## Status

Accepted

## Decision

The `federation` context reads and writes SAML with OpenSAML 5, which signs through Apache Santuario.

- **Repository:** OpenSAML is published only to the Shibboleth repository. `settings.gradle.kts` adds it as exclusive content for the groups `org.opensaml` and `net.shibboleth`, so no other dependency resolves from there.
- **Parsing:** XML from outside goes through the OpenSAML parser pool, which refuses a DOCTYPE and XInclude, and is then validated against the SAML schemas shipped in OpenSAML before it is unmarshalled.
- **EntityID:** `https://<tenant host>/saml/metadata`, fixed at provisioning in `saml_identity`. A later change of the tenant's domains leaves it as it is.
- **Signing key:** RSA-3072 per tenant, created by the `saml-keys` provisioning step. Its PKCS#8 form is stored encrypted with a key derived from the tenant key, beside a self-signed certificate valid for 5 years.
- **Rotation:** the tenant admin starts a rotation, which publishes a second key in metadata without signing with it. Completing the rotation deletes the old key and lets the new one sign.
- **Algorithms:** RSA-PSS with SHA-256 by default. `rsa-sha256` is a setting of one SP, chosen by an admin and audited. SHA-1 has no setting at all.
- **SP settings:** a new SP gets a persistent NameID, a signed assertion and no attributes. Metadata never picks a setting: the email as NameID, a signed Response, the released attributes and the legacy algorithm are an admin's choice.
- **NameID:** the persistent NameID is a random value stored per user and SP in `subject_identifiers`, not derived from the user.

## Rationale

OpenSAML is the implementation behind the Shibboleth IdP and Spring Security SAML, it has a long record of fixes for signature wrapping and XML attacks, and it covers metadata, AuthnRequest and Response with one object model. Writing that layer on the JDK alone would repeat work where mistakes are known to be costly. The extra repository is the price, and exclusive content keeps it from serving anything else.

A schema check after a parse that refuses DTDs means hostile structure fails before any field is read, and the importer only ever sees the shape it expects.

SPs store the EntityID and the certificate of the IdP, so both must stay stable on their own schedule. Tying the EntityID to the host at provisioning keeps it a readable URL while making it independent of later domain changes. Publishing the next key before it signs lets every SP that reads metadata learn it in advance, and SPs configured by hand get the time between the two steps.

RSA-3072 matches the 128-bit level of the rest of the system and every SAML product verifies RSA, while ECDSA support among SPs is uneven. RSA-PSS is what ASVS Appendix C approves for RSA. Many SPs verify only `rsa-sha256`, so it stays available where an admin asks for it and the journal shows where. SimpleSAMLphp 2.5.3 is one of them.

A stored random NameID survives a change of email and cannot be computed by anyone who learns the user ID or the key of the tenant, which a derived value could. The cost is a row per user and SP, written at the first login.
