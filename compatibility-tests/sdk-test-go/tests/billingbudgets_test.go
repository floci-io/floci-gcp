package tests

import (
	"context"
	"errors"
	"net/http"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	billingbudgets "google.golang.org/api/billingbudgets/v1"
	"google.golang.org/api/googleapi"
	"google.golang.org/api/option"
)

// Exercises the Cloud Billing Budget API v1 client that Terraform's google_billing_budget uses.
func TestBillingBudgetsLifecycle(t *testing.T) {
	ctx := context.Background()
	svc, err := billingbudgets.NewService(ctx, option.WithEndpoint(iamEndpoint()+"/"), option.WithoutAuthentication())
	require.NoError(t, err)

	parent := "billingAccounts/0000GO-000000-000001"
	displayName := uniqueName("go-budget")

	created, err := svc.BillingAccounts.Budgets.Create(parent, &billingbudgets.GoogleCloudBillingBudgetsV1Budget{
		DisplayName: displayName,
		Amount: &billingbudgets.GoogleCloudBillingBudgetsV1BudgetAmount{
			SpecifiedAmount: &billingbudgets.GoogleTypeMoney{CurrencyCode: "USD", Units: 10},
		},
		ThresholdRules: []*billingbudgets.GoogleCloudBillingBudgetsV1ThresholdRule{{ThresholdPercent: 0.5}},
	}).Do()
	require.NoError(t, err)
	assert.Contains(t, created.Name, parent+"/budgets/")
	assert.Equal(t, "MONTH", created.BudgetFilter.CalendarPeriod)
	assert.Equal(t, "INCLUDE_ALL_CREDITS", created.BudgetFilter.CreditTypesTreatment)
	assert.Equal(t, "CURRENT_SPEND", created.ThresholdRules[0].SpendBasis)
	assert.NotEmpty(t, created.Etag)

	got, err := svc.BillingAccounts.Budgets.Get(created.Name).Do()
	require.NoError(t, err)
	assert.Equal(t, displayName, got.DisplayName)

	updated, err := svc.BillingAccounts.Budgets.Patch(created.Name, &billingbudgets.GoogleCloudBillingBudgetsV1Budget{
		DisplayName: displayName + "-2",
	}).UpdateMask("displayName").Do()
	require.NoError(t, err)
	assert.Equal(t, displayName+"-2", updated.DisplayName)
	assert.Equal(t, int64(10), updated.Amount.SpecifiedAmount.Units)

	list, err := svc.BillingAccounts.Budgets.List(parent).Do()
	require.NoError(t, err)
	names := make([]string, 0, len(list.Budgets))
	for _, b := range list.Budgets {
		names = append(names, b.Name)
	}
	assert.Contains(t, names, created.Name)

	_, err = svc.BillingAccounts.Budgets.Delete(created.Name).Do()
	require.NoError(t, err)

	_, err = svc.BillingAccounts.Budgets.Get(created.Name).Do()
	var apiErr *googleapi.Error
	require.True(t, errors.As(err, &apiErr), "expected a googleapi.Error, got %v", err)
	assert.Equal(t, http.StatusNotFound, apiErr.Code)
}
