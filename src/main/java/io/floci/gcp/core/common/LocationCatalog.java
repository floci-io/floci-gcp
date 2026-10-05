package io.floci.gcp.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Real GCP regions and zones, loaded from the bundled {@code locations/gcp-locations.json}
 * (sourced from https://cloud.google.com/compute/docs/regions-zones).
 */
@ApplicationScoped
public class LocationCatalog {

    static final String RESOURCE = "locations/gcp-locations.json";
    public static final String GLOBAL = "global";
    public static final String WILDCARD = "-";
    public static final Set<String> KMS_MULTI_REGIONS = Set.of("us", "europe", "asia");

    public enum Kind { REGION, ZONE, GLOBAL, KMS_MULTI_REGION }

    private final boolean strict;
    private final Map<String, List<String>> zonesByRegion;
    private final Map<String, String> regionByZone;
    private final Map<String, String> regionLocations;
    private final Map<String, List<String>> multiRegions;
    private final Map<String, List<String>> predefinedDualRegions;

    @Inject
    public LocationCatalog(EmulatorConfig config) {
        this(config.locations().strict());
    }

    LocationCatalog(boolean strict) {
        this.strict = strict;
        JsonNode root = load();
        this.zonesByRegion = stringLists(root.path("regions"));
        this.multiRegions = stringLists(root.path("multiRegions"));
        this.predefinedDualRegions = stringLists(root.path("predefinedDualRegions"));
        Map<String, String> locations = new LinkedHashMap<>();
        root.path("regionLocations").properties().forEach(e -> locations.put(e.getKey(), e.getValue().asText()));
        this.regionLocations = Collections.unmodifiableMap(locations);
        Map<String, String> byZone = new LinkedHashMap<>();
        zonesByRegion.forEach((region, zones) -> zones.forEach(zone -> byZone.put(zone, region)));
        this.regionByZone = Collections.unmodifiableMap(byZone);
    }

    public static LocationCatalog lenient() {
        return new LocationCatalog(false);
    }

    public static LocationCatalog strictCatalog() {
        return new LocationCatalog(true);
    }

    public boolean strict() {
        return strict;
    }

    public List<String> regions() {
        return List.copyOf(zonesByRegion.keySet());
    }

    public List<String> zones() {
        return List.copyOf(regionByZone.keySet());
    }

    public List<String> zones(String region) {
        return zonesByRegion.getOrDefault(region, List.of());
    }

    public boolean isRegion(String location) {
        return location != null && zonesByRegion.containsKey(location);
    }

    public boolean isZone(String location) {
        return location != null && regionByZone.containsKey(location);
    }

    public Optional<String> regionOfZone(String zone) {
        return Optional.ofNullable(zone == null ? null : regionByZone.get(zone));
    }

    /** Nearby city for a region, the first part of the "City, Area, Geography" location. */
    public Optional<String> displayName(String region) {
        String location = regionLocations.get(region);
        if (location == null) {
            return Optional.empty();
        }
        int comma = location.indexOf(',');
        return Optional.of(comma < 0 ? location : location.substring(0, comma));
    }

    public Map<String, List<String>> multiRegions() {
        return multiRegions;
    }

    public Map<String, List<String>> predefinedDualRegions() {
        return predefinedDualRegions;
    }

    public boolean accepts(String location, Kind... allowed) {
        if (location == null || location.isBlank()) {
            return false;
        }
        for (Kind kind : allowed) {
            boolean match = switch (kind) {
                case REGION -> isRegion(location);
                case ZONE -> isZone(location);
                case GLOBAL -> GLOBAL.equals(location);
                case KMS_MULTI_REGION -> KMS_MULTI_REGIONS.contains(location);
            };
            if (match) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rejects {@code location} with INVALID_ARGUMENT when strict mode is on and the location is not
     * one of the allowed kinds. Lenient mode (the default) accepts any value.
     */
    public void requireLocation(String location, Kind... allowed) {
        if (strict && !accepts(location, allowed)) {
            throw GcpException.invalidArgument("Invalid location: " + location);
        }
    }

    /** Same as {@link #requireLocation} for list calls: the {@code -} wildcard is never checked. */
    public void requireListLocation(String location, Kind... allowed) {
        if (!WILDCARD.equals(location)) {
            requireLocation(location, allowed);
        }
    }

    /** Same as {@link #requireLocation} for a {@code projects/{project}/locations/{location}} parent. */
    public void requireParentLocation(String parent, Kind... allowed) {
        if (!strict) {
            return;
        }
        requireLocation(locationOf(parent), allowed);
    }

    public static String locationOf(String name) {
        if (name == null) {
            return null;
        }
        int start = name.indexOf("/locations/");
        if (start < 0) {
            return null;
        }
        start += "/locations/".length();
        int end = name.indexOf('/', start);
        return end < 0 ? name.substring(start) : name.substring(start, end);
    }

    private static JsonNode load() {
        try (InputStream in = LocationCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + RESOURCE);
            }
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, List<String>> stringLists(JsonNode node) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        node.properties().forEach(e -> {
            List<String> values = new ArrayList<>();
            e.getValue().forEach(v -> values.add(v.asText()));
            result.put(e.getKey(), List.copyOf(values));
        });
        return Collections.unmodifiableMap(result);
    }
}
