// Generated from contracts/openapi/auth.yaml by `pnpm generate`. Do not edit.

export interface paths {
    "/api/flows": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Start a flow, which sets the cookie that binds it to this browser */
        post: operations["startFlow"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/flows/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["Id"];
            };
            cookie?: never;
        };
        /** Read where a flow stands, for a page that was reloaded */
        get: operations["getFlow"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/flows/{id}/login": {
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
        /** Enter a login. The answer is the same whether it belongs to anyone or not */
        post: operations["submitLogin"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/flows/{id}/password": {
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
        /** Enter the password */
        post: operations["submitPassword"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/flows/{id}/invitation": {
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
        /**
         * Accept an invitation and set the first password. Offered only at the `IDENTIFY` step
         * @description The new password counts as proved, so the flow goes on to whatever the policy asks next. A browser that holds a live session starts the flow with `reauthenticate`, otherwise the flow is past `IDENTIFY`. A refused password keeps the invitation.
         */
        post: operations["acceptInvitation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/flows/{id}/totp": {
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
        /** Enter a TOTP code */
        post: operations["submitTotp"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/flows/{id}/recovery-code": {
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
        /** Enter a recovery code, which works once */
        post: operations["submitRecoveryCode"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/flows/{id}/passkey/options": {
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
        /** Get a challenge for a passkey. It lists no credentials, because passkeys are discoverable */
        post: operations["beginPasskeyLogin"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/flows/{id}/passkey": {
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
        /** Answer the challenge. Binary fields are base64url */
        post: operations["submitPasskey"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/flows/{id}/enrollment/totp": {
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
        /** Get a TOTP secret. Offered only at the `ENROLL_MFA` step */
        post: operations["beginTotpEnrollment"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/flows/{id}/enrollment/totp/confirmation": {
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
        /** Confirm the secret with a code, which also proves the factor */
        post: operations["confirmTotpEnrollment"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/flows/{id}/enrollment/passkey/options": {
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
        /** Get the options to register a passkey. Offered only at the `ENROLL_MFA` step */
        post: operations["beginPasskeyEnrollment"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/flows/{id}/enrollment/passkey": {
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
        /** Register the passkey, which also proves the factor. Binary fields are base64url */
        post: operations["finishPasskeyEnrollment"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/flows/{id}/complete": {
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
        /**
         * Finish the flow, which consumes it
         * @description Sets `__Host-session` and `__Host-csrf` when the flow proved something new. Otherwise the browser keeps the cookies it has.
         */
        post: operations["completeFlow"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/password-reset": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Ask for a password reset mail. The answer is the same whether the login belongs to anyone or not */
        post: operations["requestPasswordReset"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/password-reset/confirmation": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Set a new password with the token from the mail
         * @description Revokes every session of the user and ends the logins in progress. It opens no session, and the next login still asks for every factor. A refused password keeps the token.
         */
        post: operations["confirmPasswordReset"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/email-change": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Ask to change the email of the signed-in user
         * @description Needs a session whose full login is at most 10 minutes old, so a client first walks a flow started with `reauthenticate`. The answer is the same for an address that belongs to someone else. A confirmation mail goes to the new address.
         */
        post: operations["requestEmailChange"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/email-change/confirmation": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Confirm the new address with the token from the mail
         * @description Needs no session, because the link may open on another device. The old address gets a notice.
         */
        post: operations["confirmEmailChange"];
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
        StartFlowRequest: {
            /** @description Ignore the session and prove everything anew, which sensitive actions ask for */
            reauthenticate?: boolean;
        };
        LoginRequest: {
            login: string;
        };
        PasswordRequest: {
            password: string;
        };
        CodeRequest: {
            code: string;
        };
        InvitationRequest: {
            token: string;
            password: string;
        };
        PasswordResetConfirmation: {
            token: string;
            password: string;
        };
        EmailChangeRequest: {
            /** Format: email */
            email: string;
        };
        TokenRequest: {
            token: string;
        };
        ReauthenticationRequired: {
            /** @enum {string} */
            error: "reauthentication_required";
        };
        PasskeyLoginRequest: {
            credentialId: string;
            userHandle?: string | null;
            authenticatorData: string;
            clientDataJson: string;
            signature: string;
        };
        PasskeyEnrollmentRequest: {
            attestationObject: string;
            clientDataJson: string;
        };
        Flow: {
            /** Format: uuid */
            id: string;
            step: components["schemas"]["Step"];
            /** @description What the step offers a choice of */
            factors: components["schemas"]["Factor"][];
        };
        EnrolledFlow: {
            /** Format: uuid */
            id: string;
            step: components["schemas"]["Step"];
            factors: components["schemas"]["Factor"][];
            recoveryCodes: string[];
        };
        /** @enum {string} */
        Step: "IDENTIFY" | "PASSWORD" | "SECOND_FACTOR" | "ENROLL_MFA" | "COMPLETE";
        /** @enum {string} */
        Factor: "PASSWORD" | "TOTP" | "PASSKEY" | "RECOVERY_CODE";
        Completed: {
            /** @description Where to send the browser. Empty for a login that came from no Service Provider */
            returnUrl: string | null;
        };
        TotpEnrollment: {
            /** @description Base32 */
            secret: string;
        };
        PasskeyLoginOptions: {
            challenge: string;
            rpId: string;
            userVerification: string;
        };
        PasskeyEnrollmentOptions: {
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
        /** @description The next step */
        NextStep: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["Flow"];
            };
        };
        /** @description The next step, and recovery codes shown once */
        Enrolled: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["EnrolledFlow"];
            };
        };
        /** @description The request is malformed or names an unknown field */
        BadRequest: {
            headers: {
                [name: string]: unknown;
            };
            content?: never;
        };
        /** @description A browser with a live session did not echo its CSRF token */
        CsrfRejected: {
            headers: {
                [name: string]: unknown;
            };
            content?: never;
        };
        /** @description The flow is at another step, or a parallel request is on this one. Read the flow and go on from there */
        StepConflict: {
            headers: {
                [name: string]: unknown;
            };
            content?: never;
        };
        /** @description The flow is unknown, over, or began in another browser. Start a new one */
        Expired: {
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
        /** @description The credential is wrong, the next attempt has to wait, or this account cannot log in. The answer does not say which */
        Rejected: {
            headers: {
                [name: string]: unknown;
            };
            content?: never;
        };
        /** @description The token is unknown, spent or expired, the address is taken, or the password breaks the policy. The answer does not say which */
        TokenRefused: {
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
    startFlow: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["StartFlowRequest"];
            };
        };
        responses: {
            /** @description The flow, already past whatever a live session proves */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Flow"];
                };
            };
            400: components["responses"]["BadRequest"];
            403: components["responses"]["CsrfRejected"];
            413: components["responses"]["TooLarge"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    getFlow: {
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
            /** @description The flow */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Flow"];
                };
            };
            400: components["responses"]["BadRequest"];
            410: components["responses"]["Expired"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    submitLogin: {
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
                "application/json": components["schemas"]["LoginRequest"];
            };
        };
        responses: {
            200: components["responses"]["NextStep"];
            400: components["responses"]["BadRequest"];
            403: components["responses"]["CsrfRejected"];
            409: components["responses"]["StepConflict"];
            410: components["responses"]["Expired"];
            413: components["responses"]["TooLarge"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    submitPassword: {
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
                "application/json": components["schemas"]["PasswordRequest"];
            };
        };
        responses: {
            200: components["responses"]["NextStep"];
            400: components["responses"]["BadRequest"];
            403: components["responses"]["CsrfRejected"];
            409: components["responses"]["StepConflict"];
            410: components["responses"]["Expired"];
            413: components["responses"]["TooLarge"];
            422: components["responses"]["Rejected"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    acceptInvitation: {
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
                "application/json": components["schemas"]["InvitationRequest"];
            };
        };
        responses: {
            200: components["responses"]["NextStep"];
            400: components["responses"]["BadRequest"];
            403: components["responses"]["CsrfRejected"];
            409: components["responses"]["StepConflict"];
            410: components["responses"]["Expired"];
            413: components["responses"]["TooLarge"];
            422: components["responses"]["TokenRefused"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    submitTotp: {
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
                "application/json": components["schemas"]["CodeRequest"];
            };
        };
        responses: {
            200: components["responses"]["NextStep"];
            400: components["responses"]["BadRequest"];
            403: components["responses"]["CsrfRejected"];
            409: components["responses"]["StepConflict"];
            410: components["responses"]["Expired"];
            413: components["responses"]["TooLarge"];
            422: components["responses"]["Rejected"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    submitRecoveryCode: {
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
                "application/json": components["schemas"]["CodeRequest"];
            };
        };
        responses: {
            200: components["responses"]["NextStep"];
            400: components["responses"]["BadRequest"];
            403: components["responses"]["CsrfRejected"];
            409: components["responses"]["StepConflict"];
            410: components["responses"]["Expired"];
            413: components["responses"]["TooLarge"];
            422: components["responses"]["Rejected"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    beginPasskeyLogin: {
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
            /** @description The options for `navigator.credentials.get` */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PasskeyLoginOptions"];
                };
            };
            400: components["responses"]["BadRequest"];
            403: components["responses"]["CsrfRejected"];
            410: components["responses"]["Expired"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    submitPasskey: {
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
                "application/json": components["schemas"]["PasskeyLoginRequest"];
            };
        };
        responses: {
            200: components["responses"]["NextStep"];
            400: components["responses"]["BadRequest"];
            403: components["responses"]["CsrfRejected"];
            409: components["responses"]["StepConflict"];
            410: components["responses"]["Expired"];
            413: components["responses"]["TooLarge"];
            422: components["responses"]["Rejected"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    beginTotpEnrollment: {
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
            /** @description The secret, shown once */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TotpEnrollment"];
                };
            };
            400: components["responses"]["BadRequest"];
            403: components["responses"]["CsrfRejected"];
            409: components["responses"]["StepConflict"];
            410: components["responses"]["Expired"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    confirmTotpEnrollment: {
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
                "application/json": components["schemas"]["CodeRequest"];
            };
        };
        responses: {
            200: components["responses"]["Enrolled"];
            400: components["responses"]["BadRequest"];
            403: components["responses"]["CsrfRejected"];
            409: components["responses"]["StepConflict"];
            410: components["responses"]["Expired"];
            413: components["responses"]["TooLarge"];
            422: components["responses"]["Rejected"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    beginPasskeyEnrollment: {
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
            /** @description The options for `navigator.credentials.create` */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PasskeyEnrollmentOptions"];
                };
            };
            400: components["responses"]["BadRequest"];
            403: components["responses"]["CsrfRejected"];
            409: components["responses"]["StepConflict"];
            410: components["responses"]["Expired"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    finishPasskeyEnrollment: {
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
                "application/json": components["schemas"]["PasskeyEnrollmentRequest"];
            };
        };
        responses: {
            200: components["responses"]["Enrolled"];
            400: components["responses"]["BadRequest"];
            403: components["responses"]["CsrfRejected"];
            409: components["responses"]["StepConflict"];
            410: components["responses"]["Expired"];
            413: components["responses"]["TooLarge"];
            422: components["responses"]["Rejected"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    completeFlow: {
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
            /** @description Where the browser goes next */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Completed"];
                };
            };
            400: components["responses"]["BadRequest"];
            403: components["responses"]["CsrfRejected"];
            409: components["responses"]["StepConflict"];
            410: components["responses"]["Expired"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    requestPasswordReset: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["LoginRequest"];
            };
        };
        responses: {
            /** @description Accepted, and a mail goes out if the login belongs to an active user */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            400: components["responses"]["BadRequest"];
            403: components["responses"]["CsrfRejected"];
            413: components["responses"]["TooLarge"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    confirmPasswordReset: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PasswordResetConfirmation"];
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
            403: components["responses"]["CsrfRejected"];
            413: components["responses"]["TooLarge"];
            422: components["responses"]["TokenRefused"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    requestEmailChange: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["EmailChangeRequest"];
            };
        };
        responses: {
            /** @description Accepted, and the change waits for the new address to confirm it */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            400: components["responses"]["BadRequest"];
            /** @description The request has no live session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description The CSRF token is missing, with no body, or the login is too old, with `reauthentication_required` in the body */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ReauthenticationRequired"];
                };
            };
            413: components["responses"]["TooLarge"];
            /** @description The address is not valid */
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
    confirmEmailChange: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["TokenRequest"];
            };
        };
        responses: {
            /** @description The address is changed */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            400: components["responses"]["BadRequest"];
            403: components["responses"]["CsrfRejected"];
            413: components["responses"]["TooLarge"];
            422: components["responses"]["TokenRefused"];
            426: components["responses"]["UpgradeRequired"];
            429: components["responses"]["TooManyRequests"];
        };
    };
}
