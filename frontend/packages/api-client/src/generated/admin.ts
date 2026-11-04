// Generated from contracts/openapi/admin.yaml by `pnpm generate`. Do not edit.

export interface paths {
    "/api/admin/users": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Create a user who waits for the invitation that is mailed to them */
        post: operations["inviteUser"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/admin/users/search": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Find the user with an address. Only the whole address finds them, in any case, and a part of one finds nobody. The address goes in the body, so it stays out of URLs and logs */
        post: operations["searchUsers"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/admin/users/{id}/invitation": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["Id"];
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Mail a new invitation, which voids the link of the one before */
        post: operations["resendInvitation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/admin/users/{id}/role": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["Id"];
            };
            cookie?: never;
        };
        get?: never;
        /** Change the role of a user, which a live session of theirs follows on its next request */
        put: operations["changeRole"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/admin/users/{id}/block": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["Id"];
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Block an active user. Their sessions and logins in progress end in the same step */
        post: operations["blockUser"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/admin/users/{id}/unblock": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["Id"];
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Let a blocked user log in again */
        post: operations["unblockUser"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/admin/users/{id}/mfa-reset": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["Id"];
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Remove every TOTP, passkey and recovery code of a user who lost them, so the next login enrolls again. Their sessions and logins in progress end in the same step */
        post: operations["resetMfa"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/admin/users/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["Id"];
            };
            cookie?: never;
        };
        /** The card of a user: who they are, the Service Providers they are assigned to and the newest records of the journal about them */
        get: operations["getUser"];
        put?: never;
        post?: never;
        /** Erase a user with their data and sessions. The ID is never given out again */
        delete: operations["eraseUser"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/admin/users/{id}/sessions": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["Id"];
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** End every session of a user and their logins in progress. An admin who names themselves keeps the session of the request */
        delete: operations["endUserSessions"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/admin/policy": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** The login and session policy of the tenant. MFA for `TENANT_ADMIN` is always on, so the policy has no switch for it */
        get: operations["getPolicy"];
        /** Replace the policy read with the `ETag` sent back in `If-Match`. A policy saved meanwhile by someone else gets 412, so the client reads it again. A tighter timeout reaches the sessions already open on their next request */
        put: operations["savePolicy"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/admin/sessions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** End every session of the tenant, the one of the request too */
        delete: operations["endAllSessions"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/admin/service-providers": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** The Service Providers of the tenant, by name */
        get: operations["listServiceProviders"];
        put?: never;
        /** Register a Service Provider from its metadata file. The file name of the upload is never read. The SP starts with the default settings and nobody assigned */
        post: operations["registerServiceProvider"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/admin/service-providers/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["Id"];
            };
            cookie?: never;
        };
        /** A Service Provider with what its metadata said and its settings */
        get: operations["getServiceProvider"];
        put?: never;
        post?: never;
        /** Remove a Service Provider with every assignment to it. Its users can no longer log in to it */
        delete: operations["removeServiceProvider"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/admin/service-providers/{id}/settings": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["Id"];
            };
            cookie?: never;
        };
        get?: never;
        /** Replace the settings read with the `ETag` sent back in `If-Match`. Settings saved meanwhile by someone else get 412, so the client reads them again */
        put: operations["saveServiceProviderSettings"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/admin/service-providers/{id}/users": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["Id"];
            };
            cookie?: never;
        };
        /** The users assigned to a Service Provider, by ID. The card of a user tells who they are */
        get: operations["listAssignedUsers"];
        put?: never;
        /** Let the users log in to the Service Provider. A user assigned already stays so. A user the tenant does not have leaves everyone of the list unassigned */
        post: operations["assignUsers"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/admin/service-providers/{id}/users/removal": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["Id"];
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Stop the users from logging in to the Service Provider. A user who was not assigned changes nothing. A session already open at the SP is the SP's own and lasts as it decides */
        post: operations["unassignUsers"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/admin/signing-keys": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** The keys the tenant signs SAML Responses with, as its metadata publishes them: the active one first, and during a rotation the next one */
        get: operations["listSigningKeys"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/admin/signing-keys/rotation": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Create the next key and publish it in metadata beside the active one. It signs nothing until the rotation completes, so the Service Providers have time to read it */
        post: operations["startKeyRotation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/admin/signing-keys/rotation/completion": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Delete the active key and let the next one sign. A Service Provider that has not read the next key fails every login until it does */
        post: operations["completeKeyRotation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        /** @enum {string} */
        Role: "TENANT_ADMIN" | "USER";
        InvitationRequest: {
            email: string;
            role: components["schemas"]["Role"];
        };
        RoleChangeRequest: {
            role: components["schemas"]["Role"];
        };
        UserSearchRequest: {
            email: string;
        };
        UserSearch: {
            users: components["schemas"]["FoundUser"][];
        };
        FoundUser: {
            /** Format: uuid */
            id: string;
            role: components["schemas"]["Role"];
            status: components["schemas"]["UserStatus"];
        };
        /** @enum {string} */
        UserStatus: "INVITED" | "ACTIVE" | "BLOCKED";
        UserCard: {
            /** Format: uuid */
            id: string;
            email: string;
            name: string | null;
            role: components["schemas"]["Role"];
            status: components["schemas"]["UserStatus"];
            /** @description The Service Providers the user is assigned to, by name */
            serviceProviders: components["schemas"]["AssignedServiceProvider"][];
            /** @description The newest records of the journal whose target is the user, the newest first. The IP and the details stay out */
            events: components["schemas"]["UserEvent"][];
        };
        AssignedServiceProvider: {
            /** Format: uuid */
            id: string;
            entityId: string;
            name: string;
        };
        UserEvent: {
            /** Format: uuid */
            id: string;
            /** Format: date-time */
            occurredAt: string;
            action: string;
            /** @enum {string} */
            actorType: "USER" | "SYSTEM" | "ANONYMOUS";
            /**
             * Format: uuid
             * @description The user who acted, null unless `actorType` is `USER`
             */
            actorId: string | null;
        };
        Policy: {
            /** @description Whether the `USER` role logs in with a second factor. An admin always does */
            mfaForUsers: boolean;
            /** @description Whether every role logs in with a passkey */
            passkeyRequired: boolean;
            /** Format: int64 */
            sessionIdleSeconds: number;
            /** Format: int64 */
            sessionAbsoluteSeconds: number;
        };
        InvitedUser: {
            /** Format: uuid */
            id: string;
        };
        Created: {
            /** Format: uuid */
            id: string;
        };
        ServiceProviderUpload: {
            name: string;
            /**
             * Format: binary
             * @description The metadata file of the SP, at most 128 KiB. Only its content is read
             */
            metadata: string;
        };
        ServiceProviderList: {
            serviceProviders: components["schemas"]["ServiceProviderSummary"][];
        };
        ServiceProviderSummary: {
            /** Format: uuid */
            id: string;
            entityId: string;
            name: string;
        };
        ServiceProvider: {
            /** Format: uuid */
            id: string;
            entityId: string;
            name: string;
            /** @description The HTTP-POST endpoints of the metadata, in the order of their index */
            acs: components["schemas"]["AcsEndpoint"][];
            /** @description Whether the SP signs its requests, so an unsigned one is refused */
            authnRequestsSigned: boolean;
            /** @description The certificates the requests of the SP are checked with */
            signingCertificates: components["schemas"]["Certificate"][];
            settings: components["schemas"]["SpSettings"];
        };
        AcsEndpoint: {
            index: number;
            url: string;
            isDefault: boolean;
        };
        Certificate: {
            /** @description SHA-256 of the DER certificate in uppercase hex pairs joined by colons */
            sha256Fingerprint: string;
            /** Format: date-time */
            notAfter: string;
        };
        SpSettings: {
            /**
             * @description A persistent NameID means nothing outside the SP. The email is sent only when an admin picks it
             * @enum {string}
             */
            nameIdFormat: "PERSISTENT" | "EMAIL";
            /** @enum {string} */
            signedPart: "ASSERTION" | "RESPONSE" | "BOTH";
            /**
             * @description RSA-PSS with SHA-256 unless the SP verifies nothing else. SHA-1 is never offered
             * @enum {string}
             */
            signatureAlgorithm: "RSA_PSS_SHA256" | "RSA_SHA256";
            /** @description The attributes the Response carries. None by default */
            attributes: ("EMAIL" | "DISPLAY_NAME" | "PHONE")[];
        };
        UserList: {
            userIds: string[];
        };
        AssignedUsers: {
            users: components["schemas"]["AssignedUser"][];
        };
        AssignedUser: {
            /** Format: uuid */
            id: string;
            role: components["schemas"]["Role"];
            status: components["schemas"]["UserStatus"];
        };
        SigningKeys: {
            keys: components["schemas"]["SigningKey"][];
        };
        SigningKey: {
            /** Format: uuid */
            id: string;
            /**
             * @description The active key signs, the next one is published until the rotation completes
             * @enum {string}
             */
            status: "ACTIVE" | "NEXT";
            /** @description SHA-256 of the DER certificate in uppercase hex pairs joined by colons */
            sha256Fingerprint: string;
            /** Format: date-time */
            notAfter: string;
            /** Format: date-time */
            createdAt: string;
        };
        ReauthenticationRequired: {
            /** @enum {string} */
            error: "reauthentication_required";
        };
    };
    responses: {
        /** @description The request is malformed or names an unknown field */
        BadRequest: {
            headers: {
                [name: string]: unknown;
            };
            content?: never;
        };
        /** @description The request has no live session of this tenant */
        NoSession: {
            headers: {
                [name: string]: unknown;
            };
            content?: never;
        };
        /** @description The user of the session is not an admin, or the CSRF token is missing */
        Forbidden: {
            headers: {
                [name: string]: unknown;
            };
            content?: never;
        };
        /** @description The user of the session is not an admin or the CSRF token is missing, with no body, or the login is too old, with `reauthentication_required` in the body */
        Refused: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["ReauthenticationRequired"];
            };
        };
        /** @description The tenant has no such user */
        UserNotFound: {
            headers: {
                [name: string]: unknown;
            };
            content?: never;
        };
        /** @description The tenant has no such Service Provider */
        ServiceProviderNotFound: {
            headers: {
                [name: string]: unknown;
            };
            content?: never;
        };
        /** @description The user is the last admin who can act, so the tenant would be left without one */
        LastAdmin: {
            headers: {
                [name: string]: unknown;
            };
            content?: never;
        };
        /** @description The request is over the size limit */
        TooLarge: {
            headers: {
                [name: string]: unknown;
            };
            content?: never;
        };
        /** @description The client is older than the minimum version, so it has to reload */
        UpgradeRequired: {
            headers: {
                [name: string]: unknown;
            };
            content?: never;
        };
        /** @description Over a limit */
        TooManyRequests: {
            headers: {
                /** @description Seconds to wait */
                "Retry-After"?: number;
                [name: string]: unknown;
            };
            content?: never;
        };
    };
    parameters: {
        Id: string;
    };
    requestBodies: never;
    headers: {
        /** @description The version of the policy or of the settings of a Service Provider, to send back in `If-Match` */
        ETag: string;
    };
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    inviteUser: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["InvitationRequest"];
            };
        };
        responses: {
            /** @description The user is created and the mail is on its way */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InvitedUser"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["NoSession"];
            403: components["responses"]["Forbidden"];
            413: components["responses"]["TooLarge"];
            /** @description The address is not valid, or the tenant already has a user with it. The answer does not say which */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    searchUsers: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UserSearchRequest"];
            };
        };
        responses: {
            /** @description The user with the address, or none */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["UserSearch"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["NoSession"];
            403: components["responses"]["Forbidden"];
            413: components["responses"]["TooLarge"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    resendInvitation: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["Id"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The mail is on its way */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["NoSession"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["UserNotFound"];
            413: components["responses"]["TooLarge"];
            /** @description The user has already accepted an invitation */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    changeRole: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["Id"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["RoleChangeRequest"];
            };
        };
        responses: {
            /** @description The user has the role */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["NoSession"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["UserNotFound"];
            413: components["responses"]["TooLarge"];
            422: components["responses"]["LastAdmin"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    blockUser: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["Id"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The user is blocked */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["NoSession"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["UserNotFound"];
            413: components["responses"]["TooLarge"];
            /** @description The user is not active, or is the last admin who can act */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    unblockUser: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["Id"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The user is active */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["NoSession"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["UserNotFound"];
            413: components["responses"]["TooLarge"];
            /** @description The user is not blocked */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    resetMfa: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["Id"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The factors are gone */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["NoSession"];
            403: components["responses"]["Refused"];
            404: components["responses"]["UserNotFound"];
            413: components["responses"]["TooLarge"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    getUser: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["Id"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The card */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["UserCard"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["NoSession"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["UserNotFound"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    eraseUser: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["Id"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The user is erased */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["NoSession"];
            403: components["responses"]["Refused"];
            404: components["responses"]["UserNotFound"];
            422: components["responses"]["LastAdmin"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    endUserSessions: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["Id"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The sessions are ended */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["NoSession"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["UserNotFound"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    getPolicy: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The policy, with its version in `ETag` */
            200: {
                headers: {
                    ETag: components["headers"]["ETag"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Policy"];
                };
            };
            401: components["responses"]["NoSession"];
            403: components["responses"]["Forbidden"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    savePolicy: {
        parameters: {
            query?: never;
            header: {
                "If-Match": string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["Policy"];
            };
        };
        responses: {
            /** @description The policy is saved, with its new version in `ETag` */
            204: {
                headers: {
                    ETag: components["headers"]["ETag"];
                    [name: string]: unknown;
                };
                content?: never;
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["NoSession"];
            403: components["responses"]["Refused"];
            /** @description The policy changed since the client read it, or `If-Match` names no version of it */
            412: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            413: components["responses"]["TooLarge"];
            /** @description A timeout is out of bounds. The idle one is from 300 to 3600 seconds, the absolute one from the idle one to 86400 seconds */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            426: components["responses"]["UpgradeRequired"];
            /** @description The request has no `If-Match` */
            428: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            429: components["responses"]["TooManyRequests"];
        };
    };
    endAllSessions: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The sessions are ended */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["NoSession"];
            403: components["responses"]["Forbidden"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    listServiceProviders: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The Service Providers */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ServiceProviderList"];
                };
            };
            401: components["responses"]["NoSession"];
            403: components["responses"]["Forbidden"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    registerServiceProvider: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "multipart/form-data": components["schemas"]["ServiceProviderUpload"];
            };
        };
        responses: {
            /** @description The Service Provider is registered */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Created"];
                };
            };
            /** @description The form has no name or no file */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["NoSession"];
            403: components["responses"]["Forbidden"];
            /** @description The tenant already has a Service Provider with this EntityID */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description The request is over 160 KiB */
            413: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description The body is not `multipart/form-data` */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description The name is empty or longer than 200 characters, or the file is over 128 KiB or is not valid SAML metadata of one Service Provider with https endpoints. The answer does not say which */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    getServiceProvider: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["Id"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The Service Provider, with the version of its settings in `ETag` */
            200: {
                headers: {
                    ETag: components["headers"]["ETag"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ServiceProvider"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["NoSession"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["ServiceProviderNotFound"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    removeServiceProvider: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["Id"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The Service Provider is removed */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["NoSession"];
            403: components["responses"]["Refused"];
            404: components["responses"]["ServiceProviderNotFound"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    saveServiceProviderSettings: {
        parameters: {
            query?: never;
            header: {
                "If-Match": string;
            };
            path: {
                id: components["parameters"]["Id"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SpSettings"];
            };
        };
        responses: {
            /** @description The settings are saved, with their new version in `ETag` */
            204: {
                headers: {
                    ETag: components["headers"]["ETag"];
                    [name: string]: unknown;
                };
                content?: never;
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["NoSession"];
            403: components["responses"]["Refused"];
            404: components["responses"]["ServiceProviderNotFound"];
            /** @description The settings changed since the client read them, or `If-Match` names no version of them */
            412: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            413: components["responses"]["TooLarge"];
            426: components["responses"]["UpgradeRequired"];
            /** @description The request has no `If-Match` */
            428: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            429: components["responses"]["TooManyRequests"];
        };
    };
    listAssignedUsers: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["Id"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The assigned users */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AssignedUsers"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["NoSession"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["ServiceProviderNotFound"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    assignUsers: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["Id"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UserList"];
            };
        };
        responses: {
            /** @description The users are assigned */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["NoSession"];
            403: components["responses"]["Forbidden"];
            /** @description The tenant has no such Service Provider or no such user */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            413: components["responses"]["TooLarge"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    unassignUsers: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["Id"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UserList"];
            };
        };
        responses: {
            /** @description The users are no longer assigned */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["NoSession"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["ServiceProviderNotFound"];
            413: components["responses"]["TooLarge"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    listSigningKeys: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The keys */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SigningKeys"];
                };
            };
            401: components["responses"]["NoSession"];
            403: components["responses"]["Forbidden"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    startKeyRotation: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The next key is published */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Created"];
                };
            };
            401: components["responses"]["NoSession"];
            403: components["responses"]["Forbidden"];
            /** @description A rotation is in progress already */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            413: components["responses"]["TooLarge"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    completeKeyRotation: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The next key is the active one */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["NoSession"];
            403: components["responses"]["Refused"];
            /** @description No rotation is in progress */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            413: components["responses"]["TooLarge"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
}
