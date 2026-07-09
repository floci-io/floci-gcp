package io.floci.gcp.services.firebaseauth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.firebaseauth.model.ProviderUserInfo;
import io.floci.gcp.services.firebaseauth.model.StoredUser;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.floci.gcp.services.firebaseauth.FirebaseAuthException.badRequest;
import static io.floci.gcp.services.firebaseauth.FirebaseAuthException.check;

/**
 * Identity Toolkit v1 emulation, wire-compatible with the official Firebase Auth emulator
 * (local/google/firebase-tools/src/emulator/auth). Tokens are unsigned JWTs; passwords are
 * stored as the emulator's literal fakeHash strings — this service is a dev fixture, not
 * a security boundary.
 */
@ApplicationScoped
public class FirebaseAuthService {

    private static final Logger LOG = Logger.getLogger(FirebaseAuthService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int PASSWORD_MIN_LENGTH = 6;
    private static final long TOKEN_EXPIRES_IN_SECONDS = 3600;
    private static final String PROJECT_NUMBER = "12345";
    static final String CUSTOM_TOKEN_AUDIENCE =
            "https://identitytoolkit.googleapis.com/google.identity.identitytoolkit.v1.IdentityToolkit";
    private static final Set<String> RESERVED_CLAIMS = Set.of(
            "iss", "aud", "sub", "iat", "exp", "nbf", "jti", "nonce", "azp", "acr", "amr",
            "cnf", "auth_time", "firebase", "at_hash", "c_hash");

    private final StorageBackend<String, String> userStore;
    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;

    @Inject
    public FirebaseAuthService(StorageFactory storageFactory,
                               ServiceRegistry serviceRegistry,
                               EmulatorConfig config) {
        this.userStore = storageFactory.createGlobal("firebaseauth", "firebaseauth-users.json",
                new TypeReference<Map<String, String>>() {});
        this.serviceRegistry = serviceRegistry;
        this.config = config;
    }

    FirebaseAuthService(StorageBackend<String, String> userStore, EmulatorConfig config) {
        this.userStore = userStore;
        this.serviceRegistry = null;
        this.config = config;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("firebaseauth")
                .enabled(config.services().firebaseauth().enabled())
                .storageKey("firebaseauth")
                .protocol(ServiceProtocol.REST)
                .resourceClasses(FirebaseAuthController.class, SecureTokenController.class,
                        FirebaseAuthEmulatorController.class)
                .build());
    }

    // ── signUp ────────────────────────────────────────────────────────────────

