package io.floci.gcp.services.cloudlogging;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.logging.v2.ListLogEntriesRequest;
import com.google.logging.v2.LogEntry;
import com.google.logging.v2.WriteLogEntriesRequest;
import com.google.protobuf.Message;
import io.floci.gcp.core.common.ServiceUsageAdapter;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Logging names its projects where the generic resolution does not look: every entry of a write
 * carries its own {@code log_name}, which wins over the request's, and the REST entries methods
 * name theirs in the JSON body.
 */
final class CloudLoggingServiceUsageAdapter implements ServiceUsageAdapter {

    @Override
    public Optional<List<String>> grpcProjects(Message request) {
        if (request instanceof WriteLogEntriesRequest write) {
            Set<String> names = new LinkedHashSet<>();
            for (LogEntry entry : write.getEntriesList()) {
                names.add(entry.getLogName().isEmpty() ? write.getLogName() : entry.getLogName());
            }
            return Optional.of(projects(names));
        }
        if (request instanceof ListLogEntriesRequest list) {
            return Optional.of(projects(list.getResourceNamesList()));
        }
        return Optional.empty();
    }

    @Override
    public Optional<List<String>> restProjects(RestRequest request) {
        if (request.path().endsWith("/entries:write")) {
            JsonNode body = request.jsonBody();
            String defaultLogName = body.path("logName").asText("");
            Set<String> names = new LinkedHashSet<>();
            for (JsonNode entry : body.path("entries")) {
                String logName = entry.path("logName").asText("");
                names.add(logName.isBlank() ? defaultLogName : logName);
            }
            return Optional.of(projects(names));
        }
        if (request.path().endsWith("/entries:list")) {
            Set<String> names = new LinkedHashSet<>();
            for (JsonNode name : request.jsonBody().path("resourceNames")) {
                names.add(name.asText(""));
            }
            return Optional.of(projects(names));
        }
        return Optional.empty();
    }

    private static List<String> projects(Collection<String> resourceNames) {
        Set<String> projects = new LinkedHashSet<>();
        for (String name : resourceNames) {
            String project = ServiceUsageAdapter.projectOf(name);
            if (project != null) {
                projects.add(project);
            }
        }
        return List.copyOf(projects);
    }
}
