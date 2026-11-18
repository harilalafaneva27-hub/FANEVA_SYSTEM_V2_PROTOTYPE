# Owner-Verified Bootstrap — 30-minute policy

**Status:** approved implementation requirement; staging-only; not production-approved.
**Branch:** `implementation/owner-bootstrap-30m`
**Owner decision:** one-time bootstrap code lifetime is **30 minutes** (not 15).

## Security invariants

1. Generate the code with a cryptographically secure random generator containing at least 256 bits of entropy (32 random bytes); encode for transport only after generation.
2. Display the raw code to the owner once through a controlled out-of-band provisioning process. Never put it in source control, APK resources, logs, analytics, URLs, or ordinary API responses.
3. Persist only a SHA-256 digest of the canonical code bytes in D1. Never persist the raw code.
4. Expiry is fixed at `issued_at + 1800 seconds`. The expiry is checked against server time at consumption; client time is never authoritative.
5. Consumption is atomic and one-time. A successful consume marks the record used in the same D1 transaction/batch as creation of the initial ADMIN and initial server-side session.
6. Enforce a small fixed attempt ceiling (5 attempts per bootstrap record), increment attempts atomically for every failed candidate, and permanently lock the record at the ceiling. Apply an additional source/network rate limit; do not reveal whether a code exists, expired, used, or locked.
7. No default/fixed ADMIN password, no local-only ADMIN auto-creation, and no device-key-only ADMIN elevation.
8. Bootstrap may create exactly one initial ADMIN principal. Later devices require owner-approved enrollment and explicit role/store grants. ADMIN Android 1 and Android 2 remain equal authority; there is no primary ADMIN.
9. Issue an opaque high-entropy server session only after successful bootstrap. Store only its digest; bind it to the principal and device, set bounded expiry, support revocation, and never treat a device signature as a user session.
10. All bootstrap, ADMIN creation, session issuance, and audit writes must succeed or fail atomically. Fail closed on partial failure.

## State model

`ISSUED -> CONSUMED` on a successful atomic bootstrap; `ISSUED -> LOCKED` after five failed attempts; expiry is terminal for consumption. Replays and all terminal states are rejected.

## Explicit implementation boundary

The 30-minute lifetime and D1 storage schema are approved. The final HTTP contract, initial ADMIN credential enrollment UX, session lifetime/refresh/revocation policy, and source/network rate-limit mechanism must be implemented and tested before enabling remote login. No live D1 mutation or deployment is authorized by this decision.

## Required tests

- valid code accepted before 1800 seconds;
- rejected at/after expiry using server clock;
- raw code absent from D1, logs, and API responses;
- only digest persisted;
- concurrent duplicate consumption yields exactly one success;
- replay rejected;
- fifth failed attempt locks record and later attempts remain rejected;
- wrong code, expired, used, and locked responses do not disclose record state;
- ADMIN and session are created atomically or neither is created;
- device signature without a valid user session cannot access business endpoints;
- session expiry and revocation are enforced;
- legacy V1 resources and data remain untouched.
