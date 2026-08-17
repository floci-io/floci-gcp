package io.floci.gcp.services.credentials;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
public class StoredCredentialToken {

	public enum TokenKind {
		IMPERSONATED,
		DOWNSCOPED
	}

	private String tokenValue;
	private TokenKind tokenKind;
	private Instant expireTime;
	private String sourceToken;
	private String principal;
	private List<CredentialAccessBoundaryRule> gcsRules = new ArrayList<>();

	public StoredCredentialToken() {
	}

	public StoredCredentialToken(String tokenValue, TokenKind tokenKind, Instant expireTime,
			String sourceToken, String principal, List<CredentialAccessBoundaryRule> gcsRules) {
		this.tokenValue = tokenValue;
		this.tokenKind = tokenKind;
		this.expireTime = expireTime;
		this.sourceToken = sourceToken;
		this.principal = principal;
		this.gcsRules = new ArrayList<>(gcsRules);
	}

	public String getTokenValue() {
		return tokenValue;
	}

	public void setTokenValue(String tokenValue) {
		this.tokenValue = tokenValue;
	}

	public TokenKind getTokenKind() {
		return tokenKind;
	}

	public void setTokenKind(TokenKind tokenKind) {
		this.tokenKind = tokenKind;
	}

	public Instant getExpireTime() {
		return expireTime;
	}

	public void setExpireTime(Instant expireTime) {
		this.expireTime = expireTime;
	}

	public String getSourceToken() {
		return sourceToken;
	}

	public void setSourceToken(String sourceToken) {
		this.sourceToken = sourceToken;
	}

	public String getPrincipal() {
		return principal;
	}

	public void setPrincipal(String principal) {
		this.principal = principal;
	}

	public List<CredentialAccessBoundaryRule> getGcsRules() {
		return gcsRules;
	}

	public void setGcsRules(List<CredentialAccessBoundaryRule> gcsRules) {
		this.gcsRules = gcsRules == null ? new ArrayList<>() : new ArrayList<>(gcsRules);
	}
}
