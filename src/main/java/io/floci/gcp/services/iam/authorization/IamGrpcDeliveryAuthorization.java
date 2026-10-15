package io.floci.gcp.services.iam.authorization;

import io.grpc.Context;

/** Carries the original caller's authorization into listener-driven gRPC deliveries. */
public final class IamGrpcDeliveryAuthorization {
    private static final Context.Key<Delivery> DELIVERY = Context.key("iam-grpc-delivery");

    private IamGrpcDeliveryAuthorization() {
    }

    @FunctionalInterface
    public interface Delivery {
        void run(String resource, Runnable delivery);
    }

    public static Context withDelivery(Context context, Delivery delivery) {
        return context.withValue(DELIVERY, delivery);
    }

    public static Delivery current() {
        return DELIVERY.get();
    }
}
