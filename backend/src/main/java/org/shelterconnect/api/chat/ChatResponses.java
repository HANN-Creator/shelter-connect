package org.shelterconnect.api.chat;

import java.time.Instant;
import java.util.UUID;

public final class ChatResponses {
	private ChatResponses() {}
	public record Session(UUID id, UUID dogId, String status, boolean canSend, Instant createdAt, Instant updatedAt) {}
	public record Message(UUID id, UUID sessionId, UUID dogId, String role, String text, String clientMessageId,
			UUID replyToMessageId, String processingStatus, String failureCode, boolean needsShelterConfirmation,
			Instant createdAt, Instant updatedAt) {}
	public record Stored<T>(T value, boolean created) {}
}
