package tests

import (
	"context"
	"testing"

	"floci-gcp-sdk-test-go/internal/testutil"

	"cloud.google.com/go/firestore"
	"cloud.google.com/go/firestore/apiv1/firestorepb"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"google.golang.org/api/iterator"
)

func TestFirestore(t *testing.T) {
	ctx := context.Background()
	client := testutil.FirestoreClient(ctx)
	defer client.Close()

	colName := uniqueName("go-col")
	col := client.Collection(colName)

	t.Cleanup(func() {
		docs, _ := col.Documents(ctx).GetAll()
		for _, d := range docs {
			d.Ref.Delete(ctx)
		}
	})

	t.Run("SetAndGetDocument", func(t *testing.T) {
		docRef := col.Doc("alice")
		_, err := docRef.Set(ctx, map[string]interface{}{
			"name":   "Alice",
			"age":    30,
			"active": true,
		})
		require.NoError(t, err)

		snap, err := docRef.Get(ctx)
		require.NoError(t, err)
		assert.True(t, snap.Exists())

		name, err := snap.DataAt("name")
		require.NoError(t, err)
		assert.Equal(t, "Alice", name)

		age, err := snap.DataAt("age")
		require.NoError(t, err)
		assert.Equal(t, int64(30), age)

		_, err = docRef.Delete(ctx)
		require.NoError(t, err)
	})

	t.Run("QueryDocuments", func(t *testing.T) {
		col.Doc("user1").Set(ctx, map[string]interface{}{"name": "Alice", "score": 10})
		col.Doc("user2").Set(ctx, map[string]interface{}{"name": "Bob", "score": 20})
		col.Doc("user3").Set(ctx, map[string]interface{}{"name": "Charlie", "score": 10})

		t.Cleanup(func() {
			col.Doc("user1").Delete(ctx)
			col.Doc("user2").Delete(ctx)
			col.Doc("user3").Delete(ctx)
		})

		it := col.Where("score", "==", 10).Documents(ctx)
		var names []string
		for {
			snap, err := it.Next()
			if err == iterator.Done {
				break
			}
			require.NoError(t, err)
			name, _ := snap.DataAt("name")
			names = append(names, name.(string))
		}

		assert.Contains(t, names, "Alice")
		assert.Contains(t, names, "Charlie")
		assert.NotContains(t, names, "Bob")
	})

	t.Run("UpdateDocument", func(t *testing.T) {
		docRef := col.Doc("update-test")
		_, err := docRef.Set(ctx, map[string]interface{}{"name": "Alice", "age": 30})
		require.NoError(t, err)

		t.Cleanup(func() { docRef.Delete(ctx) })

		_, err = docRef.Update(ctx, []firestore.Update{
			{Path: "age", Value: 31},
		})
		require.NoError(t, err)

		snap, err := docRef.Get(ctx)
		require.NoError(t, err)
		age, _ := snap.DataAt("age")
		assert.Equal(t, int64(31), age)
		name, _ := snap.DataAt("name")
		assert.Equal(t, "Alice", name)
	})

	t.Run("DeleteDocument", func(t *testing.T) {
		docRef := col.Doc("to-delete")
		_, err := docRef.Set(ctx, map[string]interface{}{"name": "Delete Me"})
		require.NoError(t, err)

		_, err = docRef.Delete(ctx)
		require.NoError(t, err)

		snap, err := docRef.Get(ctx)
		assert.False(t, snap.Exists())
		_ = err
	})

	t.Run("BatchWrite", func(t *testing.T) {
		ref1 := col.Doc("batch1")
		ref2 := col.Doc("batch2")

		t.Cleanup(func() {
			ref1.Delete(ctx)
			ref2.Delete(ctx)
		})

		batch := client.Batch()
		batch.Set(ref1, map[string]interface{}{"name": "Batch1"})
		batch.Set(ref2, map[string]interface{}{"name": "Batch2"})
		_, err := batch.Commit(ctx)
		require.NoError(t, err)

		snap1, err := ref1.Get(ctx)
		require.NoError(t, err)
		n1, _ := snap1.DataAt("name")
		assert.Equal(t, "Batch1", n1)

		snap2, err := ref2.Get(ctx)
		require.NoError(t, err)
		n2, _ := snap2.DataAt("name")
		assert.Equal(t, "Batch2", n2)
	})
}

