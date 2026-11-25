package io.floci.gcp.services.cloudlogging;

import com.google.logging.v2.LogExclusion;
import com.google.logging.v2.LogSink;
import io.floci.gcp.core.common.ProjectRecord;
import io.floci.gcp.core.common.ProjectRegistry;
import io.floci.gcp.services.cloudlogging.model.StoredLogEntry;

import java.util.List;
import java.util.function.Predicate;

/** Selects the entries a sink routes: owned by its parent (or a child when included), matching, not excluded. */
final class SinkRouting {
    private final ProjectRegistry projects;

    SinkRouting(ProjectRegistry projects) {
        this.projects = projects;
    }

    List<StoredLogEntry> select(String parent, LogSink sink, List<StoredLogEntry> entries) {
        Predicate<StoredLogEntry> matches = SinkLogEntryFilter.parse(sink.getFilter());
        for (LogExclusion exclusion : sink.getExclusionsList()) {
            if (!exclusion.getDisabled()) {
                matches = matches.and(SinkLogEntryFilter.parse(exclusion.getFilter()).negate());
            }
        }
        Predicate<StoredLogEntry> routed = matches;
        return entries.stream()
                .filter(entry -> belongsTo(parent, sink.getIncludeChildren(), entry.getLogName()) && routed.test(entry))
                .toList();
    }

    private boolean belongsTo(String parent, boolean includeChildren, String logName) {
        int logs = logName.indexOf("/logs/");
        if (logs < 0) {
            return false;
        }
        String owner = logName.substring(0, logs);
        if (parent.equals(owner)) {
            return true;
        }
        if (!owner.startsWith("projects/")) {
            return false;
        }
        ProjectRecord project = projects.find(owner.substring("projects/".length())).orElse(null);
        if (project == null) {
            return false;
        }
        if (parent.equals("projects/" + project.getProjectId())
                || parent.equals("projects/" + project.getProjectNumber())) {
            return true;
        }
        if (!includeChildren || (!parent.startsWith("folders/") && !parent.startsWith("organizations/"))) {
            return false;
        }
        return project.hasParent() && parent.equals(project.getParentType() + "s/" + project.getParentId());
    }
}
