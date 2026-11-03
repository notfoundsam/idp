<?php

// The test SP of the local environment and of SamlInteropTest. It asks for the persistent NameID the IdP gives by default.
$config = [
    'admin' => [
        'core:AdminPassword',
    ],

    'default-sp' => [
        'saml:SP',
        'entityID' => 'https://sp.idp.test/simplesaml/module.php/saml/sp/metadata/default-sp',
        'idp' => getenv('SSP_IDP_ENTITY_ID') ?: 'https://acme.idp.test/saml/metadata',
        'NameIDPolicy' => [
            'Format' => 'urn:oasis:names:tc:SAML:2.0:nameid-format:persistent',
            'AllowCreate' => true,
        ],
    ],
];
