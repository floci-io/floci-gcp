package io.floci.gcp.services.locations;

import com.google.cloud.functions.v2.Environment;
import com.google.cloud.kms.v1.LocationMetadata;
import com.google.cloud.location.ListLocationsResponse;
import com.google.cloud.location.Location;
import com.google.protobuf.Any;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.LocationCatalog;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.lifecycle.GrpcServerManager;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The {@code google.cloud.location.Locations} mixin shared by every regional API. On a single
 * port the API is only known from the request host. Service-specific {@code LocationMetadata}
 * (KMS, Cloud Functions) is added only when the first host label names that API; KMS's
 * {@code global} and multi-region locations are also listed when no API can be identified, so
 * SDKs pointed at the emulator, and gRPC callers, can resolve them.
 */
@ApplicationScoped
public class LocationsService {

    static final String REGION_LABEL = "cloud.googleapis.com/region";
    static final String KMS_API = "cloudkms";
    static final String FUNCTIONS_API = "cloudfunctions";
    // First host labels of the APIs that serve this mixin. Any other host (an emulator endpoint
    // such as localhost) and every gRPC call identify no API, so they get the union of locations.
    static final Set<String> MIXIN_APIS = Set.of("cloudkms", "cloudfunctions", "cloudtasks",
            "cloudscheduler", "secretmanager", "eventarc", "managedkafka", "run");

    private final LocationCatalog catalog;
    private final GrpcServerManager grpcServerManager;

    @Inject
    public LocationsService(LocationCatalog catalog, GrpcServerManager grpcServerManager) {
        this.catalog = catalog;
        this.grpcServerManager = grpcServerManager;
    }

    LocationsService(LocationCatalog catalog) {
        this(catalog, null);
    }

    void onStart(@Observes StartupEvent ev) {
        grpcServerManager.bind(new LocationsGrpcController(this));
    }

    public ListLocationsResponse listLocations(String project, String api, int pageSize, String pageToken) {
        requireProject(project);
        List<Location> all = new ArrayList<>();
        for (String id : locationIds(api)) {
            all.add(location(project, id, api));
        }
        PageToken.Page<Location> page = PageToken.paginate(all, pageSize, pageToken);
        ListLocationsResponse.Builder response = ListLocationsResponse.newBuilder().addAllLocations(page.items());
        if (page.nextPageToken() != null) {
            response.setNextPageToken(page.nextPageToken());
        }
        return response.build();
    }

    public Location getLocation(String project, String locationId, String api) {
        requireProject(project);
        if (!locationIds(api).contains(locationId)) {
            throw GcpException.notFound("Location projects/" + project + "/locations/" + locationId + " not found.");
        }
        return location(project, locationId, api);
    }

    public Location getLocationByName(String name, String api) {
        String[] parts = name == null ? new String[0] : name.split("/");
        if (parts.length != 4 || !"projects".equals(parts[0]) || !"locations".equals(parts[2])) {
            throw GcpException.invalidArgument("Invalid location name: " + name);
        }
        return getLocation(parts[1], parts[3], api);
    }

    public ListLocationsResponse listLocationsByName(String name, String api, int pageSize, String pageToken) {
        String[] parts = name == null ? new String[0] : name.split("/");
        if (parts.length != 2 || !"projects".equals(parts[0])) {
            throw GcpException.invalidArgument("Invalid parent name: " + name);
        }
        return listLocations(parts[1], api, pageSize, pageToken);
    }

    private List<String> locationIds(String api) {
        List<String> ids = new ArrayList<>(catalog.regions());
        if (KMS_API.equals(api) || api == null || !MIXIN_APIS.contains(api)) {
            ids.add(LocationCatalog.GLOBAL);
            ids.addAll(List.of("asia", "europe", "us"));
        }
        return ids;
    }

    private Location location(String project, String id, String api) {
        Location.Builder location = Location.newBuilder()
                .setName("projects/" + project + "/locations/" + id)
                .setLocationId(id)
                .setDisplayName(catalog.displayName(id).orElse(id));
        if (catalog.isRegion(id)) {
            location.putLabels(REGION_LABEL, id);
        }
        if (KMS_API.equals(api)) {
            location.setMetadata(Any.pack(LocationMetadata.newBuilder()
                    .setHsmAvailable(false)
                    .setEkmAvailable(false)
                    .build()));
        } else if (FUNCTIONS_API.equals(api)) {
            // Cloud Functions' LocationMetadata shares its simple name with the imported KMS one.
            location.setMetadata(Any.pack(com.google.cloud.functions.v2.LocationMetadata.newBuilder()
                    .addEnvironments(Environment.GEN_2)
                    .build()));
        }
        return location.build();
    }

    private static void requireProject(String project) {
        if (project == null || project.isBlank()) {
            throw GcpException.invalidArgument("Project is required");
        }
    }

    static String apiFromHost(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        String value = host;
        int colon = value.indexOf(':');
        if (colon >= 0) {
            value = value.substring(0, colon);
        }
        int dot = value.indexOf('.');
        return dot >= 0 ? value.substring(0, dot) : value;
    }
}
