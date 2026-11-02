package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * XML API ListMultipartUploads: the result shape, delimiter grouping, the markers and
 * encoding-type, as GCS serves them.
 */
@QuarkusTest
class GcsXmlListUploadsRestIntegrationTest {

    private static final String RESULT_START = "<ListMultipartUploadsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">";
    private static final String UPLOADS = "ListMultipartUploadsResult.Upload.";

    private static String newBucket(String tag) {
        String name = "xml-uploads-" + tag + "-" + System.nanoTime();
        given().contentType("application/json").body("{\"name\":\"" + name + "\"}")
                .when().post("/storage/v1/b?project=test-project").then().statusCode(200);
        return name;
    }

    /** Starts an upload for an already URL-encoded object name and returns its id. */
    private static String initiate(String bucket, String encodedName) {
        return given().urlEncodingEnabled(false).post("/" + bucket + "/" + encodedName + "?uploads")
                .then().statusCode(200).extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");
    }

    private static Response list(String bucket, String query) {
        return given().urlEncodingEnabled(false).get("/" + bucket + "?uploads" + query);
    }

    private static String body(String bucket, String query) {
        return list(bucket, query).then().statusCode(200).contentType(containsString("text/html")).extract().asString();
    }

    private static void invalid(String bucket, String query, String details) {
        list(bucket, query).then().statusCode(400)
                .body("Error.Code", equalTo("InvalidArgument"))
                .body("Error.Message", equalTo("Invalid argument."))
                .body("Error.Details", equalTo(details));
    }

    @Test
    void listingFollowsTheGcsShape() {
        String bucket = newBucket("shape");
        String a = initiate(bucket, "a.txt");
        String x = initiate(bucket, "d1/x.txt");
        String body = body(bucket, "&prefix=");
        assertTrue(body.startsWith(RESULT_START + "<Bucket>" + bucket + "</Bucket><KeyMarker></KeyMarker>"
                + "<UploadIdMarker></UploadIdMarker><NextKeyMarker></NextKeyMarker><Prefix></Prefix>"
                + "<NextUploadIdMarker></NextUploadIdMarker><MaxUploads>1000</MaxUploads><IsTruncated>false</IsTruncated>"
                + "<Upload><Key>a.txt</Key><UploadId>" + a + "</UploadId><StorageClass>STANDARD</StorageClass><Initiated>"), body);
        assertTrue(body.contains("<Upload><Key>d1/x.txt</Key><UploadId>" + x + "</UploadId>"), body);
        // Prefix and Delimiter appear only when they were sent.
        String bare = body(bucket, "");
        assertTrue(bare.contains("<NextKeyMarker></NextKeyMarker><NextUploadIdMarker></NextUploadIdMarker>"), bare);
        assertFalse(bare.contains("<Prefix>"), bare);
        assertFalse(bare.contains("<Delimiter>"), bare);
    }

    @Test
    void delimiterRollsUploadsIntoCommonPrefixesAfterTheUploads() {
        String bucket = newBucket("delim");
        initiate(bucket, "a.txt");
        initiate(bucket, "d1/x.txt");
        initiate(bucket, "d1/y.txt");
        initiate(bucket, "d2/z.txt");
        initiate(bucket, "z.txt");
        Response grouped = list(bucket, "&delimiter=/");
        String body = grouped.then().statusCode(200).extract().asString();
        assertTrue(body.contains("<NextKeyMarker></NextKeyMarker><Delimiter>/</Delimiter><NextUploadIdMarker></NextUploadIdMarker>"), body);
        assertEquals(List.of("a.txt", "z.txt"), grouped.xmlPath().getList(UPLOADS + "Key"));
        assertTrue(body.endsWith("</Upload><CommonPrefixes><Prefix>d1/</Prefix></CommonPrefixes>"
                + "<CommonPrefixes><Prefix>d2/</Prefix></CommonPrefixes></ListMultipartUploadsResult>"), body);
        Response under = list(bucket, "&prefix=d1/&delimiter=/");
        assertEquals(List.of("d1/x.txt", "d1/y.txt"), under.xmlPath().getList(UPLOADS + "Key"));
        assertFalse(under.asString().contains("<CommonPrefixes>"), under.asString());
        // An empty delimiter is echoed and groups nothing.
        Response flat = list(bucket, "&delimiter=");
        assertTrue(flat.asString().contains("<Delimiter></Delimiter>"), flat.asString());
        assertEquals(5, flat.xmlPath().getList(UPLOADS + "Key").size());
    }

