<?php

// Read by config-override-base.php of the image after its own overrides.
$config['trusted.url.domains'] = ['sp.idp.test'];

// fetch-idp-metadata.sh writes the metadata of each local tenant here, as published at /saml/metadata.
$config['metadata.sources'] = [
    ['type' => 'flatfile'],
    ['type' => 'xml', 'file' => '/var/simplesamlphp/metadata/idp/acme.xml'],
];
