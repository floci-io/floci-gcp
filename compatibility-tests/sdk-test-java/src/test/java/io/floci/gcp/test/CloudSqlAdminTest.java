package io.floci.gcp.test;

import com.google.api.services.sqladmin.SQLAdmin;
import com.google.api.services.sqladmin.model.Database;
import com.google.api.services.sqladmin.model.DatabaseInstance;
import com.google.api.services.sqladmin.model.Flag;
import com.google.api.services.sqladmin.model.Operation;
import com.google.api.services.sqladmin.model.Settings;
import com.google.api.services.sqladmin.model.Tier;
import com.google.api.services.sqladmin.model.User;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CloudSqlAdminTest {

    private static final String PROJECT_ID = TestFixtures.projectId();
    private static final String INSTANCE_ID = TestFixtures.uniqueName("java-pg");
    private static final String DATABASE_ID = "appdb";
    private static final String USER_ID = "app";

    private static SQLAdmin client;

    @BeforeAll
    static void setUp() {
        client = TestFixtures.sqlAdminClient();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (client != null) {
            try {
                client.users().delete(PROJECT_ID, INSTANCE_ID).setName(USER_ID).execute();
            } catch (Exception ignored) {
            }
            try {
                client.databases().delete(PROJECT_ID, INSTANCE_ID, DATABASE_ID).execute();
            } catch (Exception ignored) {
            }
            try {
                client.instances().delete(PROJECT_ID, INSTANCE_ID).execute();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    @Order(1)
    void createPostgresInstance() throws Exception {
        DatabaseInstance instance = new DatabaseInstance()
                .setName(INSTANCE_ID)
                .setRegion("us-central1")
                .setDatabaseVersion("POSTGRES_15")
                .setSettings(new Settings().setTier("db-custom-1-3840"));

        Operation operation = client.instances().insert(PROJECT_ID, instance).execute();
        assertDone(operation, "CREATE");

        DatabaseInstance created = client.instances().get(PROJECT_ID, INSTANCE_ID).execute();
        assertThat(created.getName()).isEqualTo(INSTANCE_ID);
        assertThat(created.getDatabaseVersion()).isEqualTo("POSTGRES_15");
        assertThat(created.getState()).isEqualTo("RUNNABLE");
        assertThat(created.getConnectionName()).isEqualTo(PROJECT_ID + ":us-central1:" + INSTANCE_ID);
    }

    @Test
    @Order(2)
    void staticDiscoveryListsTiersAndFlags() throws Exception {
        List<Tier> tiers = client.tiers().list(PROJECT_ID).execute().getItems();
        assertThat(tiers).extracting(Tier::getTier).contains("db-custom-1-3840");

        List<Flag> flags = client.flags().list().execute().getItems();
        assertThat(flags).extracting(Flag::getName).contains("max_connections");
    }

    @Test
    @Order(3)
    void createDatabaseAndUser() throws Exception {
        Operation databaseOperation = client.databases().insert(PROJECT_ID, INSTANCE_ID,
                new Database().setName(DATABASE_ID)).execute();
        assertDone(databaseOperation, "CREATE_DATABASE");

        Database database = client.databases().get(PROJECT_ID, INSTANCE_ID, DATABASE_ID).execute();
        assertThat(database.getName()).isEqualTo(DATABASE_ID);
        assertThat(database.getInstance()).isEqualTo(INSTANCE_ID);

        Operation userOperation = client.users().insert(PROJECT_ID, INSTANCE_ID,
                new User().setName(USER_ID).setPassword("secret")).execute();
        assertDone(userOperation, "CREATE_USER");

        List<User> users = client.users().list(PROJECT_ID, INSTANCE_ID).execute().getItems();
        assertThat(users).extracting(User::getName).contains(USER_ID);
        assertThat(users).allMatch(user -> user.getPassword() == null);
    }

    @Test
    @Order(4)
    void deleteDatabaseUserAndInstance() throws Exception {
        assertDone(client.users().delete(PROJECT_ID, INSTANCE_ID).setName(USER_ID).execute(), "DELETE_USER");
        assertDone(client.databases().delete(PROJECT_ID, INSTANCE_ID, DATABASE_ID).execute(), "DELETE_DATABASE");
        assertDone(client.instances().delete(PROJECT_ID, INSTANCE_ID).execute(), "DELETE");
    }

    private static void assertDone(Operation operation, String type) {
        assertThat(operation.getKind()).isEqualTo("sql#operation");
        assertThat(operation.getStatus()).isEqualTo("DONE");
        assertThat(operation.getOperationType()).isEqualTo(type);
    }
}
