package io.floci.gcp.services.credentials;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
public class CredentialAccessBoundaryRule {

	private String bucket;
	private String objectPrefix;
	private List<String> availablePermissions = new ArrayList<>();

	public CredentialAccessBoundaryRule() {
	}

	public CredentialAccessBoundaryRule(String bucket, String objectPrefix, List<String> availablePermissions) {
		this.bucket = bucket;
		this.objectPrefix = objectPrefix;
		this.availablePermissions = new ArrayList<>(availablePermissions);
	}

	public String getBucket() {
		return bucket;
	}

	public void setBucket(String bucket) {
		this.bucket = bucket;
	}

	public String getObjectPrefix() {
		return objectPrefix;
	}

	public void setObjectPrefix(String objectPrefix) {
		this.objectPrefix = objectPrefix;
	}

	public List<String> getAvailablePermissions() {
		return availablePermissions;
	}

	public void setAvailablePermissions(List<String> availablePermissions) {
		this.availablePermissions = availablePermissions == null
				? new ArrayList<>()
				: new ArrayList<>(availablePermissions);
	}
}
