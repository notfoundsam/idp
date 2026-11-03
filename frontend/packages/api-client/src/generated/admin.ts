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
        get?: never;
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
        InvitedUser: {
            /** Format: uuid */
            id: string;
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
    headers: never;
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
}
