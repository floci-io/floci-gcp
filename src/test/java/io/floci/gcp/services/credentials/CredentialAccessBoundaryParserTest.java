package io.floci.gcp.services.credentials;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.core.common.GcpException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CredentialAccessBoundaryParserTest {

	private final CredentialAccessBoundaryParser parser = new CredentialAccessBoundaryParser(new ObjectMapper());

	@Test
	void parsesReadRuleWithLegacyObjectReader() {
		List<CredentialAccessBoundaryRule> rules = parser.parse(cab(
				"bucket",
				CredentialAccessBoundaryParser.LEGACY_OBJECT_READER,
				"resource.name.startsWith('projects/_/buckets/bucket/objects/data/')"));

		assertEquals(1, rules.size());
		assertEquals("bucket", rules.getFirst().getBucket());
		assertEquals("data/", rules.getFirst().getObjectPrefix());
		assertEquals(List.of(CredentialAccessBoundaryParser.LEGACY_OBJECT_READER),
				rules.getFirst().getAvailablePermissions());
	}

	@Test
	void parsesListRuleWithObjectViewer() {
		List<CredentialAccessBoundaryRule> rules = parser.parse(cab(
				"bucket",
				CredentialAccessBoundaryParser.OBJECT_VIEWER,
				"api.getAttribute('storage.googleapis.com/objectListPrefix', '').startsWith('data/')"));

		assertEquals("data/", rules.getFirst().getObjectPrefix());
		assertEquals(List.of(CredentialAccessBoundaryParser.OBJECT_VIEWER),
				rules.getFirst().getAvailablePermissions());
	}

	@Test
	void parsesWriteRuleWithLegacyBucketWriter() {
		List<CredentialAccessBoundaryRule> rules = parser.parse(cab(
				"bucket",
				CredentialAccessBoundaryParser.LEGACY_BUCKET_WRITER,
				"resource.name.startsWith('projects/_/buckets/bucket/objects/out')"));

		assertEquals("out/", rules.getFirst().getObjectPrefix());
	}

	@Test
	void parsesOrExpressionWithSamePrefix() {
		List<CredentialAccessBoundaryRule> rules = parser.parse(cab(
				"bucket",
				CredentialAccessBoundaryParser.OBJECT_VIEWER,
				"resource.name.startsWith('projects/_/buckets/bucket/objects/data/')"
						+ " || api.getAttribute('storage.googleapis.com/objectListPrefix', '').startsWith('data/')"));

		assertEquals("data/", rules.getFirst().getObjectPrefix());
	}

	@Test
	void preservesPrefixBoundaries() {
		List<CredentialAccessBoundaryRule> rules = parser.parse(cab(
				"bucket",
				CredentialAccessBoundaryParser.LEGACY_OBJECT_READER,
				"resource.name.startsWith('projects/_/buckets/bucket/objects/data')"));

		assertEquals("data/", rules.getFirst().getObjectPrefix());
	}

	@Test
	void decodesEscapedStrings() {
		List<CredentialAccessBoundaryRule> rules = parser.parse(cab(
				"bucket",
				CredentialAccessBoundaryParser.LEGACY_OBJECT_READER,
				"resource.name.startsWith('projects/_/buckets/bucket/objects/dir\\\\quoted\\'/')"));

		assertEquals("dir\\quoted'/", rules.getFirst().getObjectPrefix());
	}

	@Test
	void rejectsUnsupportedResource() {
		assertInvalidGrant(cab(
				"bucket",
				CredentialAccessBoundaryParser.LEGACY_OBJECT_READER,
				"resource.name.startsWith('projects/_/buckets/bucket/objects/data/')")
				.replace("storage.googleapis.com", "pubsub.googleapis.com"));
	}

	@Test
	void rejectsUnsupportedPermission() {
		assertInvalidGrant(cab(
				"bucket",
				"inRole:roles/storage.admin",
				"resource.name.startsWith('projects/_/buckets/bucket/objects/data/')"));
	}

	@Test
	void rejectsUnsupportedExpression() {
		assertInvalidGrant(cab(
				"bucket",
				CredentialAccessBoundaryParser.LEGACY_OBJECT_READER,
				"resource.name == 'projects/_/buckets/bucket/objects/data/file'"));
	}

	@Test
	void rejectsMalformedJson() {
		assertInvalidGrant("{not-json");
	}

	@Test
	void rejectsExpressionsWithDifferentPrefixes() {
		assertInvalidGrant(cab(
				"bucket",
				CredentialAccessBoundaryParser.OBJECT_VIEWER,
				"resource.name.startsWith('projects/_/buckets/bucket/objects/data/')"
						+ " || api.getAttribute('storage.googleapis.com/objectListPrefix', '').startsWith('other/')"));
	}

	private static void assertInvalidGrant(String options) {
		GcpException ex = assertThrows(GcpException.class,
				() -> new CredentialAccessBoundaryParser(new ObjectMapper()).parse(options));

		assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
		assertEquals("invalid_grant", ex.getReason());
	}

	static String cab(String bucket, String permission, String expression) {
		return """
				{
				  "accessBoundary": {
					"accessBoundaryRules": [
					  {
						"availableResource": "//storage.googleapis.com/projects/_/buckets/%s",
						"availablePermissions": ["%s"],
						"availabilityCondition": {
						  "expression": "%s"
						}
					  }
					]
				  }
				}
				""".formatted(bucket, permission, expression.replace("\\", "\\\\").replace("\"", "\\\""));
	}
}
