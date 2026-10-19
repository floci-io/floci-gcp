package io.floci.gcp.core.common;

import com.google.api.ResourceDescriptor;
import com.google.api.ResourceProto;
import com.google.api.ResourceReference;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds the fields of a request message that name resources, as the API declares them rather than
 * by the shape of their values:
 * <ul>
 *   <li>a string field annotated with {@code google.api.resource_reference};</li>
 *   <li>the name field of a message annotated with {@code google.api.resource};</li>
 *   <li>on the request itself only, the fields every API uses for its target ({@code name},
 *       {@code parent}, {@code resource}, {@code project}, {@code project_id}) and repeated string
 *       fields, where an API leaves them unannotated.</li>
 * </ul>
 * Other strings, such as an object name or a filter, are never read.
 */
public final class ProtoResourceFields {

    /**
     * @param field the proto field name
     * @param type  the declared resource type, such as {@code storage.googleapis.com/Bucket}, or
     *              null for an unannotated field of the request
     * @param top   whether the field belongs to the request itself
     */
    public record Ref(String field, String type, boolean top, String value) {}

    private record Named(FieldDescriptor field, String type) {}

    private record Plan(List<Named> annotated, List<FieldDescriptor> bare, List<FieldDescriptor> messages) {}

    private static final int MAX_DEPTH = 4;
    private static final Set<String> TARGET_FIELDS = Set.of("name", "parent", "resource", "project", "project_id");
    private static final Map<Descriptor, Plan> PLANS = new ConcurrentHashMap<>();

    private ProtoResourceFields() {}

    public static List<Ref> refs(Message message) {
        List<Ref> refs = new ArrayList<>();
        collect(readable(message), true, MAX_DEPTH, refs);
        return refs;
    }

    /**
     * A generated message answers {@code getField} by calling its generated getters through
     * reflection, which a native image does not register for every API's messages. A
     * {@link DynamicMessage} of the same bytes reads its fields from the descriptor alone.
     */
    private static Message readable(Message message) {
        if (message instanceof DynamicMessage) {
            return message;
        }
        try {
            return DynamicMessage.parseFrom(message.getDescriptorForType(), message.toByteString());
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("A message failed to parse its own bytes", e);
        }
    }

    private static void collect(Message message, boolean top, int depth, List<Ref> refs) {
        Plan plan = PLANS.computeIfAbsent(message.getDescriptorForType(), ProtoResourceFields::plan);
        for (Named named : plan.annotated()) {
            read(message, named.field(), named.type(), top, refs);
        }
        if (top) {
            for (FieldDescriptor field : plan.bare()) {
                read(message, field, null, true, refs);
            }
        }
        if (depth <= 1) {
            return;
        }
        for (FieldDescriptor field : plan.messages()) {
            if (field.isRepeated()) {
                for (Object nested : (List<?>) message.getField(field)) {
                    collect((Message) nested, false, depth - 1, refs);
                }
            } else if (message.hasField(field)) {
                collect((Message) message.getField(field), false, depth - 1, refs);
            }
        }
    }

    private static void read(Message message, FieldDescriptor field, String type, boolean top, List<Ref> refs) {
        List<?> values = field.isRepeated() ? (List<?>) message.getField(field) : List.of(message.getField(field));
        for (Object value : values) {
            if (!((String) value).isEmpty()) {
                refs.add(new Ref(field.getName(), type, top, (String) value));
            }
        }
    }

    private static Plan plan(Descriptor descriptor) {
        ResourceDescriptor resource = descriptor.getOptions().hasExtension(ResourceProto.resource)
                ? descriptor.getOptions().getExtension(ResourceProto.resource) : null;
        String nameField = resource == null ? null
                : resource.getNameField().isEmpty() ? "name" : resource.getNameField();
        List<Named> annotated = new ArrayList<>();
        List<FieldDescriptor> bare = new ArrayList<>();
        List<FieldDescriptor> messages = new ArrayList<>();
        for (FieldDescriptor field : descriptor.getFields()) {
            if (field.isMapField()) {
                continue;
            }
            if (field.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                messages.add(field);
            } else if (field.getJavaType() == FieldDescriptor.JavaType.STRING) {
                if (field.getOptions().hasExtension(ResourceProto.resourceReference)) {
                    ResourceReference reference = field.getOptions().getExtension(ResourceProto.resourceReference);
                    annotated.add(new Named(field,
                            reference.getType().isEmpty() ? reference.getChildType() : reference.getType()));
                } else if (field.getName().equals(nameField)) {
                    annotated.add(new Named(field, resource.getType()));
                } else if (TARGET_FIELDS.contains(field.getName()) || field.isRepeated()) {
                    bare.add(field);
                }
            }
        }
        return new Plan(List.copyOf(annotated), List.copyOf(bare), List.copyOf(messages));
    }
}
