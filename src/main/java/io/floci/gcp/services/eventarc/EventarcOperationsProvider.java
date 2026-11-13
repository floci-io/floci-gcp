package io.floci.gcp.services.eventarc;

import io.floci.gcp.core.common.ProtoJson;
import io.floci.gcp.services.operations.LocationOperationsV1Provider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/** Eventarc's operations on the shared v1 route: those whose metadata is Eventarc's {@code OperationMetadata}. */
@ApplicationScoped
public class EventarcOperationsProvider implements LocationOperationsV1Provider {

    private final EventarcService service;

    @Inject
    public EventarcOperationsProvider(EventarcService service) {
        this.service = service;
    }

    @Override
    public Class<?> resourceClass() {
        return EventarcController.class;
    }

    @Override
    public String api() {
        return "eventarc";
    }

    @Override
    public boolean listedWithoutHost() {
        return true;
    }

    @Override
    public Optional<String> find(String name) {
        return service.findOperation(name).map(ProtoJson::print);
    }

    @Override
    public List<Entry> list(String project, String location) {
        return service.listOperations(project, location).stream()
                .map(operation -> new Entry(operation.getName(), ProtoJson.print(operation)))
                .toList();
    }

    @Override
    public void delete(String name) {
        service.deleteOperation(name);
    }

    @Override
    public void cancel(String name) {
        service.cancelOperation(name);
    }
}
