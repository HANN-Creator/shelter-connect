package org.shelterconnect.api.chat;

final class ChatException extends RuntimeException {
	final int status;
	final String code;
	final Integer retryAfterSeconds;
	ChatException(int status, String code, String message) { this(status,code,message,null); }
	private ChatException(int status, String code, String message, Integer retryAfterSeconds) { super(message); this.status=status; this.code=code; this.retryAfterSeconds=retryAfterSeconds; }
	static ChatException limited(String code, String message, int retryAfterSeconds) { return new ChatException(429,code,message,retryAfterSeconds); }
	static ChatException invalid() { return new ChatException(400,"INVALID_REQUEST","요청 항목과 값을 확인해 주세요."); }
	static ChatException missing() { return new ChatException(404,"CHAT_NOT_FOUND","대화방을 찾을 수 없어요."); }
	static ChatException conflict(String code, String message) { return new ChatException(409,code,message); }
}
