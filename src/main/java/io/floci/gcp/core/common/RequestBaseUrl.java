package io.floci.gcp.core.common;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.UriInfo;

import java.net.URI;

/**
 * Resolves the base URL a client should use to reach the emulator back.
 *
 * <p>Services mint absolute URLs into their responses, the resumable upload session
 * {@code Location}, {@code selfLink}, {@code mediaLink}, and the client dials whatever comes
 * back. Those URLs have to name the address the caller actually reached, which is not
 * necessarily the one the emulator listens on: under {@code docker run -p 9000:4588} the
 * published port differs from the internal one.
 *
 * <p><strong>Read the authority from the request URI, not from the {@code Host} header.</strong>
 * HTTP/2 carries the authority in the {@code :authority} pseudo-header and sends no {@code Host}
 * header at all, so a Host-only lookup returns null on every HTTP/2 request and silently falls
 * back to the configured base URL. floci-gcp negotiates HTTP/2 by ALPN on the same port it
 * serves HTTP/1.1 on, so both shapes arrive on every deployment: a client that upgrades (Java's
 * {@code HttpClient} does by default) would be handed session URLs pointing at whatever
 * {@code floci-gcp.base-url} names. {@link io.floci.gcp.core.common.routing.ServiceRoutingFilter}
 * and {@code CloudRunUrlRoutingFilter} already fall back this way; this centralises the same
 * rule for the URL-minting paths.
 */
public final class RequestBaseUrl {

    private RequestBaseUrl() {
    }

    /**
     * @param uriInfo            the request's URI info, the authoritative source of the authority
     * @param headers            request headers, consulted only if the request URI has no authority
     * @param configuredBaseUrl  {@code floci-gcp.base-url}, the fallback and the scheme to use
     * @param configuredPort     {@code floci-gcp.port}, used when the authority carries no port
     */
    public static String resolve(UriInfo uriInfo, HttpHeaders headers,
            String configuredBaseUrl, int configuredPort) {
        String authority = requestAuthority(uriInfo, headers);
        if (authority == null || authority.isBlank()) {
            return configuredBaseUrl;
        }

        URI configured = configuredBaseUrl != null ? URI.create(configuredBaseUrl) : null;
        String scheme = configured != null && configured.getScheme() != null
                ? configured.getScheme()
                : requestScheme(uriInfo);

        if (hasPort(authority)) {
            return scheme + "://" + authority;
        }
        // A portless authority is legal, a proxy fronting the emulator on 80 or 443 sends one , 
        // but dropping the port here would produce a session URL the client cannot dial back.
        int port = configured != null && configured.getPort() >= 0 ? configured.getPort() : configuredPort;
        return scheme + "://" + authority + ":" + port;
    }

    private static String requestAuthority(UriInfo uriInfo, HttpHeaders headers) {
        if (uriInfo != null && uriInfo.getRequestUri() != null) {
            String authority = uriInfo.getRequestUri().getRawAuthority();
            if (authority != null && !authority.isBlank()) {
                return authority;
            }
        }
        return headers != null ? headers.getHeaderString(HttpHeaders.HOST) : null;
    }

    private static String requestScheme(UriInfo uriInfo) {
        if (uriInfo != null && uriInfo.getBaseUri() != null && uriInfo.getBaseUri().getScheme() != null) {
            return uriInfo.getBaseUri().getScheme();
        }
        return "http";
    }

    /** True when the authority already carries an explicit port, IPv6 literals included. */
    private static boolean hasPort(String authority) {
        if (authority.startsWith("[")) {
            return authority.indexOf("]:") > 0;
        }
        return authority.indexOf(':') >= 0;
    }
}
