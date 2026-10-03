package org.shelterconnect.api.asset;

import java.util.Map;
import tools.jackson.databind.JsonNode;

public interface MotionHarness {
    record Clip(byte[] sheet, int frameCount, int durationMs, Map<String,Object> validation) {}
    record MapClip(byte[] sheet, int frameCount, Map<String,Object> validation) {}
    JsonNode propose(byte[] base);
    Clip render(AssetAction action, byte[] base, JsonNode profile);
    MapClip mapPixels(byte[] base, byte[] source, int frameCount);
    static boolean handles(AssetAction action) {
        return action==AssetAction.WALK || action==AssetAction.RUN || action==AssetAction.BACK_OFF;
    }
}
