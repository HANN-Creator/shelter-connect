package org.shelterconnect.api.chat;

/** A public, fixed code only. Provider error bodies and credentials are never retained. */
public final class AiFailure extends RuntimeException {
	private final String code;
	public AiFailure(String code) { super(code);this.code=code; }
	public String code() { return code; }
}
