package io.floci.gcp.test;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code objects.list} filters beyond {@code prefix}: {@code endOffset}, {@code matchGlob} and
 * {@code includeTrailingDelimiter}.
 *
 * <p>The glob cases are the ones worth pinning: {@code *} stays inside one path segment while
 * {@code **} crosses them, a distinction a naive translation to {@code .*} collapses.
 */
class GcsListFiltersTest {

    private static final String BUCKET = TestFixtures.uniqueName("list-filters-bucket");
    private static final List<String> OBJECTS = List.of(
            "logs/",
            "logs/app.log",
            "logs/app.txt",
            "logs/2026/01/app.log",
            "logs/2026/02/app.log",
            "metrics/cpu.log");

    private static Storage storage;

    @BeforeAll
    static void setUp() {
        storage = TestFixtures.storageClient();
        storage.create(BucketInfo.of(BUCKET));
        for (String name : OBJECTS) {
            storage.create(BlobInfo.newBuilder(BlobId.of(BUCKET, name)).build(), new byte[0]);
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (storage instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    @Test
    void endOffsetIsExclusiveAndPairsWithStartOffset() {
        // "Objects whose names are lexicographically before endOffset", with startOffset
        // inclusive and endOffset exclusive.
        assertThat(names(Storage.BlobListOption.endOffset("logs/app.log")))
                .contains("logs/", "logs/2026/01/app.log", "logs/2026/02/app.log")
                .doesNotContain("logs/app.log", "logs/app.txt", "metrics/cpu.log");

        assertThat(names(
                Storage.BlobListOption.startOffset("logs/app.log"),
                Storage.BlobListOption.endOffset("logs/app.txt")))
                .containsExactly("logs/app.log");
    }

    @Test
    void matchGlobKeepsSingleStarWithinOnePathSegment() {
        assertThat(names(Storage.BlobListOption.matchGlob("logs/*.log")))
                .containsExactly("logs/app.log");

        assertThat(names(Storage.BlobListOption.matchGlob("logs/**.log")))
                .containsExactlyInAnyOrder(
                        "logs/app.log", "logs/2026/01/app.log", "logs/2026/02/app.log");

        assertThat(names(Storage.BlobListOption.matchGlob("**/*.log")))
                .contains("logs/2026/01/app.log", "metrics/cpu.log");
    }

    @Test
    void includeTrailingDelimiterAddsThePlaceholderObjectToItems() throws Exception {
        // Driven raw: google-cloud-storage 2.47.0 exposes no BlobListOption for this parameter,
        // so the SDK cannot send it. Without the flag "logs/" only rolls up into prefixes[] and
        // its own metadata is invisible; with it the placeholder is additionally in items[].
        String withoutFlag = listRaw("delimiter=%2F");
        assertThat(withoutFlag).contains("\"prefixes\"");
        assertThat(itemNames(withoutFlag)).doesNotContain("logs/");

        String withFlag = listRaw("delimiter=%2F&includeTrailingDelimiter=true");
        assertThat(itemNames(withFlag)).contains("logs/");
    }

    private static String listRaw(String query) throws Exception {
        URI uri = URI.create(TestFixtures.endpoint() + "/storage/v1/b/" + BUCKET + "/o?" + query);
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    /** Object names from the items[] array only, ignoring anything under prefixes[]. */
    private static List<String> itemNames(String body) {
        Matcher matcher = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static List<String> names(Storage.BlobListOption... options) {
        return StreamSupport.stream(
                        storage.list(BUCKET, options).iterateAll().spliterator(), false)
                .map(Blob::getName)
                .toList();
    }
}
