package org.shelterconnect.api.behavior;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public interface BehaviorSuggestionProvider {
    record Observation(UUID id,String category,String content,Instant observedAt,Instant updatedAt) {}
    JsonNode suggest(List<Observation> observations);
}
