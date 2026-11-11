package io.floci.gcp.services.operations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.GcpExceptionMapper;
import io.floci.gcp.core.common.LocationCatalog;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.common.ServiceUsageGate;
import io.floci.gcp.services.locations.LocationsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The {@code google.longrunning.Operations} mixin at {@code /v1/projects/{p}/locations/{l}/operations},
 * shared by every v1 API that binds it there (Eventarc, Managed Kafka). On real GCP the host picks
 * the API; here one route serves them all. Get, delete and cancel go to the API that owns the
 * operation name (names are unique). List answers for one API only, since an SDK client cannot read
 * another API's metadata types: the API named by a {@code *.googleapis.com} host, or Eventarc when the
 * host names none (floci's own endpoint). Each answer is gated by the owning service, as its own
 * routes are.
 */
@Path("/v1/projects/{project}/locations/{location}/operations")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class LocationOperationsV1Controller {

    private final Instance<LocationOperationsV1Provider> providers;
    private final ServiceRegistry serviceRegistry;
    private final ServiceUsageGate serviceUsageGate;
    private final LocationCatalog locations;
    private final ObjectMapper mapper;

    @Inject
    public LocationOperationsV1Controller(Instance<LocationOperationsV1Provider> providers,
                                          ServiceRegistry serviceRegistry,
                                          ServiceUsageGate serviceUsageGate,
                                          LocationCatalog locations,
                                          ObjectMapper mapper) {
        this.providers = providers;
        this.serviceRegistry = serviceRegistry;
        this.serviceUsageGate = serviceUsageGate;
        this.locations = locations;
        this.mapper = mapper;
    }

    @GET
    public Response list(@PathParam("project") String project,
                         @PathParam("location") String location,
                         @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                         @QueryParam("pageToken") String pageToken,
                         @Context HttpHeaders headers,
                         @Context UriInfo uriInfo) {
        locations.requireListLocation(location, LocationCatalog.Kind.REGION);
        String api = LocationsService.apiFromHost(host(headers, uriInfo));
        List<LocationOperationsV1Provider> serving = providers.stream()
                .filter(provider -> provider.api().equals(api))
                .toList();
        if (serving.isEmpty()) {
            serving = providers.stream().filter(LocationOperationsV1Provider::listedWithoutHost).toList();
        }
        List<LocationOperationsV1Provider.Entry> all = new ArrayList<>();
        for (LocationOperationsV1Provider provider : serving) {
            Optional<Response> refused = refusal(provider, project);
            if (refused.isPresent()) {
                return refused.get();
            }
            all.addAll(provider.list(project, location));
        }
        all.sort(Comparator.comparing(LocationOperationsV1Provider.Entry::name));
        PageToken.Page<LocationOperationsV1Provider.Entry> page = PageToken.paginate(all, pageSize, pageToken);

        ObjectNode body = mapper.createObjectNode();
        if (!page.items().isEmpty()) {
            ArrayNode operations = body.putArray("operations");
            for (LocationOperationsV1Provider.Entry entry : page.items()) {
                operations.add(parse(entry.json()));
            }
        }
        if (page.nextPageToken() != null) {
            body.put("nextPageToken", page.nextPageToken());
        }
        return json(body.toString());
    }

    @GET
    @Path("/{operation}")
    public Response get(@PathParam("project") String project,
                        @PathParam("location") String location,
                        @PathParam("operation") String operation) {
        String name = name(project, location, operation);
        Optional<Owner> found = owner(name);
        if (found.isEmpty()) {
            return unowned(project, name);
        }
        Owner owner = found.get();
        return refusal(owner.provider(), project).orElseGet(() -> json(owner.json()));
    }

    @DELETE
    @Path("/{operation}")
    public Response delete(@PathParam("project") String project,
                           @PathParam("location") String location,
                           @PathParam("operation") String operation) {
        String name = name(project, location, operation);
        Optional<Owner> found = owner(name);
        if (found.isEmpty()) {
            return unowned(project, name);
        }
        Owner owner = found.get();
        return refusal(owner.provider(), project).orElseGet(() -> {
            owner.provider().delete(name);
            return json("{}");
        });
    }

    @POST
    @Path("/{operation}:cancel")
    @Consumes(MediaType.WILDCARD)
    public Response cancel(@PathParam("project") String project,
                           @PathParam("location") String location,
                           @PathParam("operation") String operation) {
        String name = name(project, location, operation);
        Optional<Owner> found = owner(name);
        if (found.isEmpty()) {
            return unowned(project, name);
        }
        Owner owner = found.get();
        return refusal(owner.provider(), project).orElseGet(() -> {
            owner.provider().cancel(name);
            return json("{}");
        });
    }

    private Optional<Owner> owner(String name) {
        for (LocationOperationsV1Provider provider : providers) {
            Optional<String> json = provider.find(name);
            if (json.isPresent()) {
                return Optional.of(new Owner(provider, json.get()));
            }
        }
        return Optional.empty();
    }

    /**
     * A name no API owns is a 404 while any API on the route is served; when every one is refused
     * (disabled, or not enabled for the project), the route answers as the first one's own routes would.
     */
    private Response unowned(String project, String name) {
        Optional<Response> first = Optional.empty();
        for (LocationOperationsV1Provider provider : providers) {
            Optional<Response> refused = refusal(provider, project);
            if (refused.isEmpty()) {
                throw GcpException.notFound("Operation not found: " + name);
            }
            if (first.isEmpty()) {
                first = refused;
            }
        }
        return first.orElseThrow(() -> GcpException.notFound("Operation not found: " + name));
    }

    /** What {@code ServiceEnabledFilter} would answer on the owning service's own routes. */
    private Optional<Response> refusal(LocationOperationsV1Provider provider, String project) {
        Optional<ServiceDescriptor> descriptor = serviceRegistry.byResourceClass(provider.resourceClass());
        if (descriptor.isEmpty()) {
            return Optional.of(disabled(provider.api()));
        }
        if (!descriptor.get().enabled()) {
            return Optional.of(disabled(descriptor.get().name()));
        }
        if (serviceUsageGate.enforced() && serviceUsageGate.gates(descriptor.get())
                && serviceUsageGate.isDenied(descriptor.get(), project)) {
            return Optional.of(serviceUsageGate.restResponse(descriptor.get(), project));
        }
        return Optional.empty();
    }

    private static Response disabled(String serviceName) {
        return Response.status(503)
                .type(MediaType.APPLICATION_JSON)
                .entity(new GcpExceptionMapper.ErrorWrapper(
                        GcpExceptionMapper.ErrorDetail.of(503, "Service " + serviceName + " is not enabled.", "UNAVAILABLE")))
                .build();
    }

    private ObjectNode parse(String json) {
        try {
            return (ObjectNode) mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw GcpException.internal("Stored operation is not JSON: " + e.getOriginalMessage());
        }
    }

    private static String host(HttpHeaders headers, UriInfo uriInfo) {
        String host = headers.getHeaderString(HttpHeaders.HOST);
        return host == null || host.isBlank() ? uriInfo.getRequestUri().getHost() : host;
    }

    private static String name(String project, String location, String operation) {
        return "projects/" + project + "/locations/" + location + "/operations/" + operation;
    }

    private static Response json(String json) {
        return Response.ok(json, MediaType.APPLICATION_JSON).build();
    }

    private record Owner(LocationOperationsV1Provider provider, String json) {}
}
