package org.shelterconnect.api.asset;

import java.util.*;
import tools.jackson.databind.JsonNode;

final class AssetInput {
    static void fields(JsonNode body,String... allowed) {
        if(body==null || !body.isObject()) throw AssetException.invalid();
        Set<String> names=Set.of(allowed);
        if(body.properties().stream().anyMatch(e->!names.contains(e.getKey()))) throw AssetException.invalid();
    }
    static UUID id(String value) {
        try { var id=UUID.fromString(value);if(!id.toString().equalsIgnoreCase(value)) throw AssetException.invalid();return id; }
        catch(Exception e) { throw AssetException.invalid(); }
    }
    static UUID id(JsonNode body,String key) { return id(text(body,key,36)); }
    static String text(JsonNode body,String key,int max) {
        var value=body.path(key);
        if(!value.isTextual() || value.asText().isBlank() || value.asText().length()>max) throw AssetException.invalid();
        return value.asText().strip();
    }
    static boolean bool(JsonNode body,String key) {
        if(!body.path(key).isBoolean()) throw AssetException.invalid();return body.get(key).asBoolean();
    }
}