    @Test
    void pagesFollowTheKeyAndUploadIdMarkers() {
        String bucket = newBucket("markers");
        String a = initiate(bucket, "a.txt");
        List<String> b = new ArrayList<>(List.of(initiate(bucket, "b.txt"), initiate(bucket, "b.txt")));
        Collections.sort(b);
        String c = initiate(bucket, "c.txt");
        String first = body(bucket, "&max-uploads=2");
        assertTrue(first.contains("<NextKeyMarker>b.txt</NextKeyMarker><NextUploadIdMarker>" + b.get(0) + "</NextUploadIdMarker>"
                + "<MaxUploads>2</MaxUploads><IsTruncated>true</IsTruncated><Upload><Key>a.txt</Key><UploadId>" + a + "</UploadId>"), first);
        String second = body(bucket, "&max-uploads=2&key-marker=b.txt&upload-id-marker=" + b.get(0));
        assertTrue(second.contains("<KeyMarker>b.txt</KeyMarker><UploadIdMarker>" + b.get(0) + "</UploadIdMarker>"
                + "<NextKeyMarker></NextKeyMarker><NextUploadIdMarker></NextUploadIdMarker><MaxUploads>2</MaxUploads>"
                + "<IsTruncated>false</IsTruncated><Upload><Key>b.txt</Key><UploadId>" + b.get(1) + "</UploadId>"), second);
        assertTrue(second.contains("<Upload><Key>c.txt</Key><UploadId>" + c + "</UploadId>"), second);
        // A key-marker alone skips every upload of that key.
        assertEquals(List.of(c), list(bucket, "&key-marker=b.txt").xmlPath().getList(UPLOADS + "UploadId"));
        // An upload-id-marker without a key-marker is ignored and not echoed.
        Response ignored = list(bucket, "&upload-id-marker=" + b.get(0));
        assertEquals(4, ignored.xmlPath().getList(UPLOADS + "UploadId").size());
        assertTrue(ignored.asString().contains("<UploadIdMarker></UploadIdMarker>"), ignored.asString());
        assertTrue(body(bucket, "&key-marker=zzz").endsWith("<IsTruncated>false</IsTruncated></ListMultipartUploadsResult>"));
        assertTrue(body(bucket, "&max-uploads=0").endsWith("<MaxUploads>0</MaxUploads><IsTruncated>false</IsTruncated>"
                + "</ListMultipartUploadsResult>"));
    }

    @Test
    void aPageThatEndsOnACommonPrefixIsRejectedLikeGcs() {
        String bucket = newBucket("prefixpage");
        initiate(bucket, "a.txt");
        initiate(bucket, "d1/x.txt");
        initiate(bucket, "d1/y.txt");
        initiate(bucket, "z.txt");
        list(bucket, "&delimiter=/&max-uploads=2").then().statusCode(400)
                .body("Error.Code", equalTo("InvalidArgument"))
                .body("Error.Message", equalTo("Invalid argument."))
                .body(not(containsString("<Details>")));
        assertTrue(body(bucket, "&delimiter=/&max-uploads=1").contains("<NextKeyMarker>a.txt</NextKeyMarker>"));
        assertTrue(body(bucket, "&delimiter=/&max-uploads=3").contains("<IsTruncated>false</IsTruncated>"));
    }

    @Test
    void parametersAreValidatedTheWayGcsDoes() {
        String bucket = newBucket("validation");
        invalid(bucket, "&max-uploads=-1", "Invalid max uploads: -1");
        invalid(bucket, "&max-uploads=abc", "Parameter max-uploads must be an integer; received abc.");
        invalid(bucket, "&max-uploads=", "Parameter max-uploads must be an integer; received .");
        invalid(bucket, "&upload-id-marker=", "Invalid upload ID marker: ");
        invalid(bucket, "&encoding-type=URL", "Invalid encoding type: URL");
        invalid(bucket, "&encoding-type=", "Invalid encoding type: ");
        assertTrue(body(bucket, "&max-uploads=5000").contains("<MaxUploads>1000</MaxUploads>"));
    }

    @Test
    void urlEncodingAppliesToEveryEchoedName() {
        String bucket = newBucket("url");
        initiate(bucket, "a.txt");
        initiate(bucket, "e%20f.txt");
        initiate(bucket, "s%20d/c.txt");
        String body = body(bucket, "&delimiter=/&encoding-type=url&prefix=&key-marker=a%20b.txt");
        assertTrue(body.contains("<KeyMarker>a+b.txt</KeyMarker><UploadIdMarker></UploadIdMarker><NextKeyMarker></NextKeyMarker>"
                + "<Prefix></Prefix><Delimiter>/</Delimiter>"), body);
        assertTrue(body.contains("<Upload><Key>e+f.txt</Key>"), body);
        assertTrue(body.endsWith("<CommonPrefixes><Prefix>s+d/</Prefix></CommonPrefixes><EncodingType>url</EncodingType>"
                + "</ListMultipartUploadsResult>"), body);
        String under = body(bucket, "&prefix=s%20d/&encoding-type=url");
        assertTrue(under.contains("<Prefix>s+d/</Prefix>"), under);
        assertTrue(under.contains("<Upload><Key>s+d/c.txt</Key>"), under);
        String truncated = body(bucket, "&max-uploads=1&key-marker=a.txt&encoding-type=url");
        assertTrue(truncated.contains("<NextKeyMarker>e+f.txt</NextKeyMarker>"), truncated);
    }
}
