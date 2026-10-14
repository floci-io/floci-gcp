package io.floci.gcp.core.common;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Known GCP projects, keyed by project ID. By default any project ID in use is registered
 * as an implicit ACTIVE project on first sight; with {@code floci-gcp.projects.strict=true}
 * only the default project and projects made through Resource Manager exist.
 */
@ApplicationScoped
public class ProjectRegistry {

    public static final String PERMISSION_DENIED_MESSAGE = "The caller does not have permission";

    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern PROJECT_ID = Pattern.compile("[a-z][a-z0-9-]{4,28}[a-z0-9]");

    private final StorageBackend<String, ProjectRecord> store;
    private final String defaultProjectId;
    private final boolean strict;

    @Inject
    public ProjectRegistry(StorageFactory storageFactory, EmulatorConfig config) {
        this(storageFactory.createGlobal("projects", "projects.json",
                        new TypeReference<Map<String, ProjectRecord>>() {}),
                config.defaultProjectId(), config.projects().strict());
    }

    ProjectRegistry(StorageBackend<String, ProjectRecord> store, String defaultProjectId, boolean strict) {
        this.store = store;
        this.defaultProjectId = defaultProjectId;
        this.strict = strict;
    }

    public boolean strict() {
        return strict;
    }

    public static boolean isNumber(String value) {
        return value != null && NUMBER.matcher(value).matches();
    }

    /** Project ID rules for {@code projects.create}: 6-30 of [a-z0-9-], starting with a letter, no trailing hyphen. */
    public static boolean isValidProjectId(String projectId) {
        return projectId != null && PROJECT_ID.matcher(projectId).matches();
    }

    /** Looks a project up by ID or number without registering anything. */
    public Optional<ProjectRecord> find(String idOrNumber) {
        if (idOrNumber == null || idOrNumber.isBlank()) {
            return Optional.empty();
        }
        if (isNumber(idOrNumber)) {
            if (idOrNumber.equals(ProjectNumbers.of(defaultProjectId))) {
                return Optional.of(defaultProject());
            }
            return store.scan(k -> true).stream()
                    .filter(p -> idOrNumber.equals(p.getProjectNumber()))
                    .findFirst();
        }
        if (idOrNumber.equals(defaultProjectId)) {
            return Optional.of(defaultProject());
        }
        return store.get(idOrNumber);
    }

    /**
     * Resolves a project by ID or number. Unknown IDs are registered implicitly unless strict
     * mode is on; unknown numbers, and unknown IDs in strict mode, fail the way GCP does for a
     * project the caller cannot see.
     */
    public ProjectRecord require(String idOrNumber) {
        Optional<ProjectRecord> found = find(idOrNumber);
        if (found.isPresent()) {
            return found.get();
        }
        if (strict || !isRegistrable(idOrNumber)) {
            throw GcpException.permissionDenied(PERMISSION_DENIED_MESSAGE);
        }
        return registerImplicit(idOrNumber);
    }

    /**
     * Records a project ID seen on a request. Returns the canonical project ID: a registered
     * project's number maps to its ID, anything else is returned unchanged.
     */
    public String observe(String idOrNumber) {
        if (isWildcard(idOrNumber)) {
            return idOrNumber;
        }
        Optional<ProjectRecord> found = find(idOrNumber);
        if (found.isPresent()) {
            return found.get().getProjectId();
        }
        if (strict) {
            throw GcpException.permissionDenied(PERMISSION_DENIED_MESSAGE);
        }
        if (isRegistrable(idOrNumber)) {
            registerImplicit(idOrNumber);
        }
        return idOrNumber;
    }

    public synchronized ProjectRecord create(ProjectRecord project) {
        if (find(project.getProjectId()).isPresent()) {
            throw GcpException.alreadyExists("Requested entity already exists");
        }
        project.setProjectNumber(ProjectNumbers.of(project.getProjectId()));
        project.setLifecycleState(ProjectRecord.ACTIVE);
        project.setCreateTime(Instant.now().toString());
        project.setCreated(true);
        store.put(project.getProjectId(), project);
        return project;
    }

    public void save(ProjectRecord project) {
        store.put(project.getProjectId(), project);
    }

    public List<ProjectRecord> list() {
        defaultProject();
        return store.scan(k -> true).stream()
                .sorted(Comparator.comparing(ProjectRecord::getProjectId))
                .toList();
    }

    /** Stable per-project timestamp for implicit projects: real project createTimes are immutable. */
    public static String implicitCreateTime(String projectId) {
        long secondsSinceEpoch = 1_000_000_000L + Math.floorMod((long) projectId.hashCode(), 500_000_000L);
        return Instant.ofEpochSecond(secondsSinceEpoch).toString();
    }

    private ProjectRecord defaultProject() {
        return store.get(defaultProjectId).orElseGet(() -> registerImplicit(defaultProjectId));
    }

    private synchronized ProjectRecord registerImplicit(String projectId) {
        Optional<ProjectRecord> existing = store.get(projectId);
        if (existing.isPresent()) {
            return existing.get();
        }
        ProjectRecord project = new ProjectRecord();
        project.setProjectId(projectId);
        project.setProjectNumber(ProjectNumbers.of(projectId));
        project.setName(projectId);
        project.setCreateTime(implicitCreateTime(projectId));
        store.put(projectId, project);
        return project;
    }

    private static boolean isWildcard(String value) {
        return "-".equals(value) || "_".equals(value);
    }

    private static boolean isRegistrable(String value) {
        return value != null && !value.isBlank() && !isNumber(value) && !isWildcard(value)
                && value.indexOf('/') < 0 && value.chars().noneMatch(Character::isWhitespace);
    }
}
