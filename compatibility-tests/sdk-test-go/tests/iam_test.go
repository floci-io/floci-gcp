package tests

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"testing"

	"floci-gcp-sdk-test-go/internal/testutil"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func iamEndpoint() string {
	if e := os.Getenv("FLOCI_GCP_ENDPOINT"); e != "" {
		return e
	}
	return "http://localhost:4588"
}

func iamProject() string {
	if p := os.Getenv("FLOCI_GCP_PROJECT"); p != "" {
		return p
	}
	return "test-project"
}

func iamSABase(project string) string {
	return fmt.Sprintf("%s/v1/projects/%s/serviceAccounts", iamEndpoint(), project)
}

func iamAuthHeader(req *http.Request) {
	req.Header.Set("Authorization", "Bearer "+testutil.AccessToken())
}

func iamPost(t *testing.T, url string, body interface{}) map[string]interface{} {
	t.Helper()
	data, err := json.Marshal(body)
	require.NoError(t, err)
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(data)) //nolint:noctx
	require.NoError(t, err)
	req.Header.Set("Content-Type", "application/json")
	iamAuthHeader(req)
	resp, err := http.DefaultClient.Do(req)
	require.NoError(t, err)
	defer resp.Body.Close()
	require.Equal(t, 200, resp.StatusCode)
	raw, err := io.ReadAll(resp.Body)
	require.NoError(t, err)
	var result map[string]interface{}
	require.NoError(t, json.Unmarshal(raw, &result))
	return result
}

func iamGet(t *testing.T, url string) map[string]interface{} {
	t.Helper()
	req, err := http.NewRequest(http.MethodGet, url, nil) //nolint:noctx
	require.NoError(t, err)
	iamAuthHeader(req)
	resp, err := http.DefaultClient.Do(req)
	require.NoError(t, err)
	defer resp.Body.Close()
	require.Equal(t, 200, resp.StatusCode)
	raw, err := io.ReadAll(resp.Body)
	require.NoError(t, err)
	var result map[string]interface{}
	require.NoError(t, json.Unmarshal(raw, &result))
	return result
}

func iamDelete(url string) {
	req, _ := http.NewRequest(http.MethodDelete, url, nil) //nolint:noctx
	iamAuthHeader(req)
	resp, err := http.DefaultClient.Do(req)
	if err == nil {
		resp.Body.Close()
	}
}


func TestIAM(t *testing.T) {
	project := iamProject()
	accountID := uniqueName("go-sa")
	email := fmt.Sprintf("%s@%s.iam.gserviceaccount.com", accountID, project)
	base := iamSABase(project)

	t.Cleanup(func() {
		iamDelete(base + "/" + email)
	})

	t.Run("CreateServiceAccount", func(t *testing.T) {
		resp := iamPost(t, base, map[string]interface{}{
			"accountId":      accountID,
			"serviceAccount": map[string]interface{}{"displayName": "Go Test SA"},
		})
		assert.Equal(t, email, resp["email"])
		assert.Equal(t, project, resp["projectId"])
		name, _ := resp["name"].(string)
		assert.Contains(t, name, accountID)
	})

	t.Run("GetServiceAccount", func(t *testing.T) {
		resp := iamGet(t, base+"/"+email)
		assert.Equal(t, email, resp["email"])
		assert.Equal(t, "Go Test SA", resp["displayName"])
	})

	t.Run("ListServiceAccounts", func(t *testing.T) {
		resp := iamGet(t, base)
		accounts, _ := resp["accounts"].([]interface{})
		found := false
		for _, a := range accounts {
			if m, ok := a.(map[string]interface{}); ok {
				if m["email"] == email {
					found = true
					break
				}
			}
		}
		assert.True(t, found, "created service account should appear in list")
	})

	t.Run("GetIamPolicy", func(t *testing.T) {
		resp := iamPost(t, base+"/"+email+":getIamPolicy", map[string]interface{}{})
		assert.Equal(t, float64(1), resp["version"])
		bindings, _ := resp["bindings"].([]interface{})
		assert.Empty(t, bindings)
	})

	t.Run("SetIamPolicy", func(t *testing.T) {
		resp := iamPost(t, base+"/"+email+":setIamPolicy", map[string]interface{}{
			"policy": map[string]interface{}{
				"version": 1,
				"bindings": []interface{}{
					map[string]interface{}{
						"role":    "roles/iam.serviceAccountUser",
						"members": []interface{}{"user:alice@example.com"},
					},
				},
			},
		})
		bindings, _ := resp["bindings"].([]interface{})
		require.Len(t, bindings, 1)
		b, _ := bindings[0].(map[string]interface{})
		assert.Equal(t, "roles/iam.serviceAccountUser", b["role"])
	})

	t.Run("TestIamPermissions", func(t *testing.T) {
		permissions := []interface{}{"iam.serviceAccounts.get", "iam.serviceAccounts.list"}
		resp := iamPost(t, base+"/"+email+":testIamPermissions", map[string]interface{}{
			"permissions": permissions,
		})
		granted, _ := resp["permissions"].([]interface{})
		assert.Len(t, granted, 2)
	})

	t.Run("DeleteServiceAccount", func(t *testing.T) {
		iamDelete(base + "/" + email)

		resp := iamGet(t, base)
		accounts, _ := resp["accounts"].([]interface{})
		for _, a := range accounts {
			if m, ok := a.(map[string]interface{}); ok {
				assert.NotEqual(t, email, m["email"])
			}
		}
	})
}
