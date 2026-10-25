package tests

import (
	"context"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	container "google.golang.org/api/container/v1beta1"
	"google.golang.org/api/option"
)

// Exercises the container v1beta1 client that the google-beta Terraform provider (and Pulumi gcp,
// bridged from it) uses for every GKE call.
func TestContainerV1beta1ClusterLifecycle(t *testing.T) {
	ctx := context.Background()
	svc, err := container.NewService(ctx,
		option.WithEndpoint(iamEndpoint()+"/container/"), option.WithoutAuthentication())
	require.NoError(t, err)

	parent := "projects/" + iamProject() + "/locations/us-central1"
	name := uniqueName("go-beta")
	clusterName := parent + "/clusters/" + name

	op, err := svc.Projects.Locations.Clusters.Create(parent, &container.CreateClusterRequest{
		Cluster: &container.Cluster{
			Name:               name,
			InitialNodeCount:   1,
			WorkloadAltsConfig: &container.WorkloadALTSConfig{EnableAlts: true},
		},
	}).Do()
	require.NoError(t, err)
	assert.Equal(t, "CREATE_CLUSTER", op.OperationType)

	polled, err := svc.Projects.Locations.Operations.Get(parent + "/operations/" + op.Name).Do()
	require.NoError(t, err)
	assert.Equal(t, op.Name, polled.Name)

	cluster, err := svc.Projects.Locations.Clusters.Get(clusterName).Do()
	require.NoError(t, err)
	assert.Equal(t, name, cluster.Name)

	list, err := svc.Projects.Locations.Clusters.List(parent).Do()
	require.NoError(t, err)
	var names []string
	for _, c := range list.Clusters {
		names = append(names, c.Name)
	}
	assert.Contains(t, names, name)

	_, err = svc.Projects.Locations.Clusters.NodePools.Create(clusterName, &container.CreateNodePoolRequest{
		NodePool: &container.NodePool{Name: "beta-pool", InitialNodeCount: 1},
	}).Do()
	require.NoError(t, err)

	pools, err := svc.Projects.Locations.Clusters.NodePools.List(clusterName).Do()
	require.NoError(t, err)
	var poolNames []string
	for _, p := range pools.NodePools {
		poolNames = append(poolNames, p.Name)
	}
	assert.Contains(t, poolNames, "beta-pool")

	_, err = svc.Projects.Locations.Clusters.Delete(clusterName).Do()
	require.NoError(t, err)
}
