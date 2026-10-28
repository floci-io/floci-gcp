package io.floci.gcp.services.iam;

import io.floci.gcp.core.common.ProjectRecord;
import io.floci.gcp.core.common.ProjectRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IamResourceHierarchyServiceTest {

    @Test
    void loggingSinkInheritsRecordedOrganizationPolicy() {
        ProjectRegistry projects = mock(ProjectRegistry.class);
        ProjectRecord record = new ProjectRecord();
        record.setProjectId("sink-project");
        record.setParentType("organization");
        record.setParentId("123");
        when(projects.find("sink-project")).thenReturn(Optional.of(record));
        IamResourceHierarchy hierarchy = new IamResourceHierarchy(projects);

        assertEquals(List.of("projects/sink-project", "organizations/123"),
                hierarchy.policyResourcesFor(IamResource.projectChild("logging.googleapis.com", "LogSink",
                        "projects/sink-project/sinks/one", "projects/sink-project")));
        assertEquals(List.of("projects/sink-project"),
                hierarchy.policyResourcesFor(IamResource.projectChild("pubsub.googleapis.com", "Topic",
                        "projects/sink-project/topics/one", "projects/sink-project")));
    }

    @Test
    void unknownProjectDoesNotInventAnOrganizationAncestor() {
        ProjectRegistry projects = mock(ProjectRegistry.class);
        when(projects.find("unknown")).thenReturn(Optional.empty());
        IamResourceHierarchy hierarchy = new IamResourceHierarchy(projects);

        assertEquals(List.of("projects/unknown"), hierarchy.policyResourcesFor(IamResource.projectChild(
                "logging.googleapis.com", "LogSink", "projects/unknown/sinks/one", "projects/unknown")));
    }
}
