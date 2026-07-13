package io.floci.gcp.test;

import com.google.cloud.functions.v2.BuildConfig;
import com.google.cloud.functions.v2.CreateFunctionRequest;
import com.google.cloud.functions.v2.DeleteFunctionRequest;
import com.google.cloud.functions.v2.Function;
import com.google.cloud.functions.v2.FunctionServiceClient;
import com.google.cloud.functions.v2.GenerateUploadUrlRequest;
import com.google.cloud.functions.v2.GenerateUploadUrlResponse;
import com.google.cloud.functions.v2.GetFunctionRequest;
import com.google.cloud.functions.v2.ListFunctionsRequest;
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

        HttpRequest put = TestFixtures.authorize(HttpRequest.newBuilder(URI.create(response.getUploadUrl()))
                .header("Content-Type", "application/zip")
                .PUT(HttpRequest.BodyPublishers.ofString("fake source zip", StandardCharsets.UTF_8)))
                .build();
        HttpResponse<String> putResponse = HttpClient.newHttpClient().send(put, HttpResponse.BodyHandlers.ofString());
        assertThat(putResponse.statusCode()).isEqualTo(200);
    }

    @Test
    @Order(5)
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
