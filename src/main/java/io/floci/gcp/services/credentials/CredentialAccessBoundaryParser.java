package io.floci.gcp.services.credentials;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.core.common.GcpException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class CredentialAccessBoundaryParser {

	static final String LEGACY_OBJECT_READER = "inRole:roles/storage.legacyObjectReader";
	static final String OBJECT_VIEWER = "inRole:roles/storage.objectViewer";
	static final String LEGACY_BUCKET_WRITER = "inRole:roles/storage.legacyBucketWriter";

	private static final Set<String> SUPPORTED_PERMISSIONS = Set.of(
			LEGACY_OBJECT_READER,
			OBJECT_VIEWER,
			LEGACY_BUCKET_WRITER);
	private static final Pattern RESOURCE_PATTERN =
			Pattern.compile("^//storage\\.googleapis\\.com/projects/_/buckets/([^/]+)$");
	private static final Pattern RESOURCE_NAME_PREFIX_PATTERN =
			Pattern.compile("^resource\\.name\\.startsWith\\((.+)\\)$");
	private static final Pattern LIST_PREFIX_PATTERN =
			Pattern.compile("^api\\.getAttribute\\((.+),\\s*(.+)\\)\\.startsWith\\((.+)\\)$");

	private final ObjectMapper objectMapper;

	@Inject
	public CredentialAccessBoundaryParser(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public List<CredentialAccessBoundaryRule> parse(String options) {
		if (options == null || options.isBlank()) {
			throw invalidGrant("options is required");
		}

		JsonNode root;
		try {
			root = objectMapper.readTree(options);
		} catch (IOException e) {
			throw invalidGrant("options must be valid Credential Access Boundary JSON");
		}

		JsonNode rulesNode = root.path("accessBoundary").path("accessBoundaryRules");
		if (!rulesNode.isArray() || rulesNode.isEmpty()) {
			throw invalidGrant("accessBoundaryRules is required");
		}

		List<CredentialAccessBoundaryRule> rules = new ArrayList<>();
		for (JsonNode ruleNode : rulesNode) {
			String bucket = parseBucket(requiredText(ruleNode, "availableResource"));
			List<String> permissions = parsePermissions(ruleNode.path("availablePermissions"));
			String expression = ruleNode.path("availabilityCondition").path("expression").asText(null);
			if (expression == null || expression.isBlank()) {
				throw invalidGrant("availabilityCondition.expression is required");
			}
			String prefix = parsePrefixExpression(bucket, expression);
			rules.add(new CredentialAccessBoundaryRule(bucket, prefix, permissions));
		}
		return rules;
	}

	private static String requiredText(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || !value.isTextual() || value.asText().isBlank()) {
			throw invalidGrant(field + " is required");
		}
		return value.asText();
	}

	private static String parseBucket(String availableResource) {
		Matcher matcher = RESOURCE_PATTERN.matcher(availableResource);
		if (!matcher.matches() || matcher.group(1).isBlank()) {
			throw invalidGrant("unsupported availableResource");
		}
		return matcher.group(1);
	}

	private static List<String> parsePermissions(JsonNode permissionsNode) {
		if (!permissionsNode.isArray() || permissionsNode.isEmpty()) {
			throw invalidGrant("availablePermissions is required");
		}
		List<String> permissions = new ArrayList<>();
		for (JsonNode permissionNode : permissionsNode) {
			if (!permissionNode.isTextual()) {
				throw invalidGrant("availablePermissions entries must be strings");
			}
			String permission = permissionNode.asText();
			if (!SUPPORTED_PERMISSIONS.contains(permission)) {
				throw invalidGrant("unsupported availablePermission");
			}
			permissions.add(permission);
		}
		return permissions;
	}

	private static String parsePrefixExpression(String bucket, String expression) {
		Set<String> prefixes = new LinkedHashSet<>();
		for (String part : splitOrExpression(expression)) {
			prefixes.add(parseSingleExpression(bucket, part.trim()));
		}
		if (prefixes.size() != 1) {
			throw invalidGrant("all supported expressions must use the same object prefix");
		}
		return prefixes.iterator().next();
	}

	private static List<String> splitOrExpression(String expression) {
		List<String> parts = new ArrayList<>();
		int start = 0;
		Character quote = null;
		boolean escaped = false;
		for (int i = 0; i < expression.length(); i++) {
			char ch = expression.charAt(i);
			if (escaped) {
				escaped = false;
				continue;
			}
			if (quote != null) {
				if (ch == '\\') {
					escaped = true;
				} else if (ch == quote) {
					quote = null;
				}
				continue;
			}
			if (ch == '\'' || ch == '"') {
				quote = ch;
			} else if (ch == '|' && i + 1 < expression.length() && expression.charAt(i + 1) == '|') {
				parts.add(expression.substring(start, i));
				start = i + 2;
				i++;
			}
		}
		if (quote != null) {
			throw invalidGrant("unterminated string literal in expression");
		}
		parts.add(expression.substring(start));
		return parts;
	}

	private static String parseSingleExpression(String bucket, String expression) {
		Matcher resourceMatcher = RESOURCE_NAME_PREFIX_PATTERN.matcher(expression);
		if (resourceMatcher.matches()) {
			String resourcePrefix = parseOnlyStringArgument(resourceMatcher.group(1));
			String expectedStart = "projects/_/buckets/" + bucket + "/objects/";
			if (!resourcePrefix.startsWith(expectedStart)) {
				throw invalidGrant("resource.name prefix does not match availableResource bucket");
			}
			return normalizePrefix(resourcePrefix.substring(expectedStart.length()));
		}

		Matcher listMatcher = LIST_PREFIX_PATTERN.matcher(expression);
		if (listMatcher.matches()) {
			String attribute = parseOnlyStringArgument(listMatcher.group(1));
			String defaultValue = parseOnlyStringArgument(listMatcher.group(2));
			String objectPrefix = parseOnlyStringArgument(listMatcher.group(3));
			if (!"storage.googleapis.com/objectListPrefix".equals(attribute) || !defaultValue.isEmpty()) {
				throw invalidGrant("unsupported api.getAttribute expression");
			}
			return normalizePrefix(objectPrefix);
		}

		throw invalidGrant("unsupported availabilityCondition expression");
	}

	private static String parseOnlyStringArgument(String argument) {
		String trimmed = argument.trim();
		if (trimmed.length() < 2) {
			throw invalidGrant("expected string literal");
		}
		char quote = trimmed.charAt(0);
		if ((quote != '\'' && quote != '"') || trimmed.charAt(trimmed.length() - 1) != quote) {
			throw invalidGrant("expected string literal");
		}
		return decodeCelString(trimmed.substring(1, trimmed.length() - 1));
	}

	private static String decodeCelString(String value) {
		StringBuilder decoded = new StringBuilder(value.length());
		boolean escaped = false;
		for (int i = 0; i < value.length(); i++) {
			char ch = value.charAt(i);
			if (!escaped) {
				if (ch == '\\') {
					escaped = true;
				} else {
					decoded.append(ch);
				}
				continue;
			}
			switch (ch) {
				case '\\' -> decoded.append('\\');
				case '\'' -> decoded.append('\'');
				case '"' -> decoded.append('"');
				case 'n' -> decoded.append('\n');
				case 'r' -> decoded.append('\r');
				case 't' -> decoded.append('\t');
				case 'b' -> decoded.append('\b');
				case 'f' -> decoded.append('\f');
				default -> throw invalidGrant("unsupported string escape");
			}
			escaped = false;
		}
		if (escaped) {
			throw invalidGrant("unterminated string escape");
		}
		return decoded.toString();
	}

	private static String normalizePrefix(String prefix) {
		if (prefix == null || prefix.isBlank()) {
			throw invalidGrant("object prefix is required");
		}
		return prefix.endsWith("/") ? prefix : prefix + "/";
	}

	private static GcpException invalidGrant(String message) {
		return GcpException.invalidArgument(message).withReason("invalid_grant");
	}
}
