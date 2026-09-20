package org.shelterconnect.api.management;

final class ManagementException extends RuntimeException {
	final int status;
	final String code;
	ManagementException(int status, String code, String message) { super(message); this.status = status; this.code = code; }
	static ManagementException invalid(String message) { return new ManagementException(400, "INVALID_REQUEST", message); }
	static ManagementException missing() { return new ManagementException(404, "RESOURCE_NOT_FOUND", "요청한 정보를 찾을 수 없어요."); }
	static ManagementException conflict(String code, String message) { return new ManagementException(409, code, message); }
}
