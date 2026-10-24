package io.floci.gcp.services.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.scheduler.v1.CloudSchedulerGrpc;
import com.google.cloud.scheduler.v1.CreateJobRequest;
import com.google.cloud.scheduler.v1.DeleteJobRequest;
import com.google.cloud.scheduler.v1.GetJobRequest;
import com.google.cloud.scheduler.v1.Job;
import com.google.cloud.scheduler.v1.ListJobsRequest;
import com.google.cloud.scheduler.v1.PauseJobRequest;
import com.google.cloud.scheduler.v1.ResumeJobRequest;
import com.google.cloud.scheduler.v1.RunJobRequest;
import com.google.cloud.scheduler.v1.UpdateJobRequest;
import com.google.protobuf.Message;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ProtoJson;
import io.floci.gcp.services.iam.IamRoleCatalog;
import io.floci.gcp.services.iam.authorization.IamAuthorizationRegistry;
import io.floci.gcp.services.iam.authorization.IamOperation;
import io.floci.gcp.services.iam.authorization.IamPermissionCheck;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerIamAuthorizationAdapterServiceTest {
    private static final String PARENT = "projects/test/locations/us-central1";
    private static final String JOB = PARENT + "/jobs/intended";
    private final SchedulerIamAuthorizationAdapter adapter = new SchedulerIamAuthorizationAdapter();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void everyRestHandlerAndGrpcMethodHasTheSamePermissionAndTarget() {
        Map<String, Message> requests = Map.of(
                "CreateJob", CreateJobRequest.newBuilder().setParent(PARENT).setJob(Job.newBuilder().setName(JOB)).build(),
                "ListJobs", ListJobsRequest.newBuilder().setParent(PARENT).build(),
                "GetJob", GetJobRequest.newBuilder().setName(JOB).build(),
                "UpdateJob", UpdateJobRequest.newBuilder().setJob(Job.newBuilder().setName(JOB)).build(),
                "DeleteJob", DeleteJobRequest.newBuilder().setName(JOB).build(),
                "PauseJob", PauseJobRequest.newBuilder().setName(JOB).build(),
                "ResumeJob", ResumeJobRequest.newBuilder().setName(JOB).build(),
                "RunJob", RunJobRequest.newBuilder().setName(JOB).build());
        Map<String, String> permissions = Map.of(
                "CreateJob", "create", "ListJobs", "list", "GetJob", "get", "UpdateJob", "update",
                "DeleteJob", "delete", "PauseJob", "pause", "ResumeJob", "enable", "RunJob", "run");
        Set<String> restMethods = Arrays.stream(SchedulerHttpController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Path.class)).map(Method::getName).collect(Collectors.toSet());
        Set<String> grpcMethods = CloudSchedulerGrpc.getServiceDescriptor().getMethods().stream()
                .map(method -> method.getBareMethodName()).collect(Collectors.toSet());
        assertEquals(requests.keySet(), grpcMethods);
        assertEquals(requests.keySet().stream().map(name -> Character.toLowerCase(name.charAt(0)) + name.substring(1))
                .collect(Collectors.toSet()), restMethods);
        requests.forEach((method, request) -> {
            boolean parent = method.equals("CreateJob") || method.equals("ListJobs");
            Map<String, String> path = parent ? Map.of("project", "test", "location", "us-central1")
                    : Map.of("project", "test", "location", "us-central1", "jobId", "intended");
            String handler = Character.toLowerCase(method.charAt(0)) + method.substring(1);
            IamOperation grpc = adapter.grpcOperation(method, request);
            try {
                assertEquals(grpc, adapter.grpcOperation(method, mapper.readTree(ProtoJson.print(request))));
            } catch (Exception failure) {
                fail("Failed to normalize " + method, failure);
            }
            assertEquals(grpc, adapter.restOperation(handler, path, mapper.createObjectNode()));
            List<IamPermissionCheck> checks = adapter.checks(grpc);
            assertEquals(1, checks.size());
            assertEquals("cloudscheduler.jobs." + permissions.get(method), checks.getFirst().permission());
            assertEquals(parent ? PARENT : JOB, checks.getFirst().resource().name());
            assertEquals("projects/test", checks.getFirst().resource().policyResource());
            assertEquals("projects/test", checks.getFirst().resource().projectResource());
        });
    }

    @Test
    void predefinedAndBasicRolesGrantOnlyTheirDeclaredJobPermissions() {
        IamRoleCatalog catalog = new IamRoleCatalog(new IamAuthorizationRegistry(List.of(adapter)));
        for (String role : List.of("roles/cloudscheduler.admin", "roles/owner", "roles/editor")) {
            for (String permission : List.of("create", "get", "list", "update", "delete", "pause", "enable", "run")) {
                assertTrue(catalog.grants(role, "cloudscheduler.jobs." + permission));
            }
        }
        for (String role : List.of("roles/cloudscheduler.viewer", "roles/viewer")) {
            assertTrue(catalog.grants(role, "cloudscheduler.jobs.get"));
            assertTrue(catalog.grants(role, "cloudscheduler.jobs.list"));
            for (String permission : List.of("create", "update", "delete", "pause", "enable", "run")) {
                assertFalse(catalog.grants(role, "cloudscheduler.jobs." + permission));
            }
        }
        assertTrue(catalog.grants("roles/cloudscheduler.jobRunner", "cloudscheduler.jobs.run"));
        assertFalse(catalog.grants("roles/cloudscheduler.jobRunner", "cloudscheduler.jobs.get"));
        assertFalse(catalog.grants("projects/test/roles/custom", "cloudscheduler.jobs.run"));
    }

    @Test
    void restNullAndEmptyFieldsUseTheControllersProtobufDefaults() throws Exception {
        Map<String, String> path = Map.of("project", "test", "location", "us-central1", "jobId", "intended");
        for (String body : List.of("{}", "{\"name\":null}", "{\"name\":\"\"}")) {
            IamOperation operation = adapter.restOperation("updateJob", path, mapper.readTree(body));
            assertEquals(JOB, operation.resource());
            assertEquals("cloudscheduler.jobs.update", adapter.checks(operation).getFirst().permission());
        }
    }

    @Test
    void explicitNullAuthenticationFieldsAreUnsetButConfiguredTokensFailClosed() throws Exception {
        for (String target : List.of("httpTarget", "http_target")) {
            for (String token : List.of("oidcToken", "oauthToken", "oidc_token", "oauth_token")) {
                String json = "{\"" + target + "\":{\"uri\":\"https://example.invalid\",\"" + token + "\":null}}";
                IamOperation operation = adapter.restOperation("createJob", Map.of("project", "test", "location", "us-central1"), mapper.readTree(json));
                assertEquals("", operation.relatedResource());
                assertDoesNotThrow(() -> adapter.checks(operation));
                String configured = json.replace(":null", ":{}");
                IamOperation authenticated = adapter.restOperation("createJob", Map.of("project", "test", "location", "us-central1"), mapper.readTree(configured));
                assertThrows(GcpException.class, () -> adapter.checks(authenticated));
            }
        }
    }

    @Test
    void nullTargetAliasesCannotHideConfiguredAuthentication() throws Exception {
        for (String token : List.of("oidcToken", "oauthToken", "oidc_token", "oauth_token")) {
            String json = "{\"httpTarget\":null,\"http_target\":{\"uri\":\"https://example.invalid\",\""
                    + token + "\":{\"serviceAccountEmail\":\"target@example.iam.gserviceaccount.com\"}}}";
            Job parsed = ProtoJson.merge(json, Job.newBuilder()).build();
            assertTrue(parsed.getHttpTarget().hasOidcToken() || parsed.getHttpTarget().hasOauthToken());
            IamOperation rest = adapter.restOperation("createJob", Map.of("project", "test", "location", "us-central1"),
                    mapper.readTree(json));
            IamOperation grpc = adapter.grpcOperation("CreateJob",
                    CreateJobRequest.newBuilder().setParent(PARENT).setJob(parsed).build());
            assertEquals(grpc, rest);
            assertThrows(GcpException.class, () -> adapter.checks(rest));
        }
    }

    @Test
    void domainScopedProjectNamesKeepTheirProjectPolicyAndConditionName() {
        String parent = "projects/example.com:project/locations/us-central1";
        String job = parent + "/jobs/intended";
        IamPermissionCheck create = adapter.checks(new IamOperation("CreateJob", parent)).getFirst();
        IamPermissionCheck read = adapter.checks(new IamOperation("GetJob", job)).getFirst();
        assertEquals(parent, create.resource().name());
        assertEquals(job, read.resource().name());
        assertEquals("projects/example.com:project", read.resource().policyResource());
        assertEquals("projects/example.com:project", create.resource().projectResource());
    }

    @Test
    void unknownOperationsResourcesAndSharedPolicyMethodsFailClosed() {
        for (String method : List.of("Unknown", "GetIamPolicy", "SetIamPolicy", "TestIamPermissions")) {
            assertThrows(GcpException.class, () -> adapter.checks(new IamOperation(method, JOB)));
        }
        for (String resource : List.of("projects/other", PARENT, JOB + "/extra", JOB + ":run")) {
            assertTrue(adapter.resource(resource).isEmpty());
            assertThrows(GcpException.class, () -> adapter.checks(new IamOperation("RunJob", resource)));
        }
        assertThrows(GcpException.class, () -> adapter.grpcOperation("Unknown", Job.getDefaultInstance()));
        assertThrows(GcpException.class, () -> adapter.checks(new IamOperation("CreateJob", JOB)));
    }
}
