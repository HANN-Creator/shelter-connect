package org.shelterconnect.api.asset;

import tools.jackson.databind.JsonNode;

public interface PhotoAppearanceProvider {
    JsonNode analyze(byte[] normalizedPhoto);
    default JsonNode analyzeStyled(byte[] normalizedPhoto) { return analyze(normalizedPhoto); }
    String model();
}
