package io.floci.gcp.services.compute;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ComputeResourcePolicyIntegrationTest extends ComputeTestSupport {
    private static final String REGION = "/regions/us-central1";
    private static final String ZONE = "/zones/us-central1-a";

    private Map<String, Object> instanceSchedule(String name) {
        return Map.of("name", name, "description", "start and stop",
                "instanceSchedulePolicy", Map.of("timeZone", "America/Bogota",
                        "vmStartSchedule", Map.of("schedule", "0 7 * * 1-5"),
                        "vmStopSchedule", Map.of("schedule", "0 19 * * 1-5")));
    }

    private Map<String, Object> snapshotSchedule(String name) {
        return Map.of("name", name, "snapshotSchedulePolicy", Map.of(
                "schedule", Map.of("dailySchedule", Map.of("daysInCycle", 1, "startTime", "04:00")),
                "retentionPolicy", Map.of("maxRetentionDays", 7),
                "snapshotProperties", Map.of("storageLocations", List.of("us-central1"), "labels", Map.of("env", "test"))));
    }

    private void network(String root) throws Exception {
        done(root, post(root + "/global/networks", Map.of("name", "net", "autoCreateSubnetworks", false)));
        done(root, post(root + REGION + "/subnetworks", Map.of("name", "subnet", "network", "global/networks/net", "ipCidrRange", "10.20.0.0/24")));
    }

    private Map<String, Object> vm(String name, List<String> policies) {
        return Map.of("name", name, "machineType", "zones/us-central1-a/machineTypes/e2-standard-2",
                "networkInterfaces", List.of(Map.of("subnetwork", "regions/us-central1/subnetworks/subnet")),
                "disks", List.of(Map.of("boot", true, "autoDelete", true, "initializeParams", Map.of("diskSizeGb", "10"))),
                "resourcePolicies", policies);
    }

    @Test void policiesSupportCrudListAndAggregatedList() throws Exception {
        String root = root(), policies = root + REGION + "/resourcePolicies";
        done(root, post(policies, instanceSchedule("schedule")));
        done(root, post(policies, snapshotSchedule("snapshots")));
        JsonPath schedule = given().get(policies + "/schedule").then().statusCode(200).extract().jsonPath();
        assertEquals("compute#resourcePolicy", schedule.getString("kind"));
        assertEquals("READY", schedule.getString("status"));
        assertEquals("0 7 * * 1-5", schedule.getString("instanceSchedulePolicy.vmStartSchedule.schedule"));
        assertEquals("America/Bogota", schedule.getString("instanceSchedulePolicy.timeZone"));
        assertTrue(schedule.getString("region").endsWith(REGION));
        assertTrue(schedule.getString("selfLink").endsWith(REGION + "/resourcePolicies/schedule"));
        assertNotNull(schedule.getString("id"));
        assertNotNull(schedule.getString("creationTimestamp"));
        JsonPath snapshots = given().get(policies + "/snapshots").jsonPath();
        assertEquals("KEEP_AUTO_SNAPSHOTS", snapshots.getString("snapshotSchedulePolicy.retentionPolicy.onSourceDiskDelete"));
        assertEquals("04:00", snapshots.getString("snapshotSchedulePolicy.schedule.dailySchedule.startTime"));
        JsonPath list = given().get(policies).then().statusCode(200).extract().jsonPath();
        assertEquals("compute#resourcePolicyList", list.getString("kind"));
        assertEquals(List.of("schedule", "snapshots"), list.getList("items.name"));
        JsonPath aggregated = given().get(root + "/aggregated/resourcePolicies").jsonPath();
        assertEquals("compute#resourcePolicyAggregatedList", aggregated.getString("kind"));
        assertEquals(2, aggregated.getList("items.'regions/us-central1'.resourcePolicies").size());
        post(policies, instanceSchedule("schedule")).then().statusCode(409);
        done(root, given().delete(policies + "/schedule"));
        given().get(policies + "/schedule").then().statusCode(404);
        given().delete(policies + "/schedule").then().statusCode(404);
    }

    @Test void policyBodiesAreValidated() {
        String root = root(), policies = root + REGION + "/resourcePolicies";
        post(policies, Map.of("name", "empty")).then().statusCode(400);
        post(policies, Map.of("name", "both", "instanceSchedulePolicy", Map.of("vmStartSchedule", Map.of("schedule", "0 7 * * *")),
                "snapshotSchedulePolicy", Map.of("retentionPolicy", Map.of("maxRetentionDays", 1)))).then().statusCode(400);
        post(policies, Map.of("name", "no-actions", "instanceSchedulePolicy", Map.of("timeZone", "UTC"))).then().statusCode(400);
        post(policies, Map.of("name", "bad-cron", "instanceSchedulePolicy", Map.of("vmStopSchedule", Map.of("schedule", "daily")))).then().statusCode(400);
        post(policies, Map.of("name", "no-cadence", "snapshotSchedulePolicy", Map.of("schedule", Map.of()))).then().statusCode(400);
        post(policies, Map.of("name", "bad-start", "snapshotSchedulePolicy", Map.of("schedule",
                Map.of("dailySchedule", Map.of("daysInCycle", 1, "startTime", "25:00"))))).then().statusCode(400);
        post(policies, Map.of("name", "bad-hours", "snapshotSchedulePolicy", Map.of("schedule",
                Map.of("hourlySchedule", Map.of("hoursInCycle", 0, "startTime", "00:00"))))).then().statusCode(400);
        post(policies, Map.of("name", "placement", "groupPlacementPolicy", Map.of("vmCount", 2))).then().statusCode(501);
        post(policies, Map.of("name", "empty-placement", "groupPlacementPolicy", Map.of())).then().statusCode(400);
        post(root + "/global/resourcePolicies", instanceSchedule("global")).then().statusCode(400);
        given().get(policies + "/missing").then().statusCode(404);
    }

    @Test void weeklyAndHourlySnapshotSchedulesRoundTrip() throws Exception {
        String root = root(), policies = root + REGION + "/resourcePolicies";
        done(root, post(policies, Map.of("name", "weekly", "snapshotSchedulePolicy", Map.of("schedule", Map.of("weeklySchedule",
                Map.of("dayOfWeeks", List.of(Map.of("day", "MONDAY", "startTime", "02:00"), Map.of("day", "FRIDAY", "startTime", "03:00"))))))));
        done(root, post(policies, Map.of("name", "hourly", "snapshotSchedulePolicy", Map.of("schedule", Map.of("hourlySchedule",
                Map.of("hoursInCycle", 6, "startTime", "01:00"))))));
        assertEquals("FRIDAY", given().get(policies + "/weekly").jsonPath().getString("snapshotSchedulePolicy.schedule.weeklySchedule.dayOfWeeks[1].day"));
        assertEquals(6, given().get(policies + "/hourly").jsonPath().getInt("snapshotSchedulePolicy.schedule.hourlySchedule.hoursInCycle"));
    }

    @Test void instanceInsertAttachRemoveAndInUseDelete() throws Exception {
        String root = root(), policies = root + REGION + "/resourcePolicies", instances = root + ZONE + "/instances";
        network(root);
        done(root, post(policies, instanceSchedule("schedule")));
        done(root, post(policies, instanceSchedule("other")));
        done(root, post(policies, snapshotSchedule("snapshots")));
        post(instances, vm("missing", List.of("regions/us-central1/resourcePolicies/nope"))).then().statusCode(404);
        post(instances, vm("wrong-type", List.of("regions/us-central1/resourcePolicies/snapshots"))).then().statusCode(400);
        post(instances, vm("too-many", List.of("regions/us-central1/resourcePolicies/schedule", "regions/us-central1/resourcePolicies/other"))).then().statusCode(400);
        given().get(instances + "/missing").then().statusCode(404);
        done(root, post(instances, vm("vm", List.of("regions/us-central1/resourcePolicies/schedule"))));
        String link = given().get(policies + "/schedule").jsonPath().getString("selfLink");
        assertEquals(List.of(link), given().get(instances + "/vm").jsonPath().getList("resourcePolicies"));
        assertEquals(List.of(link), given().get(instances).jsonPath().getList("items[0].resourcePolicies"));
        JsonPath blocked = given().delete(policies + "/schedule").then().statusCode(400).extract().jsonPath();
        assertEquals("resourceInUseByAnotherResource", blocked.getString("error.errors[0].reason"));
        post(instances + "/vm/addResourcePolicies", Map.of("resourcePolicies", List.of("regions/us-central1/resourcePolicies/schedule"))).then().statusCode(400);
        post(instances + "/vm/addResourcePolicies", Map.of("resourcePolicies", List.of("regions/us-central1/resourcePolicies/other"))).then().statusCode(400);
        post(instances + "/vm/addResourcePolicies", Map.of("resourcePolicies", List.of("regions/us-central1/resourcePolicies/nope"))).then().statusCode(404);
        post(instances + "/vm/removeResourcePolicies", Map.of("resourcePolicies", List.of("regions/us-central1/resourcePolicies/other"))).then().statusCode(400);
        post(instances + "/vm/removeResourcePolicies", Map.of()).then().statusCode(400);
        done(root, post(instances + "/vm/removeResourcePolicies", Map.of("resourcePolicies", List.of(link))));
        assertNull(given().get(instances + "/vm").jsonPath().get("resourcePolicies"));
        done(root, post(instances + "/vm/addResourcePolicies", Map.of("resourcePolicies", List.of(link))));
        assertEquals(List.of(link), given().get(instances + "/vm").jsonPath().getList("resourcePolicies"));
        done(root, given().delete(instances + "/vm"));
        done(root, given().delete(policies + "/schedule"));
    }

    @Test void diskInsertAttachAndRemove() throws Exception {
        String root = root(), policies = root + REGION + "/resourcePolicies", disks = root + ZONE + "/disks";
        done(root, post(policies, snapshotSchedule("snapshots")));
        done(root, post(policies, instanceSchedule("schedule")));
        done(root, post(disks, Map.of("name", "data", "sizeGb", "10")));
        post(disks, Map.of("name", "wrong", "sizeGb", "10", "resourcePolicies", List.of("regions/us-central1/resourcePolicies/schedule"))).then().statusCode(400);
        done(root, post(root + "/regions/europe-west1/resourcePolicies", snapshotSchedule("eu")));
        post(disks, Map.of("name", "other-region", "sizeGb", "10", "resourcePolicies", List.of("regions/europe-west1/resourcePolicies/eu"))).then().statusCode(400);
        post(disks + "/data/addResourcePolicies", Map.of("resourcePolicies", List.of("regions/us-central1/resourcePolicies/schedule"))).then().statusCode(400);
        done(root, post(disks + "/data/addResourcePolicies", Map.of("resourcePolicies", List.of("projects/" + root.substring(root.lastIndexOf('/') + 1)
                + "/regions/us-central1/resourcePolicies/snapshots"))));
        String link = given().get(policies + "/snapshots").jsonPath().getString("selfLink");
        assertEquals(List.of(link), given().get(disks + "/data").jsonPath().getList("resourcePolicies"));
        given().delete(policies + "/snapshots").then().statusCode(400);
        done(root, post(disks + "/data/removeResourcePolicies", Map.of("resourcePolicies", List.of(link))));
        assertNull(given().get(disks + "/data").jsonPath().get("resourcePolicies"));
        done(root, post(disks, Map.of("name", "born-attached", "sizeGb", "10", "resourcePolicies", List.of(link))));
        assertEquals(List.of(link), given().get(disks + "/born-attached").jsonPath().getList("resourcePolicies"));
        done(root, given().delete(disks + "/born-attached"));
        done(root, given().delete(policies + "/snapshots"));
    }

    private int insertWithSchedule(String policies, String name, String cadence, Map<String, Object> cycle) {
        return post(policies, Map.of("name", name, "snapshotSchedulePolicy", Map.of("schedule", Map.of(cadence, cycle)))).statusCode();
    }

    @Test void snapshotScheduleTimesFollowGcpRules() throws Exception {
        String root = root(), policies = root + REGION + "/resourcePolicies";
        int i = 0;
        for (String valid : List.of("00:00", "04:00", "08:00", "12:00", "16:00", "20:00", "4:00", "13:00-5", "06:00+2")) {
            assertEquals(200, insertWithSchedule(policies, "daily-" + i++, "dailySchedule", Map.of("daysInCycle", 1, "startTime", valid)), valid);
        }
        for (String invalid : List.of("05:00", "04:30", "13:00", "13:00+5", "24:00", "4", "04:00Z", "08:00-15")) {
            assertEquals(400, insertWithSchedule(policies, "daily-bad-" + i++, "dailySchedule", Map.of("daysInCycle", 1, "startTime", invalid)), invalid);
        }
        for (int days : List.of(0, 2, 7)) {
            assertEquals(400, insertWithSchedule(policies, "days-" + i++, "dailySchedule", Map.of("daysInCycle", days, "startTime", "04:00")), "days " + days);
        }
        for (int hours : List.of(1, 5, 7, 23)) {
            assertEquals(200, insertWithSchedule(policies, "hours-" + i++, "hourlySchedule", Map.of("hoursInCycle", hours, "startTime", "03:00")), "hours " + hours);
        }
        for (String invalid : List.of("03:30", "03:01", "24:00", "3")) {
            assertEquals(400, insertWithSchedule(policies, "hourly-bad-" + i++, "hourlySchedule", Map.of("hoursInCycle", 6, "startTime", invalid)), invalid);
            assertEquals(400, insertWithSchedule(policies, "weekly-bad-" + i++, "weeklySchedule",
                    Map.of("dayOfWeeks", List.of(Map.of("day", "MONDAY", "startTime", invalid)))), invalid);
        }
        done(root, post(policies, Map.of("name", "padded", "snapshotSchedulePolicy", Map.of("schedule",
                Map.of("weeklySchedule", Map.of("dayOfWeeks", List.of(Map.of("day", "MONDAY", "startTime", "9:00"))))))));
        assertEquals("09:00", given().get(policies + "/padded").jsonPath().getString("snapshotSchedulePolicy.schedule.weeklySchedule.dayOfWeeks[0].startTime"));
        assertEquals("04:00", given().get(policies + "/daily-6").jsonPath().getString("snapshotSchedulePolicy.schedule.dailySchedule.startTime"));
        assertEquals("13:00-5", given().get(policies + "/daily-7").jsonPath().getString("snapshotSchedulePolicy.schedule.dailySchedule.startTime"));
    }

    private int insertWithCron(String policies, String name, String cron) {
        return post(policies, Map.of("name", name, "instanceSchedulePolicy",
                Map.of("vmStartSchedule", Map.of("schedule", cron)))).statusCode();
    }

    @Test void cronExpressionsAreValidated() {
        String root = root(), policies = root + REGION + "/resourcePolicies";
        int i = 0;
        for (String valid : List.of("0 8 * * 1-5", "*/15 0-6 * * *", "0 8 * JAN MON", "0,30 1-5/2 1,15 jan-mar sun-sat", "5 4 * * 7", "0-30/10 * * * *",
                "5/10 * * * *", "0 1/2 * * *", "0 0 1/7 * *", "0 0 * 2/3 *", "0 0 * * MON/2")) {
            assertEquals(200, insertWithCron(policies, "ok-" + i++, valid), valid);
        }
        for (String invalid : List.of("99 99 32 13 8", "60 * * * *", "* 24 * * *", "* * 0 * *", "* * 32 * *", "* * * 0 *", "* * * 13 *",
                "* * * * 8", "5-1 * * * *", "*/0 * * * *", "* * * * * *", "* * * *", "", "   ", "a * * * *", "1- * * * *",
                "1,,2 * * * *", "* * * FOO *", "* * * * FUNDAY", "*/ * * * *", "1/5/2 * * * *",
                "60/5 * * * *", "5/0 * * * *", "5/ * * * *", "/5 * * * *", "* 24/2 * * *", "0 0 * * FUNDAY/2")) {
            assertEquals(400, insertWithCron(policies, "bad-" + i++, invalid), "'" + invalid + "'");
        }
    }

    @Test void timeZonesAreValidated() {
        String root = root(), policies = root + REGION + "/resourcePolicies";
        int i = 0;
        for (String valid : List.of("UTC", "America/Bogota", "Etc/UTC", "Europe/London")) {
            assertEquals(200, post(policies, Map.of("name", "tz-" + i++, "instanceSchedulePolicy", Map.of("timeZone", valid,
                    "vmStartSchedule", Map.of("schedule", "0 8 * * *")))).statusCode(), valid);
        }
        for (String invalid : List.of("Mars/Olympus", "", "   ", "EST", "+05:00", "UTC+5", "america/bogota")) {
            assertEquals(400, post(policies, Map.of("name", "tz-bad-" + i++, "instanceSchedulePolicy", Map.of("timeZone", invalid,
                    "vmStartSchedule", Map.of("schedule", "0 8 * * *")))).statusCode(), "'" + invalid + "'");
        }
    }

    @Test void inlineBootDiskAttachesSnapshotPolicy() throws Exception {
        String root = root(), policies = root + REGION + "/resourcePolicies", instances = root + ZONE + "/instances", disks = root + ZONE + "/disks";
        network(root);
        done(root, post(policies, snapshotSchedule("snapshots")));
        String link = given().get(policies + "/snapshots").jsonPath().getString("selfLink");
        Map<String, Object> body = new HashMap<>(vm("inline", List.of()));
        body.remove("resourcePolicies");
        body.put("disks", List.of(Map.of("boot", true, "autoDelete", true, "initializeParams",
                Map.of("diskSizeGb", "10", "resourcePolicies", List.of("regions/us-central1/resourcePolicies/snapshots")))));
        done(root, post(instances, body));
        assertEquals(List.of(link), given().get(disks + "/inline").jsonPath().getList("resourcePolicies"));
        assertNull(given().get(instances + "/inline").jsonPath().get("disks[0].initializeParams"));
        JsonPath blocked = given().delete(policies + "/snapshots").then().statusCode(400).extract().jsonPath();
        assertEquals("resourceInUseByAnotherResource", blocked.getString("error.errors[0].reason"));
        done(root, given().delete(instances + "/inline"));
        given().get(disks + "/inline").then().statusCode(404);
        done(root, given().delete(policies + "/snapshots"));
    }

    @Test void inlineDiskPolicyIsValidated() throws Exception {
        String root = root(), policies = root + REGION + "/resourcePolicies", instances = root + ZONE + "/instances";
        network(root);
        done(root, post(policies, instanceSchedule("schedule")));
        Map<String, Object> body = new HashMap<>(vm("bad-inline", List.of()));
        body.remove("resourcePolicies");
        body.put("disks", List.of(Map.of("boot", true, "initializeParams",
                Map.of("diskSizeGb", "10", "resourcePolicies", List.of("regions/us-central1/resourcePolicies/schedule")))));
        post(instances, body).then().statusCode(400);
        given().get(instances + "/bad-inline").then().statusCode(404);
    }

    private Response patch(String path, Object body) {
        return given().contentType("application/json").body(body).patch(path);
    }

    @Test void patchWithoutMaskReplacesSentFieldsLikeTerraform() throws Exception {
        String root = root(), policies = root + REGION + "/resourcePolicies", policy = policies + "/schedule";
        done(root, post(policies, instanceSchedule("schedule")));
        JsonPath before = given().get(policy).jsonPath();
        Map<String, Object> body = new HashMap<>();
        body.put("name", "schedule");
        body.put("description", "weekday office hours");
        body.put("instanceSchedulePolicy", Map.of("timeZone", "Europe/London",
                "vmStartSchedule", Map.of("schedule", "0 8 * * 1-5"), "vmStopSchedule", Map.of("schedule", "0 18 * * 1-5")));
        body.put("diskConsistencyGroupPolicy", null);
        Response op = patch(policy, body);
        assertEquals("update", op.jsonPath().getString("operationType"));
        done(root, op);
        JsonPath after = given().get(policy).jsonPath();
        assertEquals("weekday office hours", after.getString("description"));
        assertEquals("Europe/London", after.getString("instanceSchedulePolicy.timeZone"));
        assertEquals("0 18 * * 1-5", after.getString("instanceSchedulePolicy.vmStopSchedule.schedule"));
        assertEquals("READY", after.getString("status"));
        assertNull(after.get("diskConsistencyGroupPolicy"));
        for (String field : List.of("id", "selfLink", "creationTimestamp", "region", "kind")) {
            assertEquals(before.getString(field), after.getString(field), field);
        }
    }

    @Test void patchWithoutMaskMergesGcloudPartialBodies() throws Exception {
        String root = root(), policies = root + REGION + "/resourcePolicies";
        done(root, post(policies, instanceSchedule("schedule")));
        done(root, patch(policies + "/schedule", Map.of("name", "schedule",
                "instanceSchedulePolicy", Map.of("vmStopSchedule", Map.of("schedule", "0 20 * * *")))));
        JsonPath instance = given().get(policies + "/schedule").jsonPath();
        assertEquals("0 20 * * *", instance.getString("instanceSchedulePolicy.vmStopSchedule.schedule"));
        assertEquals("0 7 * * 1-5", instance.getString("instanceSchedulePolicy.vmStartSchedule.schedule"));
        assertEquals("America/Bogota", instance.getString("instanceSchedulePolicy.timeZone"));
        assertEquals("start and stop", instance.getString("description"));

        done(root, post(policies, snapshotSchedule("snapshots")));
        String snapshots = policies + "/snapshots";
        done(root, patch(snapshots, Map.of("name", "snapshots",
                "snapshotSchedulePolicy", Map.of("retentionPolicy", Map.of("maxRetentionDays", 9)))));
        JsonPath retention = given().get(snapshots).jsonPath();
        assertEquals(9, retention.getInt("snapshotSchedulePolicy.retentionPolicy.maxRetentionDays"));
        assertEquals("KEEP_AUTO_SNAPSHOTS", retention.getString("snapshotSchedulePolicy.retentionPolicy.onSourceDiskDelete"));
        assertEquals("04:00", retention.getString("snapshotSchedulePolicy.schedule.dailySchedule.startTime"));

        done(root, patch(snapshots, Map.of("name", "snapshots", "description", "via-gcloud", "snapshotSchedulePolicy",
                Map.of("schedule", Map.of("hourlySchedule", Map.of("hoursInCycle", 6, "startTime", "02:00"))))));
        JsonPath switched = given().get(snapshots).jsonPath();
        assertEquals(6, switched.getInt("snapshotSchedulePolicy.schedule.hourlySchedule.hoursInCycle"));
        assertNull(switched.get("snapshotSchedulePolicy.schedule.dailySchedule"));
        assertEquals(9, switched.getInt("snapshotSchedulePolicy.retentionPolicy.maxRetentionDays"));
        assertEquals("test", switched.getString("snapshotSchedulePolicy.snapshotProperties.labels.env"));
        assertEquals("via-gcloud", switched.getString("description"));

        Map<String, Object> clear = new HashMap<>();
        clear.put("description", null);
        done(root, patch(snapshots, clear));
        assertNull(given().get(snapshots).jsonPath().get("description"));
    }

    @Test void patchWithMaskChangesOnlyListedPaths() throws Exception {
        String root = root(), policies = root + REGION + "/resourcePolicies", policy = policies + "/snapshots";
        done(root, post(policies, snapshotSchedule("snapshots")));
        done(root, patch(policy + "?updateMask=description", Map.of("description", "nightly",
                "snapshotSchedulePolicy", Map.of("schedule", Map.of("dailySchedule", Map.of("daysInCycle", 1, "startTime", "08:00"))))));
        JsonPath masked = given().get(policy).jsonPath();
        assertEquals("nightly", masked.getString("description"));
        assertEquals("04:00", masked.getString("snapshotSchedulePolicy.schedule.dailySchedule.startTime"));
        done(root, patch(policy + "?updateMask=snapshotSchedulePolicy.retentionPolicy.maxRetentionDays",
                Map.of("snapshotSchedulePolicy", Map.of("retentionPolicy", Map.of("maxRetentionDays", 30)))));
        JsonPath nested = given().get(policy).jsonPath();
        assertEquals(30, nested.getInt("snapshotSchedulePolicy.retentionPolicy.maxRetentionDays"));
        assertEquals("KEEP_AUTO_SNAPSHOTS", nested.getString("snapshotSchedulePolicy.retentionPolicy.onSourceDiskDelete"));
        assertEquals("test", nested.getString("snapshotSchedulePolicy.snapshotProperties.labels.env"));
        done(root, patch(policy + "?updateMask=description", Map.of()));
        assertNull(given().get(policy).jsonPath().get("description"));
        patch(policy + "?updateMask=name", Map.of("name", "snapshots")).then().statusCode(400);
        patch(policy + "?updateMask=snapshotSchedulePolicy", Map.of()).then().statusCode(400);
        done(root, patch(policy + "?updateMask=description", Map.of("description", "kept")));
        for (String mask : List.of("description.x", ".", "description,", ",description", "description..x",
                "snapshotSchedulePolicy.schedule.dailySchedule.startTime.hour", "snapshotSchedulePolicy.snapshotProperties.storageLocations.x")) {
            patch(policy + "?updateMask=" + mask, Map.of()).then().statusCode(400);
        }
        patch(policy + "?updateMask=snapshotSchedulePolicy.retentionPolicy.maxRetentionDays",
                Map.of("snapshotSchedulePolicy", Map.of("retentionPolicy", "flat"))).then().statusCode(400);
        patch(policy + "?updateMask=description", Map.of("description", Map.of())).then().statusCode(400);
        patch(policy, Map.of("description", List.of("a"))).then().statusCode(400);
        JsonPath intact = given().get(policy).jsonPath();
        assertEquals("kept", intact.getString("description"));
        assertEquals(30, intact.getInt("snapshotSchedulePolicy.retentionPolicy.maxRetentionDays"));
    }

    @Test void patchRejectsInvalidChangesAndKeepsThePolicy() throws Exception {
        String root = root(), policies = root + REGION + "/resourcePolicies", policy = policies + "/schedule";
        done(root, post(policies, instanceSchedule("schedule")));
        patch(policy, Map.of("name", "renamed")).then().statusCode(400);
        patch(policy, Map.of("snapshotSchedulePolicy", Map.of("schedule", Map.of("dailySchedule", Map.of("daysInCycle", 1, "startTime", "04:00")))))
                .then().statusCode(400);
        patch(policy, Map.of("instanceSchedulePolicy", Map.of("vmStartSchedule", Map.of("schedule", "99 * * * *")))).then().statusCode(400);
        patch(policy, Map.of("groupPlacementPolicy", Map.of("vmCount", 2))).then().statusCode(501);
        given().contentType("application/json").body(instanceSchedule("schedule")).put(policy).then().statusCode(501);
        patch(policies + "/missing", Map.of("description", "x")).then().statusCode(404);
        JsonPath unchanged = given().get(policy).jsonPath();
        assertEquals("start and stop", unchanged.getString("description"));
        assertEquals("0 7 * * 1-5", unchanged.getString("instanceSchedulePolicy.vmStartSchedule.schedule"));
        assertNull(unchanged.get("snapshotSchedulePolicy"));
    }

    @Test void patchingAnAttachedPolicyKeepsTheAttachment() throws Exception {
        String root = root(), policies = root + REGION + "/resourcePolicies", disks = root + ZONE + "/disks";
        done(root, post(policies, snapshotSchedule("snapshots")));
        String link = given().get(policies + "/snapshots").jsonPath().getString("selfLink");
        done(root, post(disks, Map.of("name", "data", "sizeGb", "10", "resourcePolicies", List.of(link))));
        done(root, patch(policies + "/snapshots", Map.of("snapshotSchedulePolicy", Map.of("schedule",
                Map.of("weeklySchedule", Map.of("dayOfWeeks", List.of(Map.of("day", "SUNDAY", "startTime", "02:00"))))))));
        assertEquals("SUNDAY", given().get(policies + "/snapshots").jsonPath()
                .getString("snapshotSchedulePolicy.schedule.weeklySchedule.dayOfWeeks[0].day"));
        assertEquals(List.of(link), given().get(disks + "/data").jsonPath().getList("resourcePolicies"));
        given().delete(policies + "/snapshots").then().statusCode(400);
    }
}
