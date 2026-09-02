package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * XML API listing: pagination and the gzip refusal that decides transcoding.
 */
@QuarkusTest
class GcsXmlListRestIntegrationTest {

    private static final String BUCKET = "xml-list-bucket";

    private static void seed() {
        given().contentType("application/json").body("{\"name\":\"" + BUCKET + "\"}")
                .when().post("/storage/v1/b?project=test-project");
        for (String name : new String[] {"a.txt", "b.txt", "c.txt"}) {
            given().contentType("text/plain").body("x")
                    .queryParam("uploadType", "media").queryParam("name", name)
                    .when().post("/upload/storage/v1/b/" + BUCKET + "/o");
        }
    }

    @Test
    void markerStartsListingAfterTheGivenKey() {
        // "Objects whose names are lexicographically greater than the marker are returned"
        // (https://cloud.google.com/storage/docs/xml-api/get-bucket-list). Without marker
        // support a client that pages on IsTruncated re-reads the first page forever.
        seed();
        given().queryParam("marker", "a.txt")
                .when().get("/" + BUCKET)
                .then().statusCode(200)
                .body(not(containsString("<Key>a.txt</Key>")))
                .body(containsString("<Key>b.txt</Key>"))
                .body(containsString("<Key>c.txt</Key>"))
                .body(containsString("<Marker>a.txt</Marker>"));
    }

    @Test
    void truncatedListingCarriesTheNextMarker() {
        seed();
        given().queryParam("max-keys", 1)
                .when().get("/" + BUCKET)
                .then().statusCode(200)
                .body(containsString("<IsTruncated>true</IsTruncated>"))
                .body(containsString("<NextMarker>a.txt</NextMarker>"));
    }

    @Test
    void pagingWithTheNextMarkerTerminates() {
        seed();
        String second = given().queryParam("max-keys", 1).queryParam("marker", "a.txt")
                .when().get("/" + BUCKET)
                .then().statusCode(200)
                .extract().asString();
        // The second page must move on rather than repeating the first.
        org.junit.jupiter.api.Assertions.assertTrue(second.contains("<Key>b.txt</Key>"), second);
        org.junit.jupiter.api.Assertions.assertFalse(second.contains("<Key>a.txt</Key>"), second);
    }

    @Test
    void gzipRefusedWithAZeroQualityIsNotServedAsGzip() {
        // RFC 7231 writes a qvalue as "0" [ "." 0*3DIGIT ], so q=0.0 is the same refusal as q=0.
        given().contentType("application/json").body("{\"name\":\"" + BUCKET + "\"}")
                .when().post("/storage/v1/b?project=test-project");
        given().contentType("text/plain").body("plain-bytes")
                .queryParam("uploadType", "media").queryParam("name", "q0.txt")
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o")
                .then().statusCode(200);

        given().header("Accept-Encoding", "gzip;q=0.0")
                .when().get("/storage/v1/b/" + BUCKET + "/o/q0.txt?alt=media")
                .then().statusCode(200)
                .header("Content-Encoding", (String) null);
    }

    @Test
    void maxKeysBoundsObjectsAndPrefixesTogether() {
        // A page is one run over both, so a delimiter listing cannot emit unlimited prefixes
        // alongside a capped set of objects, and the NextMarker has to name whichever entry
        // ended the page or the next request repeats them.
        given().contentType("application/json").body("{\"name\":\"" + BUCKET + "\"}")
                .when().post("/storage/v1/b?project=test-project");
        for (String name : new String[] {"d1/x.txt", "d2/x.txt", "d3/x.txt"}) {
            given().contentType("text/plain").body("x")
                    .queryParam("uploadType", "media").queryParam("name", name)
                    .when().post("/upload/storage/v1/b/" + BUCKET + "/o");
        }
        String first = given().queryParam("delimiter", "/").queryParam("max-keys", 1)
                .when().get("/" + BUCKET)
                .then().statusCode(200)
                .body(containsString("<IsTruncated>true</IsTruncated>"))
                .extract().asString();
        org.junit.jupiter.api.Assertions.assertTrue(first.contains("<NextMarker>"), first);
        // Exactly one entry on the page, not one object plus every prefix.
        org.junit.jupiter.api.Assertions.assertEquals(1,
                first.split("<CommonPrefixes>", -1).length - 1 + (first.split("<Contents>", -1).length - 1),
                first);
    }

    @Test
    void explicitGzipRefusalBeatsAWildcard() {
        // "gzip;q=0, *" refuses gzip: the specific entry decides (RFC 7231 section 5.3.4).
        given().contentType("application/json").body("{\"name\":\"" + BUCKET + "\"}")
                .when().post("/storage/v1/b?project=test-project");
        given().contentType("text/plain").body("plain")
                .queryParam("uploadType", "media").queryParam("name", "wc.txt")
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o")
                .then().statusCode(200);
        given().header("Accept-Encoding", "gzip;q=0, *")
                .when().get("/storage/v1/b/" + BUCKET + "/o/wc.txt?alt=media")
                .then().statusCode(200)
                .header("Content-Encoding", (String) null);
    }

    @Test
    void pagingFromAPrefixMarkerAdvancesPastThatPrefix() {
        // A NextMarker naming a common prefix has to move the listing past every object under
        // it; comparing against raw object names would re-roll the same prefix forever.
        given().contentType("application/json").body("{\"name\":\"" + BUCKET + "\"}")
                .when().post("/storage/v1/b?project=test-project");
        for (String name : new String[] {"p1/a.txt", "p1/b.txt", "p2/a.txt"}) {
            given().contentType("text/plain").body("x")
                    .queryParam("uploadType", "media").queryParam("name", name)
                    .when().post("/upload/storage/v1/b/" + BUCKET + "/o");
        }
        given().queryParam("delimiter", "/").queryParam("marker", "p1/")
                .when().get("/" + BUCKET)
                .then().statusCode(200)
                .body(not(containsString("<Prefix>p1/</Prefix>")))
                .body(containsString("<Prefix>p2/</Prefix>"));
    }
}
