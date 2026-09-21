package org.shelterconnect.api.adoption;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class AdoptionNoteResponses {
	private AdoptionNoteResponses() {}
	public record Note(UUID id, UUID dogId, String questions, String carePlan,
			Map<String, Boolean> checklist, Instant createdAt, Instant updatedAt) {}
	public record Stored(Note value, boolean created) {}
}
