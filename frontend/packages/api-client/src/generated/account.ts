// Generated from contracts/openapi/account.yaml by `pnpm generate`. Do not edit.

export interface paths {
    "/api/account/factors": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Read the factors of the user */
        get: operations["getFactors"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/account/password": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Change the password, which needs the current one */
        post: operations["changePassword"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/account/totp": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Get a new TOTP secret, which counts for nothing until a code from it is confirmed */
        post: operations["beginTotp"];
        /** Remove the TOTP. Recovery codes go with the last second factor */
        delete: operations["removeTotp"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/account/totp/confirmation": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Turn the new secret on with a code from it */
        post: operations["confirmTotp"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/account/passkeys/options": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Get the options to register a passkey */
        post: operations["beginPasskey"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/account/passkeys": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Register a passkey with the answer of the authenticator */
        post: operations["addPasskey"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/account/passkeys/{id}": {
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
        /** Remove a passkey. Recovery codes go with the last second factor */
        delete: operations["removePasskey"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/account/recovery-codes": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Replace the recovery codes, the old ones stop working */
        post: operations["renewRecoveryCodes"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/account/sessions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Read the email and the role of the user and their live sessions */
        get: operations["getSessions"];
        put?: never;
        post?: never;
        /** End every session of the user but the current one, and their logins in progress */
        delete: operations["endOtherSessions"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/account/sessions/{id}": {
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
        /** End one other session of the user. The current one ends by logging out */
        delete: operations["endSession"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/account/logout": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * End the current session on the server and clear the browser
         * @description Answers 204 also without a live session, so a browser whose session is already gone still clears itself. A live session needs the CSRF token like any other change.
         */
        post: operations["logout"];
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
        PasswordChangeRequest: {
            currentPassword: string;
            newPassword: string;
            /** @description Ends every other session of the user and their logins in progress together with the change, false when left out. The current password stands in for the fresh login that ending sessions needs otherwise */
            endOtherSessions?: boolean;
        };
        TotpConfirmationRequest: {
            code: string;
        };
        PasskeyRegistrationRequest: {
            attestationObject: string;
            clientDataJson: string;
        };
        ReauthenticationRequired: {
            /** @enum {string} */
            error: "reauthentication_required";
        };
        Factors: {
            /** @description Whether a confirmed TOTP is enrolled */
            totp: boolean;
            /** @description The oldest first */
            passkeys: components["schemas"]["Passkey"][];
            recoveryCodesLeft: number;
        };
        Passkey: {
            /** Format: uuid */
            id: string;
            /** Format: date-time */
            createdAt: string;
            /** Format: date-time */
            lastUsedAt: string | null;
            /** @description Whether the authenticator syncs it to other devices */
            backedUp: boolean;
        };
        Sessions: {
            email: string;
            /**
             * @description The role of the user, so the console shows the admin pages only to an admin. The admin API checks it anew
             * @enum {string}
             */
            role: "TENANT_ADMIN" | "USER";
            /** @description The one seen last first */
            sessions: components["schemas"]["Session"][];
        };
        Session: {
            /** Format: uuid */
            id: string;
            /** @description Whether the request came with this session */
            current: boolean;
            /**
             * Format: date-time
             * @description The time of the login that opened it
             */
            authenticatedAt: string;
            /**
             * Format: date-time
             * @description Moves at most once a minute
             */
            lastSeenAt: string;
            /** @description The address of the login */
            ip: string | null;
            /** @description The User-Agent of the login */
            userAgent: string | null;
        };
        TotpSetup: {
            /** @description Base32. The client builds the `otpauth://` URI with the name of the tenant and the email */
            secret: string;
        };
        RecoveryCodes: {
            recoveryCodes: string[];
        };
        PasskeySetupOptions: {
            challenge: string;
            rpId: string;
            userHandle: string;
            algorithms: number[];
            excludeCredentials: string[];
            residentKey: string;
            userVerification: string;
            attestation: string;
        };
    };
    responses: {
        /** @description The factor is added. Recovery codes come only to a user who had none, and they are shown once */
        NewFactor: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["RecoveryCodes"];
            };
        };
        /** @description The request is malformed or names an unknown field */
        BadRequest: {
            headers: {
                [name: string]: unknown;
            };
            content?: never;
        };
        /** @description The request has no live session */
        NoSession: {
            headers: {
                [name: string]: unknown;
            };
            content?: never;
        };
        /** @description The CSRF token is missing */
        CsrfRejected: {
            headers: {
                [name: string]: unknown;
            };
            content?: never;
        };
        /** @description The CSRF token is missing, with no body, or the login is too old, with `reauthentication_required` in the body */
        Refused: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["ReauthenticationRequired"];
            };
        };
        /** @description The code or the passkey is not valid, or the next attempt has to wait. The answer does not say which */
        CredentialRejected: {
            headers: {
                [name: string]: unknown;
            };
            content?: never;
        };
        /** @description It is the last second factor under a policy that asks for MFA, or the last passkey under one that asks for passkeys. Add the replacement first */
        FactorRequired: {
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
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    getFactors: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The factors */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Factors"];
                };
            };
            401: components["responses"]["NoSession"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    changePassword: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PasswordChangeRequest"];
            };
        };
        responses: {
            /** @description The password is changed */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["NoSession"];
            403: components["responses"]["CsrfRejected"];
            413: components["responses"]["TooLarge"];
            /** @description The current password is wrong or its next attempt has to wait, or the new one breaks the password policy. The answer does not say which */
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
    beginTotp: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The secret */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TotpSetup"];
                };
            };
            401: components["responses"]["NoSession"];
            403: components["responses"]["Refused"];
            413: components["responses"]["TooLarge"];
            /** @description A TOTP is already confirmed. Remove it first to replace it */
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
    removeTotp: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The TOTP is removed */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["NoSession"];
            403: components["responses"]["Refused"];
            /** @description No TOTP is confirmed */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            422: components["responses"]["FactorRequired"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    confirmTotp: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["TotpConfirmationRequest"];
            };
        };
        responses: {
            200: components["responses"]["NewFactor"];
            400: components["responses"]["BadRequest"];
            401: components["responses"]["NoSession"];
            403: components["responses"]["Refused"];
            413: components["responses"]["TooLarge"];
            422: components["responses"]["CredentialRejected"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    beginPasskey: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The options for `navigator.credentials.create` */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PasskeySetupOptions"];
                };
            };
            401: components["responses"]["NoSession"];
            403: components["responses"]["Refused"];
            413: components["responses"]["TooLarge"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    addPasskey: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PasskeyRegistrationRequest"];
            };
        };
        responses: {
            200: components["responses"]["NewFactor"];
            400: components["responses"]["BadRequest"];
            401: components["responses"]["NoSession"];
            403: components["responses"]["Refused"];
            413: components["responses"]["TooLarge"];
            422: components["responses"]["CredentialRejected"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    removePasskey: {
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
            /** @description The passkey is removed */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["NoSession"];
            403: components["responses"]["Refused"];
            /** @description The user holds no passkey with this id */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            422: components["responses"]["FactorRequired"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    renewRecoveryCodes: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The new codes, shown once */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RecoveryCodes"];
                };
            };
            401: components["responses"]["NoSession"];
            403: components["responses"]["Refused"];
            413: components["responses"]["TooLarge"];
            /** @description The user has no second factor for the codes to stand in for */
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
    getSessions: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The sessions */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Sessions"];
                };
            };
            401: components["responses"]["NoSession"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    endOtherSessions: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The other sessions are ended */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["NoSession"];
            403: components["responses"]["Refused"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    endSession: {
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
            /** @description The session is ended */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["NoSession"];
            403: components["responses"]["Refused"];
            /** @description No other live session of the user has this id */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    logout: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The session is ended, its cookies are cleared, and the browser drops the cache and the storage of this origin */
            204: {
                headers: {
                    /** @description Clears `__Host-session` and `__Host-csrf` */
                    "Set-Cookie"?: string;
                    /** @description `"cache", "storage"`. Cookies are left to `Set-Cookie`, because this header would clear them for every tenant */
                    "Clear-Site-Data"?: string;
                    [name: string]: unknown;
                };
                content?: never;
            };
            403: components["responses"]["CsrfRejected"];
            413: components["responses"]["TooLarge"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
}
