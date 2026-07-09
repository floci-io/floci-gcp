# Identity Platform / Firebase Auth

floci-gcp emulates the Google Identity Toolkit API (`identitytoolkit.googleapis.com` v1)
wire-compatibly with the **official Firebase Auth emulator**: same paths, same unsigned
JWTs, same error strings. Anything that works against `firebase emulators:start --only auth`
is the compatibility target.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_FIREBASEAUTH_ENABLED` | `true` | Enable/disable Firebase Auth |

## Endpoint

Set `FIREBASE_AUTH_EMULATOR_HOST=localhost:4588`. Firebase SDKs then prefix requests with
the API hostname as a **path**, which floci-gcp serves directly on its single port:

| Method | Path |
|---|---|
| `POST` | `/identitytoolkit.googleapis.com/v1/accounts:signUp` \| `:signInWithPassword` \| `:signInWithCustomToken` \| `:lookup` \| `:update` \| `:delete` (client, `?key=` any non-empty API key) |
| `POST` | `/identitytoolkit.googleapis.com/v1/projects/{project}/accounts[:lookup\|:update\|:delete\|:batchDelete]` (admin, `Authorization: Bearer owner`) |
| `GET` | `/identitytoolkit.googleapis.com/v1/projects/{project}/accounts:batchGet` (admin listUsers) |
| `POST` | `/securetoken.googleapis.com/v1/token` (refresh; JSON or form-urlencoded) |
| `DELETE` | `/emulator/v1/projects/{project}/accounts` (test helper: delete all users) |

## Tokens

- ID tokens are **unsigned JWTs** (`alg: none`, empty signature) with the claims the
  Admin SDK's emulator-mode verifier checks: `iss=https://securetoken.google.com/{project}`,
  `aud={project}`, `sub`/`user_id`, `auth_time`, `firebase.{identities, sign_in_provider}`.
  Custom claims (from `setCustomUserClaims` or custom-token `claims`) are merged into the payload.
- Refresh tokens follow the emulator's base64-JSON record format; `POST /securetoken.../token`
  with `grant_type=refresh_token` re-issues tokens.
- Token revocation: `revokeRefreshTokens` sets `validSince`; tokens with `iat < validSince`
  fail with `TOKEN_EXPIRED` (wall-clock `exp` is intentionally not enforced, like the emulator).
- Custom tokens: Admin SDK JWTs (signed or unsigned) and the emulator's strict-JSON form
  (`{"uid": "...", "claims": {...}}`) are both accepted.

## Quick Start

=== "Java (firebase-admin)"

    ```java
    // export FIREBASE_AUTH_EMULATOR_HOST=localhost:4588
    FirebaseApp app = FirebaseApp.initializeApp(FirebaseOptions.builder()
            .setProjectId("floci-local")   // the emulator's default project
            .setCredentials(new EmulatorCredentials())
            .build());
    FirebaseAuth auth = FirebaseAuth.getInstance(app);

    auth.createUser(new UserRecord.CreateRequest()
            .setUid("alice").setEmail("alice@example.com").setPassword("secret123"));
    auth.setCustomUserClaims("alice", Map.of("role", "admin"));
    String customToken = auth.createCustomToken("alice");
    // client exchanges it at accounts:signInWithCustomToken, then:
    FirebaseToken decoded = auth.verifyIdToken(idToken);
    ```

=== "REST"

    ```bash
    # client sign-up (any non-empty API key)
    curl -X POST 'http://localhost:4588/identitytoolkit.googleapis.com/v1/accounts:signUp?key=fake-api-key' \
      -H 'Content-Type: application/json' \
      -d '{"email":"alice@example.com","password":"secret123"}'

    # refresh
    curl -X POST 'http://localhost:4588/securetoken.googleapis.com/v1/token?key=fake-api-key' \
      -d 'grant_type=refresh_token&refresh_token=<refreshToken>'
    ```

## Scope and deviations

- Client API-key calls resolve to the emulator's **default project**
  (`floci-gcp.default-project-id`), mirroring the official emulator's single-project model.
- Phase 1 covers email/password, anonymous, and custom-token flows plus the admin user CRUD
  surface (create/lookup/update/delete/batchGet/batchDelete). Not yet implemented: OOB codes
  (email verification / password reset), email-link, IdP, phone, MFA, passkeys, tenants,
  session cookies, and the legacy v3 `relyingparty` paths — these return 404 (the official
  emulator returns 501 for unimplemented operations).
- Passwords are stored in the emulator's literal `fakeHash:salt=...:password=...` format —
  a dev fixture, not a security boundary, identical to the official emulator.
- `securetoken` responses report `project_id: "12345"`, the emulator's hardcoded project number.
