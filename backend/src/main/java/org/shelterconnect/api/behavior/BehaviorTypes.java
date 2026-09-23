package org.shelterconnect.api.behavior;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class BehaviorTypes {
	private BehaviorTypes() {}
	public enum Action { IDLE, WALK, RUN, SNIFF, TAIL_WAG, BACK_OFF, SIT, LIE_DOWN }
	public record Motion(int weight, BigDecimal speedTilesPerSecond, int minDurationMs, int maxDurationMs, int cooldownMs) {}
	public record BallPlay(boolean chaseEnabled, boolean returnEnabled, int reactionDelayMs) {}
	public record Settings(Map<Action, Motion> actions, BigDecimal approachDistanceTiles,
			BigDecimal personalSpaceTiles, int reactionDelayMs, BallPlay ballPlay) {}
	@io.swagger.v3.oas.annotations.media.Schema(name="BehaviorProfile")
	public record Profile(UUID dogId, int schemaVersion, int revision, JsonNode settings, String source, String status,
			List<UUID> evidenceObservationIds, UUID confirmedBy, Instant confirmedAt, Instant updatedAt) {}
	public record Playback(UUID dogId, int schemaVersion, String basis, Integer revision, Settings settings) {
		@com.fasterxml.jackson.annotation.JsonProperty
		public Map<String,BehaviorInteractions.BallChase> interactions() {
			return Map.of("BALL_CHASE",BehaviorInteractions.ballChase(settings));
		}
	}
	record Save(int expectedRevision, Settings settings, String source, List<UUID> evidence) {}
}
