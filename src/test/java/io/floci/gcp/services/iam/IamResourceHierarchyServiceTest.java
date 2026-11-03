package io.floci.gcp.services.iam;

import io.floci.gcp.core.common.ProjectRecord;
import io.floci.gcp.core.common.ProjectRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IamResourceHierarchyServiceTest {

    @Test
    void organizationInheritanceRequiresExplicitOptIn() {
        ProjectRegistry projects = projects();
        ProjectRecord record = new ProjectRecord();
        record.setProjectId("sink-project");
        record.setParentType("organization");
        record.setParentId("123");
        when(projects.find("sink-project")).thenReturn(Optional.of(record));
        IamResourceHierarchy hierarchy = new IamResourceHierarchy(projects);

        assertEquals(List.of("projects/sink-project", "organizations/123"),
                hierarchy.policyResourcesFor(new IamResource("example.googleapis.com", "example.googleapis.com/Child",
                        "projects/sink-project/children/one", "projects/sink-project", "projects/sink-project", true)));
        assertEquals(List.of("projects/sink-project"),
                hierarchy.policyResourcesFor(IamResource.projectChild("pubsub.googleapis.com", "Topic",
                        "projects/sink-project/topics/one", "projects/sink-project")));
    }

    @Test
    void unknownProjectDoesNotInventAnOrganizationAncestor() {
        ProjectRegistry projects = projects();
        when(projects.find("unknown")).thenReturn(Optional.empty());
        IamResourceHierarchy hierarchy = new IamResourceHierarchy(projects);

        assertEquals(List.of("projects/unknown"), hierarchy.policyResourcesFor(new IamResource(
                "example.googleapis.com", "example.googleapis.com/Child", "projects/unknown/children/one",
                "projects/unknown", "projects/unknown", true)));
    }

    @Test
    void organizationInheritancePreservesCanonicalProjectPolicyLookup() {
        ProjectRegistry projects = projects();
        ProjectRecord record = new ProjectRecord();
        record.setProjectId("sink-project");
        record.setParentType("organization");
        record.setParentId("123");
        when(projects.find("456")).thenReturn(Optional.of(record));
        when(projects.canonicalProjectName("projects/456")).thenReturn("projects/sink-project");

        assertEquals(List.of("projects/sink-project", "organizations/123"),
                new IamResourceHierarchy(projects).policyResourcesFor(new IamResource(
                        "example.googleapis.com", "example.googleapis.com/Child", "projects/456/children/one",
                        "projects/456", "projects/456", true)));
    }

    @Test
    void folderParentDoesNotInventAnOrganizationAncestor() {
        ProjectRegistry projects = projects();
        ProjectRecord record = new ProjectRecord();
        record.setParentType("folder");
        record.setParentId("123");
        when(projects.find("sink-project")).thenReturn(Optional.of(record));

        assertEquals(List.of("projects/sink-project"),
                new IamResourceHierarchy(projects).policyResourcesFor(new IamResource(
                        "example.googleapis.com", "example.googleapis.com/Child", "projects/sink-project/children/one",
                        "projects/sink-project", "projects/sink-project", true)));
    }

    private static ProjectRegistry projects() {
        ProjectRegistry projects = mock(ProjectRegistry.class);
        when(projects.canonicalProjectName(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        return projects;
    }
}
