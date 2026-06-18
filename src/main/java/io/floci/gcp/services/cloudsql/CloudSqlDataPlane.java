package io.floci.gcp.services.cloudsql;

import java.util.Map;

interface CloudSqlDataPlane {

    Map<String, Object> startInstance(String project, String instance, Map<String, Object> metadata);

    Map<String, Object> ensureInstance(String project, String instance, Map<String, Object> metadata);

    void stopInstance(String project, String instance, Map<String, Object> metadata, boolean removeStorage);

    void createDatabase(Map<String, Object> instanceMetadata, String database);

    void deleteDatabase(Map<String, Object> instanceMetadata, String database);

    void createOrUpdateUser(Map<String, Object> instanceMetadata, String user, String password);

    void deleteUser(Map<String, Object> instanceMetadata, String user, Iterable<String> databases);

    void grantDatabaseAccess(Map<String, Object> instanceMetadata, String database, String user);

    static CloudSqlDataPlane noop() {
        return new CloudSqlDataPlane() {
            @Override
            public Map<String, Object> startInstance(String project, String instance, Map<String, Object> metadata) {
                return metadata;
            }

            @Override
            public Map<String, Object> ensureInstance(String project, String instance, Map<String, Object> metadata) {
                return metadata;
            }

            @Override
            public void stopInstance(String project, String instance, Map<String, Object> metadata,
                                     boolean removeStorage) {
            }

            @Override
            public void createDatabase(Map<String, Object> instanceMetadata, String database) {
            }

            @Override
            public void deleteDatabase(Map<String, Object> instanceMetadata, String database) {
            }

            @Override
            public void createOrUpdateUser(Map<String, Object> instanceMetadata, String user, String password) {
            }

            @Override
            public void deleteUser(Map<String, Object> instanceMetadata, String user, Iterable<String> databases) {
            }

            @Override
            public void grantDatabaseAccess(Map<String, Object> instanceMetadata, String database, String user) {
            }
        };
    }
}
