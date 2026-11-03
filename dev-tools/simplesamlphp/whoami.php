<?php

// Logs in through default-sp and prints what the IdP said, so a person or a test can read it without the admin pages.
require_once('_include.php');

$auth = new \SimpleSAML\Auth\Simple('default-sp');
$auth->requireAuth();

$nameId = $auth->getAuthData('saml:sp:NameID');

header('Content-Type: application/json; charset=utf-8');
echo json_encode([
    'nameId' => $nameId?->getValue(),
    'nameIdFormat' => $nameId?->getFormat(),
    'authnContext' => $auth->getAuthData('saml:sp:AuthnContext'),
    'attributes' => $auth->getAttributes(),
], JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES);
