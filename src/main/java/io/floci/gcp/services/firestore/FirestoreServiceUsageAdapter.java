package io.floci.gcp.services.firestore;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import io.floci.gcp.core.common.ServiceUsageAdapter;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The handlers act on each document a request names ({@code BatchGetDocuments} {@code documents},
 * the {@code writes} of {@code Commit}, {@code Write} and {@code BatchWrite}, a {@code Listen}
 * target) without requiring it to be a child of the request's {@code database}, so every project
 * those names carry is checked, not only the database's. Only fields that name a resource are
 * read.
 */
final class FirestoreServiceUsageAdapter implements ServiceUsageAdapter {

    /** The Firestore protos carry no resource annotations; these are the fields that name one. */
    private static final Set<String> RESOURCE_FIELDS =
            Set.of("database", "parent", "name", "documents", "document", "delete");
    /** Request, then a write or target, then the document it names. */
    private static final int MAX_DEPTH = 3;

    @Override
    public Optional<List<String>> grpcProjects(Message request) {
        Set<String> projects = new LinkedHashSet<>();
        collectProjects(request, projects, MAX_DEPTH);
        return projects.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(projects));
    }

    private static void collectProjects(Message message, Set<String> projects, int depth) {
        for (FieldDescriptor field : message.getDescriptorForType().getFields()) {
            if (field.isMapField()) {
                continue;
            }
            List<?> values = field.isRepeated() ? (List<?>) message.getField(field)
                    : field.getJavaType() != FieldDescriptor.JavaType.MESSAGE || message.hasField(field)
                            ? List.of(message.getField(field)) : List.of();
            for (Object value : values) {
                if (value instanceof String name && RESOURCE_FIELDS.contains(field.getName())) {
                    String project = ServiceUsageAdapter.projectOf(name);
                    if (project != null) {
                        projects.add(project);
                    }
                } else if (value instanceof Message nested && depth > 1) {
                    collectProjects(nested, projects, depth - 1);
                }
            }
        }
    }
}