    public Map<String, Object> signUp(String project, Map<String, Object> body, boolean privileged) {
        String email = str(body.get("email"));
        String password = str(body.get("password"));
        String idToken = str(body.get("idToken"));
        String requestedLocalId = str(body.get("localId"));

        String provider = null;
        StoredUser existing = null;
        if (privileged) {
            if (idToken != null) {
                check(requestedLocalId == null, "UNEXPECTED_PARAMETER : User ID");
                existing = parseIdToken(project, idToken);
            } else if (requestedLocalId != null) {
                check(getUser(project, requestedLocalId) == null, "DUPLICATE_LOCAL_ID");
            }
        } else {
            check(requestedLocalId == null, "UNEXPECTED_PARAMETER : User ID");
            if (idToken != null || password != null || email != null) {
                check(email != null, "MISSING_EMAIL");
                check(password != null, "MISSING_PASSWORD");
                provider = "password";
            } else {
                provider = "anonymous";
            }
            if (idToken != null) {
                existing = parseIdToken(project, idToken);
            }
        }

        if (email != null) {
            check(isValidEmail(email), "INVALID_EMAIL");
            email = canonicalizeEmail(email);
            StoredUser byEmail = findByEmail(project, email);
            check(byEmail == null || (existing != null && byEmail.getLocalId().equals(existing.getLocalId())),
                    "EMAIL_EXISTS");
        }
        if (password != null) {
            check(password.length() >= PASSWORD_MIN_LENGTH,
                    "WEAK_PASSWORD : Password should be at least 6 characters");
        }

        String nowMs = String.valueOf(Instant.now().toEpochMilli());
        StoredUser user = existing != null ? existing : new StoredUser();
        if (existing == null) {
            user.setLocalId(requestedLocalId != null ? requestedLocalId : randomId(28));
            user.setCreatedAt(nowMs);
            user.setLastLoginAt(nowMs);
        }
        boolean anonymous = "anonymous".equals(provider);
        if (!anonymous) {
            if (email != null) {
                user.setEmail(email);
            }
            if (body.get("displayName") != null) {
                user.setDisplayName(str(body.get("displayName")));
            }
            if (body.get("photoUrl") != null) {
                user.setPhotoUrl(str(body.get("photoUrl")));
            }
        }
        if (privileged) {
            user.setEmailVerified(bool(body.get("emailVerified")));
            if (body.get("disabled") != null) {
                user.setDisabled(bool(body.get("disabled")));
            }
            String phoneNumber = str(body.get("phoneNumber"));
            if (phoneNumber != null) {
                check(phoneNumber.startsWith("+"), "INVALID_PHONE_NUMBER : Invalid format.");
                check(findByPhoneNumber(project, phoneNumber) == null, "PHONE_NUMBER_EXISTS");
                user.setPhoneNumber(phoneNumber);
            }
        } else if (existing == null) {
            user.setEmailVerified(false);
        }
        if (password != null) {
            setPassword(user, password);
            upsertPasswordProvider(user);
        }
        putUser(project, user);
        LOG.debugf("firebaseauth signUp project=%s localId=%s provider=%s", project, user.getLocalId(), provider);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("kind", "identitytoolkit#SignupNewUserResponse");
        response.put("localId", user.getLocalId());
        if (user.getDisplayName() != null) {
            response.put("displayName", user.getDisplayName());
        }
        if (user.getEmail() != null) {
            response.put("email", user.getEmail());
        }
        if (provider != null) {
            response.putAll(issueTokens(project, user, provider, Map.of()));
        }
        return response;
    }

    // ── signInWithPassword ────────────────────────────────────────────────────

    public Map<String, Object> signInWithPassword(String project, Map<String, Object> body) {
        String email = str(body.get("email"));
        String password = str(body.get("password"));
        check(email != null, "MISSING_EMAIL");
        check(isValidEmail(email), "INVALID_EMAIL");
        check(password != null && !password.isEmpty(), "MISSING_PASSWORD");

        StoredUser user = findByEmail(project, canonicalizeEmail(email));
        check(user != null, "EMAIL_NOT_FOUND");
        check(!user.isDisabled(), "USER_DISABLED");
        check(user.getPasswordHash() != null && user.getSalt() != null, "INVALID_PASSWORD");
        check(user.getPasswordHash().equals(hashPassword(password, user.getSalt())), "INVALID_PASSWORD");

        user.setLastLoginAt(String.valueOf(Instant.now().toEpochMilli()));
        putUser(project, user);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("kind", "identitytoolkit#VerifyPasswordResponse");
        response.put("registered", true);
        response.put("localId", user.getLocalId());
        response.put("email", user.getEmail());
        response.putAll(issueTokens(project, user, "password", Map.of()));
        return response;
    }

    // ── signInWithCustomToken ─────────────────────────────────────────────────

