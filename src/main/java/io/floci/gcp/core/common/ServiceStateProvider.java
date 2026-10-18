package io.floci.gcp.core.common;

/** Per-project API enablement state, as tracked by Service Usage. */
public interface ServiceStateProvider {

    boolean isServiceEnabled(String project, String apiName);
}
