package io.floci.gcp.services.gcs;

import io.floci.gcp.core.common.XmlBuilder;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

final class GcsSignedUrl {

    private static final DateTimeFormatter V4_DATE_FORMATTER = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final long MAX_EXPIRES_SECONDS = 604_800;

    private GcsSignedUrl() {}

    static void checkNotExpired(UriInfo uriInfo) {
        String signedDate = firstQueryParameter(uriInfo, "X-Goog-Date");
        String signedTtl = firstQueryParameter(uriInfo, "X-Goog-Expires");
        if (signedDate == null || signedTtl == null) {
            return;
        }

        Instant expiresAt;
        long ttlSeconds;
        try {
            expiresAt = V4_DATE_FORMATTER.parse(signedDate, Instant::from);
        }
        catch (DateTimeParseException e) {
            throw malformedSecurityHeader("Date not in valid ISO 8601 format.", "Date");
        }
        try {
            ttlSeconds = Long.parseLong(signedTtl);
        }
        catch (NumberFormatException e) {
            throw malformedSecurityHeader("Expires must be between 1 and 604800.", "Expires");
        }
        if (ttlSeconds < 1 || ttlSeconds > MAX_EXPIRES_SECONDS) {
            throw malformedSecurityHeader("Expires must be between 1 and 604800.", "Expires");
        }

        expiresAt = expiresAt.plusSeconds(ttlSeconds);
        if (Instant.now().isAfter(expiresAt)) {
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                    .type(MediaType.APPLICATION_XML)
                    .entity(xmlError("ExpiredToken", "The provided token has expired.", null, null))
                    .build());
        }
    }

    private static String firstQueryParameter(UriInfo uriInfo, String name) {
        for (Map.Entry<String, List<String>> entry : uriInfo.getQueryParameters().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                return entry.getValue().getFirst();
            }
        }
        return null;
    }

    private static WebApplicationException malformedSecurityHeader(String details, String parameterName) {
        return new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_XML)
                .entity(xmlError("MalformedSecurityHeader", "Your request has a malformed header.", details, parameterName))
                .build());
    }

    private static String xmlError(String code, String message, String details, String parameterName) {
        return new XmlBuilder()
                .start("Error")
                .elem("Code", code)
                .elem("Message", message)
                .elem("Details", details)
                .elem("ParameterName", parameterName)
                .end("Error")
                .build();
    }
}