    public Map<String, Object> signInWithCustomToken(String project, Map<String, Object> body) {
        String token = str(body.get("token"));
        check(token != null && !token.isEmpty(), "MISSING_CUSTOM_TOKEN");

        Map<String, Object> payload;
        if (token.trim().startsWith("{")) {
            try {
                payload = MAPPER.readValue(token, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                throw badRequest("INVALID_CUSTOM_TOKEN : "
                        + "((Auth Emulator only accepts strict JSON or JWTs as fake custom tokens.))");
            }
        } else {
            payload = FirebaseJwt.decodePayload(token);
            check(payload != null, "INVALID_CUSTOM_TOKEN : Invalid assertion format");
            check(CUSTOM_TOKEN_AUDIENCE.equals(payload.get("aud")),
                    "INVALID_CUSTOM_TOKEN : ((Invalid aud (audience) in custom token.))");
        }

        String localId = str(payload.get("uid") != null ? payload.get("uid") : payload.get("user_id"));
        check(localId != null && !localId.isEmpty(), "MISSING_IDENTIFIER");

        Map<String, Object> extraClaims = Map.of();
        if (payload.containsKey("claims")) {
            extraClaims = validateCustomClaims(payload.get("claims"));
        }

        StoredUser user = getUser(project, localId);
        boolean isNewUser = user == null;
        if (user == null) {
            user = new StoredUser();
            user.setLocalId(localId);
            user.setCreatedAt(String.valueOf(Instant.now().toEpochMilli()));
        } else {
            check(!user.isDisabled(), "USER_DISABLED");
        }
        user.setCustomAuth(true);
        user.setLastLoginAt(String.valueOf(Instant.now().toEpochMilli()));
        putUser(project, user);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("kind", "identitytoolkit#VerifyCustomTokenResponse");
        response.put("isNewUser", isNewUser);
        response.putAll(issueTokens(project, user, "custom", extraClaims));
        return response;
    }

    // ── lookup ────────────────────────────────────────────────────────────────

    public Map<String, Object> lookup(String project, Map<String, Object> body, boolean privileged) {
        List<StoredUser> users = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (privileged) {
            for (String localId : strList(body.get("localId"))) {
                addUser(users, seen, getUser(project, localId));
            }
            for (String email : strList(body.get("email"))) {
                addUser(users, seen, findByEmail(project, canonicalizeEmail(email)));
            }
            for (String phone : strList(body.get("phoneNumber"))) {
                addUser(users, seen, findByPhoneNumber(project, phone));
            }
        } else {
            String idToken = str(body.get("idToken"));
            check(idToken != null, "MISSING_ID_TOKEN");
            addUser(users, seen, parseIdToken(project, idToken));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("kind", "identitytoolkit#GetAccountInfoResponse");
        if (!users.isEmpty()) {
            response.put("users", users);
        }
        return response;
    }

    // ── update (setAccountInfo) ───────────────────────────────────────────────

    public Map<String, Object> update(String project, Map<String, Object> body, boolean privileged) {
        String idToken = str(body.get("idToken"));
        StoredUser user;
        String signInProvider = null;
        if (privileged) {
            String localId = str(body.get("localId"));
            check(localId != null, "MISSING_LOCAL_ID");
            user = getUser(project, localId);
            check(user != null, "USER_NOT_FOUND");
        } else {
            check(idToken != null, "INVALID_REQ_TYPE : Unsupported request parameters.");
            check(body.get("customAttributes") == null, "INSUFFICIENT_PERMISSION");
            check(body.get("disableUser") == null, "OPERATION_NOT_ALLOWED");
            user = parseIdToken(project, idToken);
            signInProvider = user.getPasswordHash() != null ? "password"
                    : Boolean.TRUE.equals(user.getCustomAuth()) ? "custom" : "anonymous";
        }

        boolean revokesTokens = false;

        String email = str(body.get("email"));
        if (email != null) {
            check(isValidEmail(email), "INVALID_EMAIL");
            email = canonicalizeEmail(email);
            if (!email.equals(user.getEmail())) {
                StoredUser byEmail = findByEmail(project, email);
                check(byEmail == null, "EMAIL_EXISTS");
                user.setEmail(email);
                user.setEmailVerified(false);
                revokesTokens = true;
            }
        }
        String password = str(body.get("password"));
        if (password != null) {
            check(password.length() >= PASSWORD_MIN_LENGTH,
                    "WEAK_PASSWORD : Password should be at least 6 characters");
            setPassword(user, password);
            upsertPasswordProvider(user);
            signInProvider = "password";
            revokesTokens = true;
        }
        // Any caller may trigger revocation (validSince = now); only privileged callers
        // may copy a literal validSince value — mirrors the emulator's fieldsToCopy split.
        if (body.get("validSince") != null) {
            revokesTokens = true;
        }
        if (revokesTokens) {
            user.setValidSince(String.valueOf(Instant.now().getEpochSecond()));
        }
        if (body.get("displayName") != null) {
            user.setDisplayName(str(body.get("displayName")));
        }
        if (body.get("photoUrl") != null) {
            user.setPhotoUrl(str(body.get("photoUrl")));
        }
        if (privileged) {
            if (body.get("disableUser") != null) {
                user.setDisabled(bool(body.get("disableUser")));
            }
            if (body.get("emailVerified") != null) {
                user.setEmailVerified(bool(body.get("emailVerified")));
            }
            if (body.get("customAttributes") != null) {
                String serialized = str(body.get("customAttributes"));
                validateSerializedCustomClaims(serialized);
                user.setCustomAttributes(serialized);
            }
            String phoneNumber = str(body.get("phoneNumber"));
            if (phoneNumber != null) {
                check(phoneNumber.startsWith("+"), "INVALID_PHONE_NUMBER : Invalid format.");
                StoredUser byPhone = findByPhoneNumber(project, phoneNumber);
                check(byPhone == null || byPhone.getLocalId().equals(user.getLocalId()), "PHONE_NUMBER_EXISTS");
                user.setPhoneNumber(phoneNumber);
            }
            if (body.get("validSince") != null) {
                user.setValidSince(str(body.get("validSince")));
            }
        }
        for (String attribute : strList(body.get("deleteAttribute"))) {
            switch (attribute) {
                case "DISPLAY_NAME" -> user.setDisplayName(null);
                case "PHOTO_URL" -> user.setPhotoUrl(null);
                case "EMAIL" -> user.setEmail(null);
                case "PASSWORD" -> {
                    user.setPasswordHash(null);
                    user.setSalt(null);
                }
                default -> { }
            }
        }
        for (String providerId : strList(body.get("deleteProvider"))) {
            if ("password".equals(providerId)) {
                user.setEmail(null);
                user.setPasswordHash(null);
                user.setSalt(null);
            } else if ("phone".equals(providerId)) {
                user.setPhoneNumber(null);
            }
            if (user.getProviderUserInfo() != null) {
                user.setProviderUserInfo(user.getProviderUserInfo().stream()
                        .filter(p -> !providerId.equals(p.getProviderId()))
                        .toList());
            }
        }
        putUser(project, user);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("kind", "identitytoolkit#SetAccountInfoResponse");
        response.put("localId", user.getLocalId());
        if (user.getEmail() != null) {
            response.put("email", user.getEmail());
        }
        if (user.getDisplayName() != null) {
            response.put("displayName", user.getDisplayName());
        }
        if (user.getPhotoUrl() != null) {
            response.put("photoUrl", user.getPhotoUrl());
        }
        if (user.getEmailVerified() != null) {
            response.put("emailVerified", user.getEmailVerified());
        }
        if (user.getProviderUserInfo() != null && !user.getProviderUserInfo().isEmpty()) {
            response.put("providerUserInfo", user.getProviderUserInfo());
        }
        if (user.getPasswordHash() != null) {
            response.put("passwordHash", user.getPasswordHash());
        }
        if (revokesTokens && signInProvider != null) {
            response.putAll(issueTokens(project, user, signInProvider, Map.of()));
        }
        return response;
    }

    // ── delete ────────────────────────────────────────────────────────────────

    public Map<String, Object> delete(String project, Map<String, Object> body, boolean privileged) {
        StoredUser user;
        if (privileged && body.get("localId") != null) {
            user = getUser(project, str(body.get("localId")));
            check(user != null, "USER_NOT_FOUND");
        } else {
            String idToken = str(body.get("idToken"));
            check(idToken != null, "MISSING_ID_TOKEN");
            user = parseIdToken(project, idToken);
        }
        userStore.delete(userKey(project, user.getLocalId()));
        LOG.debugf("firebaseauth delete project=%s localId=%s", project, user.getLocalId());
        return Map.of("kind", "identitytoolkit#DeleteAccountResponse");
    }

    // ── batchGet / batchDelete ────────────────────────────────────────────────

    public Map<String, Object> batchGet(String project, int maxResults, String nextPageToken) {
        int effectiveMax = maxResults <= 0 ? 20 : Math.min(maxResults, 1000);
        List<StoredUser> all = listUsers(project).stream()
                .filter(u -> nextPageToken == null || nextPageToken.isEmpty()
                        || u.getLocalId().compareTo(nextPageToken) > 0)
                .toList();
        List<StoredUser> page = all.stream().limit(effectiveMax).toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("kind", "identitytoolkit#DownloadAccountResponse");
        if (!page.isEmpty()) {
            response.put("users", page);
        }
        if (page.size() >= effectiveMax) {
            response.put("nextPageToken", page.get(page.size() - 1).getLocalId());
        }
        return response;
    }

    public Map<String, Object> batchDelete(String project, Map<String, Object> body) {
        List<String> localIds = strList(body.get("localIds"));
        check(!localIds.isEmpty() && localIds.size() <= 1000, "LOCAL_ID_LIST_EXCEEDS_LIMIT");
        boolean force = Boolean.TRUE.equals(bool(body.get("force")));

        List<Map<String, Object>> errors = new ArrayList<>();
        for (int i = 0; i < localIds.size(); i++) {
            StoredUser user = getUser(project, localIds.get(i));
            if (user == null) {
                continue;
            }
            if (!force && !user.isDisabled()) {
                errors.add(Map.of(
                        "index", i,
                        "localId", user.getLocalId(),
                        "message", "NOT_DISABLED : Disable the account before batch deletion."));
                continue;
            }
            userStore.delete(userKey(project, user.getLocalId()));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        if (!errors.isEmpty()) {
            response.put("errors", errors);
        }
        return response;
    }

    // ── securetoken grantToken ────────────────────────────────────────────────

    public Map<String, Object> grantToken(String project, String grantType, String refreshToken) {
        check(grantType != null && !grantType.isEmpty(), "MISSING_GRANT_TYPE");
        check("refresh_token".equals(grantType), "INVALID_GRANT_TYPE");
        check(refreshToken != null && !refreshToken.isEmpty(), "MISSING_REFRESH_TOKEN");

        Map<String, Object> record;
        try {
            // Space is never valid Base64; restore '+' mangled by unescaped form transport.
            record = MAPPER.readValue(Base64.getDecoder().decode(refreshToken.replace(' ', '+')),
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw badRequest("INVALID_REFRESH_TOKEN");
        }
        check(record.containsKey("_AuthEmulatorRefreshToken"), "INVALID_REFRESH_TOKEN");
        check(project.equals(record.get("projectId")), "INVALID_REFRESH_TOKEN");

        StoredUser user = getUser(project, str(record.get("localId")));
        check(user != null, "INVALID_REFRESH_TOKEN");
        check(!user.isDisabled(), "USER_DISABLED");

        @SuppressWarnings("unchecked")
        Map<String, Object> extraClaims = record.get("extraClaims") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        Map<String, Object> tokens = issueTokens(project, user, str(record.get("provider")), extraClaims);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id_token", tokens.get("idToken"));
        response.put("access_token", tokens.get("idToken"));
        response.put("expires_in", tokens.get("expiresIn"));
        response.put("refresh_token", tokens.get("refreshToken"));
        response.put("token_type", "Bearer");
        response.put("user_id", user.getLocalId());
        response.put("project_id", PROJECT_NUMBER);
        return response;
    }

    // ── emulator management ───────────────────────────────────────────────────

    public void deleteAllAccounts(String project) {
        String prefix = "projects/" + project + "/users/";
        for (String key : List.copyOf(userStore.keys())) {
            if (key.startsWith(prefix)) {
                userStore.delete(key);
            }
        }
    }

    // ── tokens ────────────────────────────────────────────────────────────────

    Map<String, Object> issueTokens(String project, StoredUser user, String provider,
                                    Map<String, Object> extraClaims) {
        user.setLastRefreshAt(Instant.now().toString());
        putUser(project, user);

        Map<String, Object> refreshRecord = new LinkedHashMap<>();
        refreshRecord.put("_AuthEmulatorRefreshToken", "DO NOT MODIFY");
        refreshRecord.put("localId", user.getLocalId());
        refreshRecord.put("provider", provider);
        refreshRecord.put("extraClaims", extraClaims);
        refreshRecord.put("projectId", project);
        String refreshToken;
        try {
            refreshToken = Base64.getEncoder().encodeToString(
                    MAPPER.writeValueAsBytes(refreshRecord));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode refresh token", e);
        }

        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("idToken", generateIdToken(project, user, provider, extraClaims));
        tokens.put("refreshToken", refreshToken);
        tokens.put("expiresIn", String.valueOf(TOKEN_EXPIRES_IN_SECONDS));
        return tokens;
    }

    private String generateIdToken(String project, StoredUser user, String provider,
                                   Map<String, Object> extraClaims) {
        long iat = Instant.now().getEpochSecond();
        Map<String, Object> payload = new LinkedHashMap<>();
        if (user.getDisplayName() != null) {
            payload.put("name", user.getDisplayName());
        }
        if (user.getPhotoUrl() != null) {
            payload.put("picture", user.getPhotoUrl());
        }
        if (user.getCustomAttributes() != null) {
            try {
                payload.putAll(MAPPER.readValue(user.getCustomAttributes(),
                        new TypeReference<Map<String, Object>>() {}));
            } catch (Exception ignored) {
                // stored attributes were validated on write
            }
        }
        payload.putAll(extraClaims);
        if (user.getEmail() != null) {
            payload.put("email", user.getEmail());
            payload.put("email_verified", Boolean.TRUE.equals(user.getEmailVerified()));
        }
        if (user.getPhoneNumber() != null) {
            payload.put("phone_number", user.getPhoneNumber());
        }
        if ("anonymous".equals(provider)) {
            payload.put("provider_id", "anonymous");
        }
        payload.put("auth_time", authTimeSeconds(user));
        payload.put("user_id", user.getLocalId());

        Map<String, Object> identities = new LinkedHashMap<>();
        if (user.getEmail() != null) {
            identities.put("email", List.of(user.getEmail()));
        }
        if (user.getProviderUserInfo() != null) {
            for (ProviderUserInfo info : user.getProviderUserInfo()) {
                if (!"password".equals(info.getProviderId()) && info.getRawId() != null) {
                    identities.put(info.getProviderId(), List.of(info.getRawId()));
                }
            }
        }
        Map<String, Object> firebase = new LinkedHashMap<>();
        firebase.put("identities", identities);
        firebase.put("sign_in_provider", provider);
        payload.put("firebase", firebase);

        payload.put("iat", iat);
        payload.put("exp", iat + TOKEN_EXPIRES_IN_SECONDS);
        payload.put("aud", project);
        payload.put("iss", "https://securetoken.google.com/" + project);
        payload.put("sub", user.getLocalId());
        return FirebaseJwt.sign(payload);
    }

    StoredUser parseIdToken(String project, String idToken) {
        Map<String, Object> payload = FirebaseJwt.decodePayload(idToken);
        check(payload != null, "INVALID_ID_TOKEN");
        StoredUser user = getUser(project, str(payload.get("user_id")));
        check(user != null, "USER_NOT_FOUND");
        if (user.getValidSince() != null && payload.get("iat") instanceof Number iat) {
            check(iat.longValue() >= Long.parseLong(user.getValidSince()), "TOKEN_EXPIRED");
        }
        check(!user.isDisabled(), "USER_DISABLED");
        return user;
    }

    // ── claims validation ─────────────────────────────────────────────────────

    private void validateSerializedCustomClaims(String serialized) {
        check(serialized.length() <= 1000, "CLAIMS_TOO_LARGE");
        Object parsed;
        try {
            parsed = MAPPER.readValue(serialized, Object.class);
        } catch (Exception e) {
            throw badRequest("INVALID_CLAIMS");
        }
        validateCustomClaims(parsed);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> validateCustomClaims(Object claims) {
        check(claims instanceof Map, "INVALID_CLAIMS");
        Map<String, Object> map = (Map<String, Object>) claims;
        for (String key : map.keySet()) {
            check(!RESERVED_CLAIMS.contains(key), "FORBIDDEN_CLAIM : " + key);
        }
        return map;
    }

    // ── user store helpers ────────────────────────────────────────────────────

    StoredUser getUser(String project, String localId) {
        if (localId == null) {
            return null;
        }
        return userStore.get(userKey(project, localId)).map(this::parseUser).orElse(null);
    }

    private void putUser(String project, StoredUser user) {
        try {
            userStore.put(userKey(project, user.getLocalId()), MAPPER.writeValueAsString(user));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize user", e);
        }
    }

    StoredUser findByEmail(String project, String email) {
        return listUsers(project).stream()
                .filter(u -> email.equals(u.getEmail()))
                .findFirst()
                .orElse(null);
    }

    private StoredUser findByPhoneNumber(String project, String phoneNumber) {
        return listUsers(project).stream()
                .filter(u -> phoneNumber.equals(u.getPhoneNumber()))
                .findFirst()
                .orElse(null);
    }

    private List<StoredUser> listUsers(String project) {
        String prefix = "projects/" + project + "/users/";
        return userStore.scan(k -> k.startsWith(prefix)).stream()
                .map(this::parseUser)
                .sorted(Comparator.comparing(StoredUser::getLocalId))
                .toList();
    }

    private StoredUser parseUser(String json) {
        try {
            return MAPPER.readValue(json, StoredUser.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse stored user", e);
        }
    }

    private static String userKey(String project, String localId) {
        return "projects/" + project + "/users/" + localId;
    }

    // ── small helpers ─────────────────────────────────────────────────────────

    private void setPassword(StoredUser user, String password) {
        String salt = "fakeSalt" + randomId(20);
        user.setSalt(salt);
        user.setPasswordHash(hashPassword(password, salt));
        user.setPasswordUpdatedAt(Instant.now().toEpochMilli());
        user.setValidSince(String.valueOf(Instant.now().getEpochSecond()));
    }

    private void upsertPasswordProvider(StoredUser user) {
        if (user.getEmail() == null) {
            return;
        }
        List<ProviderUserInfo> providers = new ArrayList<>(
                user.getProviderUserInfo() == null ? List.of() : user.getProviderUserInfo());
        providers.removeIf(p -> "password".equals(p.getProviderId()));
        providers.add(ProviderUserInfo.password(user.getEmail(), user.getDisplayName(), user.getPhotoUrl()));
        user.setProviderUserInfo(providers);
    }

    private static String hashPassword(String password, String salt) {
        return "fakeHash:salt=" + salt + ":password=" + password;
    }

    private static long authTimeSeconds(StoredUser user) {
        if (user.getLastLoginAt() != null) {
            return Long.parseLong(user.getLastLoginAt()) / 1000;
        }
        if (user.getLastRefreshAt() != null) {
            return Instant.parse(user.getLastRefreshAt()).getEpochSecond();
        }
        return Instant.now().getEpochSecond();
    }

    private static boolean isValidEmail(String email) {
        return email.matches("^[^@]+@[^@]+$");
    }

    private static String canonicalizeEmail(String email) {
        return email.toLowerCase();
    }

    static String randomId(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return sb.toString();
    }

    private static void addUser(List<StoredUser> users, Set<String> seen, StoredUser user) {
        if (user != null && seen.add(user.getLocalId())) {
            users.add(user);
        }
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof String s ? s : String.valueOf(value);
    }

    private static Boolean bool(Object value) {
        if (value == null) {
            return false;
        }
        return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream().map(FirebaseAuthService::str).toList();
        }
        return List.of(str(value));
    }
}
