package io.floci.gcp.services.sts;

class StsException extends RuntimeException {

	private final String error;

	StsException(String error, String message) {
		super(message);
		this.error = error;
	}

	String error() {
		return error;
	}
}
