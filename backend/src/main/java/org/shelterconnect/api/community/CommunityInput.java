package org.shelterconnect.api.community;

import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import org.shelterconnect.api.web.*;
import static org.shelterconnect.api.web.FeatureInput.*;
import static org.shelterconnect.api.community.CommunityTypes.*;

public final class CommunityInput {
    private CommunityInput() {}
    public static String choice(String value,String... allowed) {
        if(value==null || !Set.of(allowed).contains(value)) throw FeatureException.invalid(); return value;
    }
    public static long version(JsonNode body) { return positive(body.path("version")); }
    public static long positive(JsonNode value) {
        if(!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong()<1) throw FeatureException.invalid(); return value.asLong();
    }
    public static String optionalText(JsonNode body,String key,int max) {
        if(!body.has(key) || body.path(key).isNull()) return "";
        if(body.path(key).isString() && body.path(key).asString().isBlank()) return "";
        return text(body,key,max);
    }
    public static Location location(JsonNode body,boolean required) {
        if(body==null || body.isNull() || body.isMissingNode()) { if(required) throw FeatureException.invalid(); return null; }
        fields(body,"label","latitude","longitude","occurredAt");
        String label=text(body,"label",200);
        Double lat=number(body,"latitude",90),lon=number(body,"longitude",180);
        if((lat==null)!=(lon==null)) throw FeatureException.invalid();
        Instant at=body.path("occurredAt").isNull() || !body.has("occurredAt")?null:instant(text(body,"occurredAt",40));
        if(required && at==null || at!=null && at.isAfter(Instant.now().plusSeconds(300))) throw FeatureException.invalid();
        return new Location(label,lat,lon,at);
    }
    private static Double number(JsonNode body,String key,int bound) {
        var value=body.path(key); if(value.isMissingNode() || value.isNull()) return null;
        if(!value.isNumber()) throw FeatureException.invalid(); return coordinate(value.asString(),bound);
    }
    public static List<UUID> media(JsonNode body,int max) {
        var array=body.path("mediaIds"); if(array.isMissingNode()) return List.of();
        if(!array.isArray() || array.size()>max) throw FeatureException.invalid();
        List<UUID> ids=new ArrayList<>();
        for(var value:array) { if(!value.isString()) throw FeatureException.invalid(); ids.add(id(value.asString())); }
        if(new HashSet<>(ids).size()!=ids.size()) throw FeatureException.invalid();return List.copyOf(ids);
    }
    public static Content content(JsonNode body,String category,boolean published) {
        String title=optionalText(body,"title",50),text=optionalText(body,"text",1000),region=optionalText(body,"regionLabel",100);
        if(published && (title.isEmpty() || text.isEmpty() || region.isEmpty())) throw FeatureException.invalid();
        List<String> features=new ArrayList<>();var tags=body.path("features");
        if(!tags.isMissingNode()) {
            if(!tags.isArray() || tags.size()>8) throw FeatureException.invalid();
            for(var tag:tags) {
                if(!tag.isString()) throw FeatureException.invalid(); String value=tag.asString().strip();
                if(value.isEmpty() || value.codePointCount(0,value.length())>20 || value.chars().anyMatch(Character::isISOControl)) throw FeatureException.invalid();
                features.add(value);
            }
            if(new HashSet<>(features).size()!=features.size()) throw FeatureException.invalid();
        }
        return new Content(title,text,region,location(body.path("location"),published && !category.equals("NEIGHBOR_NEWS")),List.copyOf(features),media(body,5));
    }
    public static void publishable(Content content,String category) {
        if(content.title().isEmpty() || content.text().isEmpty() || content.regionLabel().isEmpty()
            || !category.equals("NEIGHBOR_NEWS") && (content.location()==null || content.location().occurredAt()==null)) throw FeatureException.invalid();
    }
}
