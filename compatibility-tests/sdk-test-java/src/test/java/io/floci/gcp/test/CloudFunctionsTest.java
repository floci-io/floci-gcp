package io.floci.gcp.test;

import com.google.cloud.functions.v2.BuildConfig;
import com.google.cloud.functions.v2.CreateFunctionRequest;
import com.google.cloud.functions.v2.DeleteFunctionRequest;
import com.google.cloud.functions.v2.Function;
import com.google.cloud.functions.v2.FunctionServiceClient;
import com.google.cloud.functions.v2.GenerateDownloadUrlRequest;
import com.google.cloud.functions.v2.GenerateDownloadUrlResponse;
import com.google.cloud.functions.v2.GenerateUploadUrlRequest;
import com.google.cloud.functions.v2.GenerateUploadUrlResponse;
import com.google.cloud.functions.v2.GetFunctionRequest;
import com.google.cloud.functions.v2.ListFunctionsRequest;
import com.google.cloud.functions.v2.Source;
import com.google.cloud.functions.v2.StorageSource;
import com.google.cloud.functions.v2.UpdateFunctionRequest;
import com.google.iam.v1.Binding;
import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.Policy;
import com.google.iam.v1.SetIamPolicyRequest;
import com.google.iam.v1.TestIamPermissionsRequest;
import com.google.iam.v1.TestIamPermissionsResponse;
import com.google.protobuf.FieldMask;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CloudFunctionsTest {

    private static final String PROJECT_ID = TestFixtures.projectId();
    private static final String LOCATION = "us-central1";
    private static final String FUNCTION_ID = TestFixtures.uniqueName("java-fn");
    private static final String PARENT = "projects/" + PROJECT_ID + "/locations/" + LOCATION;
    private static final String FUNCTION_NAME = PARENT + "/functions/" + FUNCTION_ID;

    private static FunctionServiceClient client;
    private static StorageSource uploadedSource;

    @BeforeAll
    static void setUp() throws IOException {
        client = TestFixtures.cloudFunctionsClient();
    }

    @AfterAll
    static void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @Order(1)
    void createFunctionWithLro() throws Exception {
        Function function = Function.newBuilder()
                .setBuildConfig(BuildConfig.newBuilder()
                        .setRuntime("java21")
                        .setEntryPoint("ExampleFunction")
                        .build())
                .build();

        Function created = client.createFunctionAsync(CreateFunctionRequest.newBuilder()
                        .setParent(PARENT)
                        .setFunctionId(FUNCTION_ID)
                        .setFunction(function)
                        .build())
                .get(10, TimeUnit.SECONDS);

        assertThat(created.getName()).isEqualTo(FUNCTION_NAME);
        assertThat(created.getState()).isEqualTo(Function.State.ACTIVE);
        assertThat(created.getEnvironment()).isEqualTo(com.google.cloud.functions.v2.Environment.GEN_2);
        assertThat(created.getUrl()).isEqualTo("https://" + LOCATION + "-" + PROJECT_ID + ".cloudfunctions.net/" + FUNCTION_ID);
        assertThat(created.getServiceConfig().getService()).isEqualTo(
                "projects/" + PROJECT_ID + "/locations/" + LOCATION + "/services/" + FUNCTION_ID);
    }

    @Test
    @Order(2)
    void getFunction() {
        Function function = client.getFunction(GetFunctionRequest.newBuilder()
                .setName(FUNCTION_NAME)
                .build());

        assertThat(function.getName()).isEqualTo(FUNCTION_NAME);
        assertThat(function.getState()).isEqualTo(Function.State.ACTIVE);
    }

    @Test
    @Order(3)
    void listFunctions() {
        List<Function> functions = new ArrayList<>();
        client.listFunctions(ListFunctionsRequest.newBuilder()
                        .setParent(PARENT)
                        .build())
                .iterateAll()
                .forEach(functions::add);

        assertThat(functions).anyMatch(function -> function.getName().equals(FUNCTION_NAME));

        List<Function> allLocations = new ArrayList<>();
        client.listFunctions(ListFunctionsRequest.newBuilder()
                        .setParent("projects/" + PROJECT_ID + "/locations/-")
                        .build())
                .iterateAll()
                .forEach(allLocations::add);

        assertThat(allLocations).anyMatch(function -> function.getName().equals(FUNCTION_NAME));
    }

    @Test
    @Order(4)
    void generateUploadUrlAndPutSourceObject() throws Exception {
        GenerateUploadUrlResponse response = client.generateUploadUrl(GenerateUploadUrlRequest.newBuilder()
                .setParent(PARENT)
                .build());

        assertThat(response.getUploadUrl()).startsWith(TestFixtures.endpoint() + "/");
        assertThat(response.getStorageSource().getBucket()).startsWith("gcf-v2-sources-");
        assertThat(response.getStorageSource().getObject()).startsWith("source-");

        HttpRequest put = HttpRequest.newBuilder(URI.create(response.getUploadUrl()))
                .header("Content-Type", "application/zip")
                .PUT(HttpRequest.BodyPublishers.ofString("fake source zip", StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> putResponse = HttpClient.newHttpClient().send(put, HttpResponse.BodyHandlers.ofString());
        assertThat(putResponse.statusCode()).isEqualTo(200);
        uploadedSource = response.getStorageSource();
    }

    @Test
    @Order(5)
    void generateDownloadUrlServesTheDeployedSource() throws Exception {
        client.updateFunctionAsync(UpdateFunctionRequest.newBuilder()
                        .setFunction(Function.newBuilder()
                                .setName(FUNCTION_NAME)
                                .setBuildConfig(BuildConfig.newBuilder()
                                        .setSource(Source.newBuilder()
                                                .setStorageSource(StorageSource.newBuilder()
                                                        .setBucket(uploadedSource.getBucket())
                                                        .setObject(uploadedSource.getObject())
                                                        .build())
                                                .build())
                                        .build())
                                .build())
                        .setUpdateMask(FieldMask.newBuilder().addPaths("build_config.source").build())
                        .build())
                .get(10, TimeUnit.SECONDS);

        GenerateDownloadUrlResponse response = client.generateDownloadUrl(GenerateDownloadUrlRequest.newBuilder()
                .setName(FUNCTION_NAME)
                .build());

        assertThat(response.getDownloadUrl())
                .startsWith(TestFixtures.endpoint() + "/" + uploadedSource.getBucket() + "/" + uploadedSource.getObject() + "?")
                .contains("X-Goog-Expires=1800");
        HttpResponse<String> download = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(response.getDownloadUrl())).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo("fake source zip");
    }

    @Test
    @Order(6)
    void updateFunctionWithMaskAndLro() throws Exception {
        Function updated = client.updateFunctionAsync(UpdateFunctionRequest.newBuilder()
                        .setFunction(Function.newBuilder()
                                .setName(FUNCTION_NAME)
                                .setDescription("updated")
                                .setBuildConfig(BuildConfig.newBuilder().setRuntime("java25").build())
                                .build())
                        .setUpdateMask(FieldMask.newBuilder()
                                .addPaths("description")
                                .addPaths("build_config.runtime")
                                .build())
                        .build())
                .get(10, TimeUnit.SECONDS);

        assertThat(updated.getDescription()).isEqualTo("updated");
        assertThat(updated.getBuildConfig().getRuntime()).isEqualTo("java25");
        assertThat(updated.getBuildConfig().getEntryPoint()).isEqualTo("ExampleFunction");
        assertThat(updated.getState()).isEqualTo(Function.State.ACTIVE);
    }

    @Test
    @Order(7)
    void iamPolicyRoundTrip() {
        Policy empty = client.getIamPolicy(GetIamPolicyRequest.newBuilder()
                .setResource(FUNCTION_NAME)
                .build());
        assertThat(empty.getBindingsList()).isEmpty();

        Policy set = client.setIamPolicy(SetIamPolicyRequest.newBuilder()
                .setResource(FUNCTION_NAME)
                .setPolicy(Policy.newBuilder()
                        .addBindings(Binding.newBuilder()
                                .setRole("roles/cloudfunctions.invoker")
                                .addMembers("allUsers")
                                .build())
                        .build())
                .build());
        assertThat(set.getBindingsList()).hasSize(1);

        Policy read = client.getIamPolicy(GetIamPolicyRequest.newBuilder()
                .setResource(FUNCTION_NAME)
                .build());
        assertThat(read.getEtag()).isEqualTo(set.getEtag());
        assertThat(read.getBindings(0).getMembersList()).containsExactly("allUsers");

        TestIamPermissionsResponse permissions = client.testIamPermissions(TestIamPermissionsRequest.newBuilder()
                .setResource(FUNCTION_NAME)
                .addPermissions("cloudfunctions.functions.invoke")
                .build());
        assertThat(permissions.getPermissionsList()).containsExactly("cloudfunctions.functions.invoke");
    }

    @Test
    @Order(8)
    void deleteFunctionWithLro() throws Exception {
        client.deleteFunctionAsync(DeleteFunctionRequest.newBuilder()
                        .setName(FUNCTION_NAME)
                        .build())
                .get(10, TimeUnit.SECONDS);

        List<Function> functions = new ArrayList<>();
        client.listFunctions(ListFunctionsRequest.newBuilder()
                        .setParent(PARENT)
                        .build())
                .iterateAll()
                .forEach(functions::add);
        assertThat(functions).noneMatch(function -> function.getName().equals(FUNCTION_NAME));
    }
}
