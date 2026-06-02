package tests

import (
	"context"
	"sync"
	"testing"
	"time"

	"floci-gcp-sdk-test-go/internal/testutil"

	"cloud.google.com/go/pubsub"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

func TestPubSub(t *testing.T) {
	ctx := context.Background()
	client := testutil.PubSubClient(ctx)
	defer client.Close()

	topicID := uniqueName("go-topic")
	subID := uniqueName("go-sub")

	t.Cleanup(func() {
		client.Subscription(subID).Delete(ctx)
		client.Topic(topicID).Delete(ctx)
	})

	var topic *pubsub.Topic

	t.Run("CreateTopic", func(t *testing.T) {
		var err error
		topic, err = client.CreateTopic(ctx, topicID)
		require.NoError(t, err)
		assert.Equal(t, topicID, topic.ID())
	})

	t.Run("ListTopics", func(t *testing.T) {
		it := client.Topics(ctx)
		found := false
		for {
			tp, err := it.Next()
			if err != nil {
				break
			}
			if tp.ID() == topicID {
				found = true
				break
			}
		}
		assert.True(t, found, "created topic should appear in list")
	})

	var sub *pubsub.Subscription

	t.Run("CreateSubscription", func(t *testing.T) {
		var err error
		sub, err = client.CreateSubscription(ctx, subID, pubsub.SubscriptionConfig{
			Topic:       topic,
			AckDeadline: 10 * time.Second,
		})
		require.NoError(t, err)
		assert.Equal(t, subID, sub.ID())
	})

	t.Run("ListSubscriptions", func(t *testing.T) {
		it := client.Subscriptions(ctx)
		found := false
		for {
			s, err := it.Next()
			if err != nil {
				break
			}
			if s.ID() == subID {
				found = true
				break
			}
		}
		assert.True(t, found, "created subscription should appear in list")
	})

	t.Run("PublishAndReceive", func(t *testing.T) {
		messages := []string{"Hello from Go!", "Second Go message"}

		for _, msg := range messages {
			res := topic.Publish(ctx, &pubsub.Message{Data: []byte(msg)})
			_, err := res.Get(ctx)
			require.NoError(t, err)
		}

		// Wait briefly for messages to propagate
		time.Sleep(200 * time.Millisecond)

		received := make([]string, 0, len(messages))
		var mu sync.Mutex

		cctx, cancel := context.WithTimeout(ctx, 15*time.Second)
		defer cancel()

		var wg sync.WaitGroup
		wg.Add(1)
		go func() {
			defer wg.Done()
			sub.Receive(cctx, func(ctx context.Context, m *pubsub.Message) {
				mu.Lock()
				received = append(received, string(m.Data))
				count := len(received)
				mu.Unlock()
				m.Ack()
				if count >= len(messages) {
					cancel()
				}
			})
		}()

		<-cctx.Done()
		wg.Wait()

		mu.Lock()
		defer mu.Unlock()
		assert.GreaterOrEqual(t, len(received), len(messages))
		assert.Contains(t, received, "Hello from Go!")
		assert.Contains(t, received, "Second Go message")
	})

	t.Run("DeleteSubscription", func(t *testing.T) {
		// The streaming Receive above is cancelled just before this call; its
		// teardown can race the delete at the gRPC layer and surface NotFound for
		// an already-deleted subscription. Treat that as success, then verify the
		// subscription is actually gone so a genuinely broken delete still fails.
		err := client.Subscription(subID).Delete(ctx)
		if status.Code(err) == codes.NotFound {
			err = nil
		}
		require.NoError(t, err)

		exists, err := client.Subscription(subID).Exists(ctx)
		require.NoError(t, err)
		assert.False(t, exists, "subscription should be deleted")
	})

	t.Run("DeleteTopic", func(t *testing.T) {
		err := client.Topic(topicID).Delete(ctx)
		require.NoError(t, err)
	})
}
