package io.floci.gcp.services.gcs;

import io.floci.gcp.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
@Path("/batch/storage/v1")
public class GcsBatchController {

    private static final Logger LOG = Logger.getLogger(GcsBatchController.class);
    private static final Pattern BOUNDARY_PATTERN = Pattern.compile("boundary=([^\\s;\"]+|\"[^\"]+\")");

    private final EmulatorConfig config;
    private final HttpClient httpClient;

    @Inject
    public GcsBatchController(EmulatorConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

	@POST
	@Consumes(MediaType.WILDCARD)
	@Produces(MediaType.WILDCARD)
	public Response batch(@HeaderParam("Content-Type") String contentType,
			@HeaderParam("Authorization") String authorization,
			@Context UriInfo uriInfo, byte[] body) {
		String boundary = extractBoundary(contentType);
		if (boundary == null) {
			return Response.status(400).entity("Missing multipart boundary").build();
		}

		List<SubRequest> subRequests = parseMultipart(boundary, body);
		LOG.debugf("batch: parsed %d sub-requests", subRequests.size());

		String responseBoundary = "batch_" + UUID.randomUUID().toString().replace("-", "");
		StringBuilder responseBody = new StringBuilder();

		for (int i = 0; i < subRequests.size(); i++) {
			SubRequest req = subRequests.get(i);
			SubResponse resp = dispatch(req, authorization, requestPort(uriInfo));
			appendResponsePart(responseBody, responseBoundary, i, req.contentId(), resp);
		}
		responseBody.append("--").append(responseBoundary).append("--\r\n");

		return Response.ok(responseBody.toString())
				.type("multipart/mixed; boundary=" + responseBoundary)
				.build();
	}

    private String extractBoundary(String contentType) {
        if (contentType == null) return null;
        Matcher m = BOUNDARY_PATTERN.matcher(contentType);
        if (!m.find()) return null;
        return m.group(1).replace("\"", "");
    }

    private List<SubRequest> parseMultipart(String boundary, byte[] bodyBytes) {
        String body = new String(bodyBytes, StandardCharsets.UTF_8);
        String delimiter = "--" + boundary;
        List<SubRequest> requests = new ArrayList<>();

        int pos = body.indexOf(delimiter);
        while (pos >= 0) {
            int partStart = pos + delimiter.length();
            // Skip the closing boundary
            if (partStart < body.length() && body.startsWith("--", partStart)) {
                break;
            }
            // Skip line ending after boundary
            if (partStart < body.length() && body.charAt(partStart) == '\r') partStart++;
            if (partStart < body.length() && body.charAt(partStart) == '\n') partStart++;

            int nextBoundary = body.indexOf("\r\n" + delimiter, partStart);
            if (nextBoundary < 0) nextBoundary = body.indexOf("\n" + delimiter, partStart);
            if (nextBoundary < 0) break;

            String part = body.substring(partStart, nextBoundary);
            SubRequest req = parsePart(part);
            if (req != null) {
                requests.add(req);
            }
            pos = nextBoundary + (body.startsWith("\r\n" + delimiter, nextBoundary) ? 2 : 1) + delimiter.length();
            pos -= delimiter.length(); // will be re-added in next iteration
            pos = body.indexOf(delimiter, nextBoundary + 1);
        }
        return requests;
    }

    private SubRequest parsePart(String part) {
        // Find blank line separating MIME headers from the embedded HTTP request
        int blankCrlf = part.indexOf("\r\n\r\n");
        int blankLf = part.indexOf("\n\n");
        int blankEnd;
        if (blankCrlf >= 0 && (blankLf < 0 || blankCrlf <= blankLf)) {
            blankEnd = blankCrlf + 4;
        } else if (blankLf >= 0) {
            blankEnd = blankLf + 2;
        } else {
            return null;
        }

        String mimeHeaders = part.substring(0, blankEnd);
        String contentId = extractContentId(mimeHeaders);
        String httpPart = part.substring(blankEnd).stripTrailing();

        return parseHttpSubRequest(httpPart, contentId);
    }

    private String extractContentId(String mimeHeaders) {
        for (String line : mimeHeaders.split("\r?\n")) {
            if (line.toLowerCase().startsWith("content-id:")) {
                return line.substring("content-id:".length()).trim();
            }
        }
        return null;
    }

    private SubRequest parseHttpSubRequest(String httpText, String contentId) {
        if (httpText == null || httpText.isBlank()) return null;
        String[] lines = httpText.split("\r?\n", -1);
        if (lines.length == 0 || lines[0].isBlank()) return null;

        String[] requestLine = lines[0].trim().split(" ", 3);
        if (requestLine.length < 2) return null;

        String method = requestLine[0].trim();
        String pathAndQuery = requestLine[1].trim();

        Map<String, String> headers = new LinkedHashMap<>();
        int i = 1;
        while (i < lines.length && !lines[i].isBlank()) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon).trim(),
                        lines[i].substring(colon + 1).trim());
            }
            i++;
        }
        i++; // skip blank line

        StringBuilder bodyBuilder = new StringBuilder();
        while (i < lines.length) {
            if (bodyBuilder.length() > 0) bodyBuilder.append("\n");
            bodyBuilder.append(lines[i]);
            i++;
        }
        String requestBody = bodyBuilder.toString().strip();
        return new SubRequest(method, pathAndQuery, headers, requestBody, contentId);
	    }

	private int requestPort(UriInfo uriInfo) {
		if (uriInfo != null && uriInfo.getBaseUri() != null && uriInfo.getBaseUri().getPort() > 0) {
			return uriInfo.getBaseUri().getPort();
		}
		return config.port();
	}

	private SubResponse dispatch(SubRequest req, String outerAuthorization, int port) {
		try {
			String path = req.path();
			URI uri = path.startsWith("http://") || path.startsWith("https://")
					? rewriteToLocalhost(URI.create(path), port)
					: URI.create("http://localhost:" + port + path);

            HttpRequest.BodyPublisher bodyPublisher = req.body().isEmpty()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(req.body());

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(30))
                    .method(req.method(), bodyPublisher);

            req.headers().forEach((k, v) -> {
                if (!k.equalsIgnoreCase("host") && !k.equalsIgnoreCase("content-length")
                        && !k.equalsIgnoreCase("transfer-encoding")) {
                    try {
                        builder.header(k, v);
                    } catch (IllegalArgumentException ignored) {
                        // skip restricted headers
                    }
                }
            });
			if (outerAuthorization != null && req.headers().keySet().stream()
					.noneMatch(header -> header.equalsIgnoreCase("Authorization"))) {
				builder.header("Authorization", outerAuthorization);
			}
            if (!req.body().isEmpty() && !req.headers().containsKey("Content-Type")) {
                builder.header("Content-Type", "application/json");
            }

            HttpResponse<String> resp = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());

            LOG.debugf("batch dispatch %s %s → %d", req.method(), req.path(), resp.statusCode());
            return new SubResponse(resp.statusCode(), resp.body());
        } catch (Exception e) {
            LOG.errorf("batch dispatch error %s %s: %s", req.method(), req.path(), e.getMessage());
            return new SubResponse(500,
                    "{\"error\":{\"code\":500,\"message\":\"Internal batch error\"}}");
        }
	}

	private URI rewriteToLocalhost(URI original, int port) {
		try {
			return new URI("http", null, "localhost", port,
					original.getRawPath(), original.getRawQuery(), null);
		} catch (Exception e) {
			return URI.create("http://localhost:" + port + original.getRawPath()
					+ (original.getRawQuery() != null ? "?" + original.getRawQuery() : ""));
		}
	}

    private void appendResponsePart(StringBuilder sb, String boundary, int index,
            String contentId, SubResponse resp) {
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Type: application/http\r\n");
        String responseId = contentId != null
                ? "<response-" + contentId.replaceAll("[<>]", "") + ">"
                : "<response-" + index + ">";
        sb.append("Content-ID: ").append(responseId).append("\r\n");
        sb.append("\r\n");
        sb.append("HTTP/1.1 ").append(resp.statusCode()).append(" ")
                .append(statusText(resp.statusCode())).append("\r\n");
        String body = resp.body();
        if (body != null && !body.isBlank()) {
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            sb.append("Content-Type: application/json; charset=UTF-8\r\n");
            sb.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
            sb.append("\r\n");
            sb.append(body).append("\r\n");
        } else {
            sb.append("\r\n");
        }
    }

    private static String statusText(int code) {
        return switch (code) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 304 -> "Not Modified";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 409 -> "Conflict";
            case 412 -> "Precondition Failed";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            default -> "Unknown";
        };
    }

    record SubRequest(String method, String path, Map<String, String> headers,
                      String body, String contentId) {}

    record SubResponse(int statusCode, String body) {}
}
