package org.shelterconnect.api.asset;

import tools.jackson.databind.JsonNode;

public interface PhotoAppearanceProvider {
    JsonNode analyze(byte[] normalizedPhoto);
    String model();
}
