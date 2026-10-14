package tests

import (
	"context"
	"errors"
	"strconv"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	cloudbilling "google.golang.org/api/cloudbilling/v1"
	crm "google.golang.org/api/cloudresourcemanager/v1"
	"google.golang.org/api/googleapi"
	"google.golang.org/api/option"
)

// Exercises the same Resource Manager v1 and Cloud Billing v1 clients the Terraform
// google_project resource uses.
func TestResourceManagerProjectLifecycle(t *testing.T) {
	ctx := context.Background()
	opts := []option.ClientOption{option.WithEndpoint(iamEndpoint() + "/"), option.WithoutAuthentication()}
	rm, err := crm.NewService(ctx, opts...)
	require.NoError(t, err)
	billing, err := cloudbilling.NewService(ctx, opts...)
	require.NoError(t, err)

	projectID := uniqueName("go-proj")

	perms, err := billing.BillingAccounts.TestIamPermissions("billingAccounts/000000-AAAAAA-BBBBBB",
		&cloudbilling.TestIamPermissionsRequest{Permissions: []string{"billing.resourceAssociations.create"}}).Do()
	require.NoError(t, err)
	assert.Contains(t, perms.Permissions, "billing.resourceAssociations.create")

	op, err := rm.Projects.Create(&crm.Project{
		ProjectId: projectID,
		Name:      "Go Compat Project",
		Labels:    map[string]string{"env": "compat"},
		Parent:    &crm.ResourceId{Type: "folder", Id: "123456"},
	}).Do()
	require.NoError(t, err)
	assert.True(t, op.Done)
	assert.Contains(t, string(op.Response), `"@type":"type.googleapis.com/google.cloudresourcemanager.v1.Project"`)

	polled, err := rm.Operations.Get(op.Name).Do()
	require.NoError(t, err)
	assert.True(t, polled.Done)

	project, err := rm.Projects.Get(projectID).Do()
	require.NoError(t, err)
	assert.Equal(t, "ACTIVE", project.LifecycleState)
	assert.Equal(t, "Go Compat Project", project.Name)
	assert.Equal(t, "compat", project.Labels["env"])
	require.NotNil(t, project.Parent)
	assert.Equal(t, "folder", project.Parent.Type)

	byNumber, err := rm.Projects.Get(strconv.FormatInt(project.ProjectNumber, 10)).Do()
	require.NoError(t, err)
	assert.Equal(t, projectID, byNumber.ProjectId)

	_, err = rm.Projects.Create(&crm.Project{ProjectId: projectID, Name: "Duplicate"}).Do()
	var apiErr *googleapi.Error
	require.True(t, errors.As(err, &apiErr))
	assert.Equal(t, 409, apiErr.Code)

	project.Name = "Go Compat Renamed"
	updated, err := rm.Projects.Update(projectID, project).Do()
	require.NoError(t, err)
	assert.Equal(t, "Go Compat Renamed", updated.Name)

	listed, err := rm.Projects.List().Filter("id:" + projectID).Do()
	require.NoError(t, err)
	require.Len(t, listed.Projects, 1)
	assert.Equal(t, projectID, listed.Projects[0].ProjectId)

	ancestry, err := rm.Projects.GetAncestry(projectID, &crm.GetAncestryRequest{}).Do()
	require.NoError(t, err)
	require.Len(t, ancestry.Ancestor, 2)
	assert.Equal(t, "project", ancestry.Ancestor[0].ResourceId.Type)
	assert.Equal(t, "folder", ancestry.Ancestor[1].ResourceId.Type)

	name := "projects/" + projectID
	info, err := billing.Projects.UpdateBillingInfo(name, &cloudbilling.ProjectBillingInfo{
		BillingAccountName: "billingAccounts/000000-AAAAAA-BBBBBB",
	}).Do()
	require.NoError(t, err)
	assert.True(t, info.BillingEnabled)

	info, err = billing.Projects.GetBillingInfo(name).Do()
	require.NoError(t, err)
	assert.Equal(t, "billingAccounts/000000-AAAAAA-BBBBBB", info.BillingAccountName)
	assert.Equal(t, projectID, info.ProjectId)

	info, err = billing.Projects.UpdateBillingInfo(name, &cloudbilling.ProjectBillingInfo{}).Do()
	require.NoError(t, err)
	assert.False(t, info.BillingEnabled)

	_, err = rm.Projects.Delete(projectID).Do()
	require.NoError(t, err)
	deleted, err := rm.Projects.Get(projectID).Do()
	require.NoError(t, err)
	assert.Equal(t, "DELETE_REQUESTED", deleted.LifecycleState)

	_, err = rm.Projects.Undelete(projectID, &crm.UndeleteProjectRequest{}).Do()
	require.NoError(t, err)
	restored, err := rm.Projects.Get(projectID).Do()
	require.NoError(t, err)
	assert.Equal(t, "ACTIVE", restored.LifecycleState)

	_, err = rm.Projects.Delete(projectID).Do()
	require.NoError(t, err)
}
