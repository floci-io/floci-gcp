package io.floci.gcp.services.gcs;

final class GcsObjectNames {

    private GcsObjectNames() {
    }

    static String fromPathParam(String value) {
        // JAX-RS path params are already decoded once; GCS object names must not be decoded again.
        return value;
    }
}
