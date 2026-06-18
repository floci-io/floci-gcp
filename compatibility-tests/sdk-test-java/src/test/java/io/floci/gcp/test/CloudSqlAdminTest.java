package io.floci.gcp.test;

import com.google.api.services.sqladmin.SQLAdmin;
import com.google.api.services.sqladmin.model.Database;
import com.google.api.services.sqladmin.model.DatabaseInstance;
import com.google.api.services.sqladmin.model.Flag;
import com.google.api.services.sqladmin.model.Operation;
import com.google.api.services.sqladmin.model.Settings;
import com.google.api.services.sqladmin.model.Tier;
import com.google.api.services.sqladmin.model.User;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CloudSqlAdminTest {

    private static final String PROJECT_ID = TestFixtures.projectId();
    private static final String DATABASE_ID = "appdb";
    private static final String USER_ID = "app";

    private static SQLAdmin client;

    @BeforeAll
    static void setUp() {
        client = TestFixtures.sqlAdminClient();
    }

    @Test
    void staticDiscoveryListsTiersAndFlags() throws Exception {
        List<Tier> tiers = client.tiers().list(PROJECT_ID).execute().getItems();
        assertThat(tiers).extracting(Tier::getTier).contains("db-custom-1-3840");

        List<Flag> flags = client.flags().list().execute().getItems();
        assertThat(flags).extracting(Flag::getName).contains("max_connections");
    }

    @Test
    void sqlAdminSdkCreatesUsableInstancesForLatestPostgresVersions() throws Exception {
        for (String databaseVersion : List.of("POSTGRES_16", "POSTGRES_17", "POSTGRES_18")) {
            createDatabaseUserAndConnect(databaseVersion);
        }
    }

    private void createDatabaseUserAndConnect(String databaseVersion) throws Exception {
        String majorVersion = databaseVersion.substring("POSTGRES_".length());
        String instanceId = TestFixtures.uniqueName("java-pg-" + majorVersion);

        DatabaseInstance instance = new DatabaseInstance()
                .setName(instanceId)
                .setRegion("us-central1")
                .setDatabaseVersion(databaseVersion)
                .setSettings(new Settings().setTier("db-custom-1-3840"));

        try {
            Operation operation = client.instances().insert(PROJECT_ID, instance).execute();
            assertDone(operation, "CREATE");

            DatabaseInstance created = client.instances().get(PROJECT_ID, instanceId).execute();
            assertThat(created.getName()).isEqualTo(instanceId);
            assertThat(created.getDatabaseVersion()).isEqualTo(databaseVersion);
            assertThat(created.getState()).isEqualTo("RUNNABLE");
            assertThat(created.getConnectionName()).isEqualTo(PROJECT_ID + ":us-central1:" + instanceId);

            Operation databaseOperation = client.databases().insert(PROJECT_ID, instanceId,
                    new Database().setName(DATABASE_ID)).execute();
            assertDone(databaseOperation, "CREATE_DATABASE");

            Database database = client.databases().get(PROJECT_ID, instanceId, DATABASE_ID).execute();
            assertThat(database.getName()).isEqualTo(DATABASE_ID);
            assertThat(database.getInstance()).isEqualTo(instanceId);

            Operation userOperation = client.users().insert(PROJECT_ID, instanceId,
                    new User().setName(USER_ID).setPassword("secret")).execute();
            assertDone(userOperation, "CREATE_USER");

            List<User> users = client.users().list(PROJECT_ID, instanceId).execute().getItems();
            assertThat(users).extracting(User::getName).contains(USER_ID);
            assertThat(users).allMatch(user -> user.getPassword() == null);

            DatabaseInstance running = client.instances().get(PROJECT_ID, instanceId).execute();
            String host = running.getIpAddresses().get(0).getIpAddress();
            Number port = (Number) running.getIpAddresses().get(0).get("port");
            try (var connection = DriverManager.getConnection(
                    "jdbc:postgresql://" + host + ":" + port.intValue() + "/" + DATABASE_ID,
                    USER_ID, "secret");
                 var statement = connection.createStatement()) {
                try (var result = statement.executeQuery("SHOW server_version")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).startsWith(majorVersion + ".");
                }
                statement.execute("CREATE TABLE sdk_probe (id INT PRIMARY KEY, note TEXT)");
                statement.execute("INSERT INTO sdk_probe (id, note) VALUES (1, 'ready')");
                try (var result = statement.executeQuery("SELECT note FROM sdk_probe WHERE id = 1")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("ready");
                }
            }
        } finally {
            cleanup(instanceId);
        }
    }

    private static void cleanup(String instanceId) {
        try {
            client.users().delete(PROJECT_ID, instanceId).setName(USER_ID).execute();
        } catch (Exception ignored) {
        }
        try {
            client.databases().delete(PROJECT_ID, instanceId, DATABASE_ID).execute();
        } catch (Exception ignored) {
        }
        try {
            client.instances().delete(PROJECT_ID, instanceId).execute();
        } catch (Exception ignored) {
        }
    }

    private static void assertDone(Operation operation, String type) {
        assertThat(operation.getKind()).isEqualTo("sql#operation");
        assertThat(operation.getStatus()).isEqualTo("DONE");
        assertThat(operation.getOperationType()).isEqualTo(type);
    }
}
