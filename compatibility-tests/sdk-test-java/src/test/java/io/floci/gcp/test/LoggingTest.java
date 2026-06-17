package io.floci.gcp.test;

import com.google.api.MonitoredResource;
import com.google.cloud.logging.v2.LoggingClient;
import com.google.logging.type.LogSeverity;
import com.google.logging.v2.DeleteLogRequest;
import com.google.logging.v2.ListLogEntriesRequest;
import com.google.logging.v2.LogEntry;
import com.google.logging.v2.WriteLogEntriesRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LoggingTest {

    private static final String PROJECT_ID = TestFixtures.projectId();
    private static final String LOG_ID = TestFixtures.uniqueName("test-log");
    private static final String LOG_NAME = "projects/" + PROJECT_ID + "/logs/" + LOG_ID;

    private static LoggingClient client;

    @BeforeAll
    static void setUp() throws IOException {
        client = TestFixtures.loggingClient();
    }

    @AfterAll
    static void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    private static LogEntry entry(LogSeverity severity, String text) {
        return LogEntry.newBuilder()
                .setLogName(LOG_NAME)
                .setResource(MonitoredResource.newBuilder().setType("global").build())
                .setSeverity(severity)
                .setTextPayload(text)
                .build();
    }

    @Test
    @Order(1)
    void writeLogEntries() {
        client.writeLogEntries(WriteLogEntriesRequest.newBuilder()
                .addEntries(entry(LogSeverity.INFO, "info-message"))
                .addEntries(entry(LogSeverity.ERROR, "error-message"))
                .build());
    }

    @Test
    @Order(2)
    void listAllEntries() {
        List<LogEntry> entries = new ArrayList<>();
        client.listLogEntries(ListLogEntriesRequest.newBuilder()
                        .addResourceNames("projects/" + PROJECT_ID)
                        .setFilter("logName=\"" + LOG_NAME + "\"")
                        .build())
                .iterateAll().forEach(entries::add);

        assertThat(entries).hasSize(2);
        assertThat(entries).allMatch(e -> e.getResource().getType().equals("global"));
        assertThat(entries).anyMatch(e -> e.getTextPayload().equals("info-message")
                && e.getSeverity() == LogSeverity.INFO);
        assertThat(entries).anyMatch(e -> e.getTextPayload().equals("error-message")
                && e.getSeverity() == LogSeverity.ERROR);
    }

    @Test
    @Order(3)
    void listWithSeverityFilter() {
        List<LogEntry> entries = new ArrayList<>();
        client.listLogEntries(ListLogEntriesRequest.newBuilder()
                        .addResourceNames("projects/" + PROJECT_ID)
                        .setFilter("logName=\"" + LOG_NAME + "\" AND severity>=WARNING")
                        .build())
                .iterateAll().forEach(entries::add);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getSeverity()).isEqualTo(LogSeverity.ERROR);
        assertThat(entries.get(0).getTextPayload()).isEqualTo("error-message");
    }

    @Test
    @Order(4)
    void listLogs() {
        List<String> logNames = new ArrayList<>();
        client.listLogs("projects/" + PROJECT_ID).iterateAll().forEach(logNames::add);
        assertThat(logNames).contains(LOG_NAME);
    }

    @Test
    @Order(5)
    void deleteLog() {
        client.deleteLog(DeleteLogRequest.newBuilder().setLogName(LOG_NAME).build());

        List<String> logNames = new ArrayList<>();
        client.listLogs("projects/" + PROJECT_ID).iterateAll().forEach(logNames::add);
        assertThat(logNames).doesNotContain(LOG_NAME);
    }
}
