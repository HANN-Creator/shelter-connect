package org.shelterconnect.api.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.shelterconnect.api.chat.ChatResponses.Message;

public final class AiTypes {
	private AiTypes() {}
	public record Observation(UUID id,String category,String content,Instant observedAt,Instant updatedAt) {}
	public record Context(String dogName,String question,List<String> recentUserMessages,List<Observation> observations) {}
	public record Generated(String text,boolean needsShelterConfirmation,List<UUID> observationIds,String responseId) {}
	public record Reply(UUID requestMessageId,String processingStatus,String failureCode,boolean retryable,Message reply) {}
	public record Outcome(Reply data,int httpStatus) {}
	record Work(UUID subject,UUID userId,UUID sessionId,UUID dogId,UUID requestId,UUID token,Context context) {}
	record Started(Work work,Outcome existing) {}
	record Request(Message message,UUID token,Instant expiresAt,int attempts,boolean active) {}
	public static Generated unknown() {
		return new Generated("아직 그 부분은 내 기록에 없어서 확실히 말하기 어려워. 보호소에 함께 확인해 줄래?",true,List.of(),null);
	}
}
