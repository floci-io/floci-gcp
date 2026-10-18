package io.floci.gcp.services.bigquery;

import io.floci.gcp.core.common.ServiceUsageAdapter;

import java.util.Set;

/**
 * A read session or a write stream acts on a BigQuery table, which the Storage API names with the
 * {@code bigquery.googleapis.com/Table} type ({@code read_session.table}, {@code parent}).
 */
final class BigQueryStorageServiceUsageAdapter implements ServiceUsageAdapter {

    @Override
    public Set<String> referenceDomains() {
        return Set.of("bigquery.googleapis.com");
    }
}
