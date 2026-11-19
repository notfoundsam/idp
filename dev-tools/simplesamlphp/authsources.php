<?php

// The test SP of the local environment and of SamlInteropTest. It asks for the persistent NameID the IdP gives by default.
$domain = getenv('IDP_DOMAIN') ?: 'idp.test';
$config = [
    'admin' => [
        'core:AdminPassword',
    ],

    'default-sp' => [
        'saml:SP',
        'entityID' => "https://sp.$domain/simplesaml/module.php/saml/sp/metadata/default-sp",
        'idp' => getenv('SSP_IDP_ENTITY_ID') ?: "https://acme.$domain/saml/metadata",
        'NameIDPolicy' => [
            'Format' => 'urn:oasis:names:tc:SAML:2.0:nameid-format:persistent',
            'AllowCreate' => true,
        ],
    ],
];
