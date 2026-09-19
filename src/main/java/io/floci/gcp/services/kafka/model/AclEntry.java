package io.floci.gcp.services.kafka.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class AclEntry {

    @JsonProperty("principal")
    private String principal;

    @JsonProperty("permissionType")
    @JsonAlias("permission_type")
    private String permissionType;

    @JsonProperty("operation")
    private String operation;

    @JsonProperty("host")
    private String host;

    public AclEntry() {
    }

    public AclEntry(String principal, String permissionType, String operation, String host) {
        this.principal = principal;
        this.permissionType = permissionType;
        this.operation = operation;
        this.host = host != null ? host : "*";
    }

    public String getPrincipal() {
        return principal;
    }

    public void setPrincipal(String principal) {
        this.principal = principal;
    }

    public String getPermissionType() {
        return permissionType;
    }

    public void setPermissionType(String permissionType) {
        this.permissionType = permissionType;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AclEntry aclEntry = (AclEntry) o;
        return Objects.equals(principal, aclEntry.principal) &&
               Objects.equals(permissionType, aclEntry.permissionType) &&
               Objects.equals(operation, aclEntry.operation) &&
               Objects.equals(host, aclEntry.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(principal, permissionType, operation, host);
    }
}
