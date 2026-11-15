package io.floci.gcp.services.billingbudgets;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class BillingBudgetsRestIntegrationTest {

    private static final String MINIMAL =
            "{\"displayName\":\"%s\",\"amount\":{\"specifiedAmount\":{\"currencyCode\":\"USD\",\"units\":\"10\"}}}";

    @Test
    void createFillsInTheDefaultsGcpReturns() {
        String base = "/v1/billingAccounts/0000AA-111111-222222/budgets";

        given().contentType("application/json").body(MINIMAL.formatted("defaults"))
                .when().post(base)
                .then().statusCode(200)
                .body("name", matchesPattern(
                        "billingAccounts/0000AA-111111-222222/budgets/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                .body("displayName", equalTo("defaults"))
                .body("budgetFilter.creditTypesTreatment", equalTo("INCLUDE_ALL_CREDITS"))
                .body("budgetFilter.calendarPeriod", equalTo("MONTH"))
                .body("amount.specifiedAmount.units", equalTo("10"))
                .body("notificationsRule.size()", equalTo(0))
                .body("etag", matchesPattern("[0-9]+"));
    }

    @Test
    void thresholdRulesDefaultToCurrentSpend() {
        given().contentType("application/json")
                .body("{\"amount\":{\"lastPeriodAmount\":{}},\"thresholdRules\":[{\"thresholdPercent\":0.5},"
                        + "{\"thresholdPercent\":0.9,\"spendBasis\":\"FORECASTED_SPEND\"}]}")
                .when().post("/v1/billingAccounts/0000AA-111111-333333/budgets")
                .then().statusCode(200)
                .body("thresholdRules.spendBasis", contains("CURRENT_SPEND", "FORECASTED_SPEND"))
                .body("displayName", nullValue());
    }

    @Test
    void getReturnsTheCreatedBudgetAndDeleteRemovesIt() {
        String name = create("0000AA-111111-444444", "lifecycle");

        given().when().get("/v1/" + name)
                .then().statusCode(200)
                .body("name", equalTo(name))
                .body("displayName", equalTo("lifecycle"));

        given().when().delete("/v1/" + name)
                .then().statusCode(200)
                .body("size()", equalTo(0));

        given().when().get("/v1/" + name)
                .then().statusCode(404)
                .body("error.status", equalTo("NOT_FOUND"));

        given().when().delete("/v1/" + name)
                .then().statusCode(200);
    }

    @Test
    void listFollowsCreationOrderAndPages() {
        String account = "0000AA-111111-555555";
        String base = "/v1/billingAccounts/" + account + "/budgets";

        given().when().get(base)
                .then().statusCode(200)
                .body("budgets", nullValue())
                .body("nextPageToken", nullValue());

        String first = create(account, "first");
        String second = create(account, "second");
        String third = create(account, "third");

        given().when().get(base)
                .then().statusCode(200)
                .body("budgets.name", contains(first, second, third))
                .body("nextPageToken", nullValue());

        String token = given().when().get(base + "?pageSize=2")
                .then().statusCode(200)
                .body("budgets.name", contains(first, second))
                .extract().path("nextPageToken");

        given().when().get(base + "?pageSize=2&pageToken=" + token)
                .then().statusCode(200)
                .body("budgets.name", contains(third))
                .body("nextPageToken", nullValue());
    }

    @Test
    void listRejectsAMalformedPageToken() {
        given().when().get("/v1/billingAccounts/0000AA-111111-555556/budgets?pageToken=garbage")
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
    }

    @Test
    void scopeReturnsOnlyBudgetsFilteredToThatProject() {
        String account = "0000AA-111111-666666";
        String base = "/v1/billingAccounts/" + account + "/budgets";
        create(account, "all-projects");
        String scoped = given().contentType("application/json")
                .body("{\"budgetFilter\":{\"projects\":[\"projects/budget-scope-test\"]},"
                        + "\"amount\":{\"lastPeriodAmount\":{}}}")
                .when().post(base)
                .then().statusCode(200)
                .extract().path("name");

        given().when().get(base + "?scope=projects/budget-scope-test")
                .then().statusCode(200)
                .body("budgets.name", contains(scoped));

        given().when().get(base + "?scope=projects/other-project")
                .then().statusCode(200)
                .body("budgets", nullValue());
    }

    @Test
    void updateWithAMaskChangesOnlyTheMaskedFields() {
        String name = create("0000AA-111111-777777", "before");
        String etag = given().when().get("/v1/" + name).then().extract().path("etag");

        given().contentType("application/json")
                .body("{\"displayName\":\"after\",\"amount\":{\"lastPeriodAmount\":{}}}")
                .when().patch("/v1/" + name + "?updateMask=displayName")
                .then().statusCode(200)
                .body("displayName", equalTo("after"))
                .body("amount.specifiedAmount.units", equalTo("10"))
                .body("etag", not(equalTo(etag)));

        given().contentType("application/json")
                .body("{\"amount\":{\"lastPeriodAmount\":{}}}")
                .when().patch("/v1/" + name + "?updateMask=amount")
                .then().statusCode(200)
                .body("amount.lastPeriodAmount.size()", equalTo(0))
                .body("amount.specifiedAmount", nullValue());
    }

    @Test
    void updateWithoutAMaskChangesOnlyFieldsWithValues() {
        String name = create("0000AA-111111-888888", "kept");

        given().contentType("application/json")
                .body("{\"thresholdRules\":[{\"thresholdPercent\":0.5}]}")
                .when().patch("/v1/" + name)
                .then().statusCode(200)
                .body("displayName", equalTo("kept"))
                .body("thresholdRules.thresholdPercent", contains(0.5f))
                .body("thresholdRules.spendBasis", contains("CURRENT_SPEND"));
    }

    @Test
    void updateAcceptsAStaleEtagAndIgnoresNameInTheMask() {
        String name = create("0000AA-111111-999999", "etag");
        String stale = given().when().get("/v1/" + name).then().extract().path("etag");
        given().contentType("application/json").body("{\"displayName\":\"one\"}")
                .when().patch("/v1/" + name + "?updateMask=displayName")
                .then().statusCode(200);

        given().contentType("application/json")
                .body("{\"displayName\":\"two\",\"etag\":\"" + stale + "\"}")
                .when().patch("/v1/" + name + "?updateMask=displayName")
                .then().statusCode(200)
                .body("displayName", equalTo("two"));

        given().contentType("application/json")
                .body("{\"name\":\"billingAccounts/0000AA-111111-999999/budgets/other\"}")
                .when().patch("/v1/" + name + "?updateMask=name")
                .then().statusCode(200)
                .body("name", equalTo(name));
    }

    @Test
    void updateRejectsAnUnknownMaskField() {
        String name = create("0000AA-111112-000000", "mask");

        given().contentType("application/json").body("{\"displayName\":\"z\"}")
                .when().patch("/v1/" + name + "?updateMask=notAField")
                .then().statusCode(400)
                .body("error.message", equalTo("Invalid update mask: not_a_field"));
    }

    @Test
    void updateOfAMissingBudgetIsNotFound() {
        given().contentType("application/json").body("{\"displayName\":\"z\"}")
                .when().patch("/v1/billingAccounts/0000AA-111112-111111/budgets/"
                        + "00000000-0000-0000-0000-000000000000?updateMask=displayName")
                .then().statusCode(404)
                .body("error.status", equalTo("NOT_FOUND"));
    }

    @Test
    void malformedNamesFollowGcp() {
        given().when().get("/v1/billingAccounts/not-an-id/budgets")
                .then().statusCode(400)
                .body("error.message", equalTo("Invalid billing account name: billingAccounts/not-an-id"));

        given().when().get("/v1/billingAccounts/0000AA-111112-222222/budgets/not-a-budget")
                .then().statusCode(404)
                .body("error.status", equalTo("NOT_FOUND"));
    }

    @Test
    void createValidatesTheBudgetLikeGcp() {
        String base = "/v1/billingAccounts/0000AA-111112-333333/budgets";

        rejects(base, "{\"displayName\":\"no amount\"}", "Request contains an invalid argument.");
        rejects(base, "{\"amount\":{\"specifiedAmount\":{\"currencyCode\":\"BRL\",\"units\":\"10\"}}}",
                "Request contains an invalid argument.");
        rejects(base, "{\"amount\":{\"specifiedAmount\":{\"units\":\"1\"},\"lastPeriodAmount\":{}}}",
                "Invalid value at 'budget.amount' (oneof), oneof field 'budget_amount' is already set."
                        + " Cannot set 'lastPeriodAmount'");
        rejects(base, "{\"displayName\":\"" + "x".repeat(61) + "\",\"amount\":{\"lastPeriodAmount\":{}}}",
                "Budget display name " + "x".repeat(61) + " exceeds length limit 60");
        rejects(base, "{\"amount\":{\"lastPeriodAmount\":{}},\"thresholdRules\":[{\"thresholdPercent\":-1}]}",
                "Threshold percent must be non-negative: -1.0");
        rejects(base, "{\"amount\":{\"lastPeriodAmount\":{}},"
                        + "\"notificationsRule\":{\"pubsubTopic\":\"projects/p/topics/t\"}}",
                "Invalid schema version: ");
        rejects(base, "{\"amount\":{\"lastPeriodAmount\":{}},\"budgetFilter\":{\"customPeriod\":"
                        + "{\"startDate\":{\"year\":2026,\"month\":1,\"day\":1}}}}",
                "Last Period Amount cannot be set with Custom Period.");
        rejects(base, "{\"amount\":{\"specifiedAmount\":{\"currencyCode\":\"USD\",\"units\":\"1\"}},"
                        + "\"budgetFilter\":{\"customPeriod\":{\"startDate\":{\"year\":2026,\"month\":1,\"day\":1}}},"
                        + "\"thresholdRules\":[{\"thresholdPercent\":0.9,\"spendBasis\":\"FORECASTED_SPEND\"}]}",
                "Threshold percent based on forecast spend cannot be set with Custom Period.");
        given().contentType("application/json")
                .body("{\"amount\":{\"lastPeriodAmount\":{}},\"budgetFilter\":{\"calendarPeriod\":\"QUARTER\","
                        + "\"customPeriod\":{\"startDate\":{\"year\":2026,\"month\":1,\"day\":1}}}}")
                .when().post(base)
                .then().statusCode(400)
                .body("error.message", startsWith("Invalid value at 'budget.budget_filter' (oneof)"));
    }

    @Test
    void customPeriodBudgetHasNoCalendarPeriod() {
        given().contentType("application/json")
                .body("{\"amount\":{\"specifiedAmount\":{\"currencyCode\":\"USD\",\"units\":\"5\"}},"
                        + "\"budgetFilter\":{\"customPeriod\":{\"startDate\":{\"year\":2026,\"month\":11,\"day\":1}}},"
                        + "\"notificationsRule\":{\"pubsubTopic\":\"projects/p/topics/t\",\"schemaVersion\":\"1.0\"}}")
                .when().post("/v1/billingAccounts/0000AA-111112-444444/budgets")
                .then().statusCode(200)
                .body("budgetFilter.calendarPeriod", nullValue())
                .body("budgetFilter.customPeriod.startDate.month", equalTo(11))
                .body("notificationsRule.pubsubTopic", equalTo("projects/p/topics/t"));
    }

    @Test
    void pageSizeAboveOneHundredIsCappedAtOneHundred() {
        String account = "0000AA-111112-555555";
        for (int i = 0; i < 101; i++) {
            create(account, "cap-" + i);
        }

        given().when().get("/v1/billingAccounts/" + account + "/budgets?pageSize=500")
                .then().statusCode(200)
                .body("budgets", hasSize(100))
                .body("nextPageToken", not(nullValue()));

        given().when().get("/v1/billingAccounts/" + account + "/budgets")
                .then().statusCode(200)
                .body("budgets", hasSize(100));
    }

    private static String create(String account, String displayName) {
        return given().contentType("application/json").body(MINIMAL.formatted(displayName))
                .when().post("/v1/billingAccounts/" + account + "/budgets")
                .then().statusCode(200)
                .extract().path("name");
    }

    private static void rejects(String base, String body, String message) {
        given().contentType("application/json").body(body)
                .when().post(base)
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"))
                .body("error.message", equalTo(message));
    }
}
