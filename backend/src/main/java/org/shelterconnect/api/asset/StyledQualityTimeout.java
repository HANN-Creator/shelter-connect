package org.shelterconnect.api.asset;

import tools.jackson.databind.JsonNode;

/** Transport retries do not grant image edits or alter a quality verdict. */
final class StyledQualityTimeout {
    static final String VERSION="saved-quality-timeout-v1";
    static final int LIMIT=2;
    static boolean enabled(JsonNode policy) { return policy!=null && VERSION.equals(policy.path("qualityTimeoutVersion").asText()); }
    static int delay(int used) { return used==0?15:60; }
    static boolean started(JsonNode checkpoint) {
        return "STARTED".equals(checkpoint.at("/quality/status").asText()) && checkpoint.hasNonNull("payload");
    }
}