func TestFirestoreAggregation(t *testing.T) {
	ctx := context.Background()
	client := testutil.FirestoreClient(ctx)
	// Registered first so it runs last: cleanups below still need the client.
	t.Cleanup(func() { client.Close() })

	col := client.Collection(uniqueName("go-agg"))
	_, err := col.Doc("a").Set(ctx, map[string]interface{}{"price": 10})
	require.NoError(t, err)
	_, err = col.Doc("b").Set(ctx, map[string]interface{}{"price": 20})
	require.NoError(t, err)

	t.Cleanup(func() {
		docs, _ := col.Documents(ctx).GetAll()
		for _, d := range docs {
			d.Ref.Delete(ctx)
		}
	})

	t.Run("CountSumAndAvg", func(t *testing.T) {
		res, err := col.NewAggregationQuery().
			WithCount("n").
			WithSum("price", "total").
			WithAvg("price", "mean").
			Get(ctx)
		require.NoError(t, err)

		assert.Equal(t, int64(2), aggregateValue(t, res, "n").GetIntegerValue())
		total := aggregateValue(t, res, "total")
		assert.IsType(t, &firestorepb.Value_IntegerValue{}, total.GetValueType())
		assert.Equal(t, int64(30), total.GetIntegerValue())
		mean := aggregateValue(t, res, "mean")
		assert.IsType(t, &firestorepb.Value_DoubleValue{}, mean.GetValueType())
		assert.Equal(t, 15.0, mean.GetDoubleValue())
	})

	t.Run("UnnamedAggregationsGetDefaultAliases", func(t *testing.T) {
		res, err := col.NewAggregationQuery().
			WithSum("price", "").
			WithAvg("price", "").
			Get(ctx)
		require.NoError(t, err)

		assert.Equal(t, int64(30), aggregateValue(t, res, "field_1").GetIntegerValue())
		assert.Equal(t, 15.0, aggregateValue(t, res, "field_2").GetDoubleValue())
	})

	t.Run("SumIsADoubleWhenAnyValueIsADouble", func(t *testing.T) {
		mixed := client.Collection(uniqueName("go-agg-mixed"))
		_, err := mixed.Doc("a").Set(ctx, map[string]interface{}{"price": 10})
		require.NoError(t, err)
		_, err = mixed.Doc("b").Set(ctx, map[string]interface{}{"price": 2.5})
		require.NoError(t, err)
		t.Cleanup(func() {
			mixed.Doc("a").Delete(ctx)
			mixed.Doc("b").Delete(ctx)
		})

		res, err := mixed.NewAggregationQuery().WithSum("price", "total").Get(ctx)
		require.NoError(t, err)

		total := aggregateValue(t, res, "total")
		assert.IsType(t, &firestorepb.Value_DoubleValue{}, total.GetValueType())
		assert.Equal(t, 12.5, total.GetDoubleValue())
	})

	t.Run("SumOfNothingIsZeroAndAvgOfNothingIsNull", func(t *testing.T) {
		res, err := client.Collection(uniqueName("go-agg-empty")).NewAggregationQuery().
			WithSum("price", "total").
			WithAvg("price", "mean").
			Get(ctx)
		require.NoError(t, err)

		total := aggregateValue(t, res, "total")
		assert.IsType(t, &firestorepb.Value_IntegerValue{}, total.GetValueType())
		assert.Equal(t, int64(0), total.GetIntegerValue())
		assert.IsType(t, &firestorepb.Value_NullValue{}, aggregateValue(t, res, "mean").GetValueType())
	})
}

func aggregateValue(t *testing.T, res firestore.AggregationResult, alias string) *firestorepb.Value {
	t.Helper()
	v, ok := res[alias]
	require.True(t, ok, "no aggregation result named %q", alias)
	value, ok := v.(*firestorepb.Value)
	require.True(t, ok, "aggregation result %q is %T, not *firestorepb.Value", alias, v)
	return value
}
