package org.shelterconnect.api.auth;

public final class AccountAccessException extends RuntimeException {
	private final int status;
	private final String code;

	AccountAccessException(int status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public int status() { return status; }
	public String code() { return code; }
}
