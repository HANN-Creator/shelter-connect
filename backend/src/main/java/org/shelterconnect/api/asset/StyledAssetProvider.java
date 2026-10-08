package org.shelterconnect.api.asset;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
public interface StyledAssetProvider {
    UUID submit(boolean character,JsonNode payload);
    UUID editAnimation(JsonNode payload);
    UUID editSeedEyes(JsonNode payload);
    JsonNode pollSeedEyes(UUID id);
    /** Completed result contains bounded base64 PNGs; no provider URLs leave this adapter. */
    JsonNode poll(UUID id,boolean character);
}
