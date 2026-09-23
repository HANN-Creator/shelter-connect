package org.shelterconnect.api.asset;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import tools.jackson.databind.JsonNode;

/** Coordinates are proposals from vision, validated and padded before any paid sprite request. */
public final class PhotoAppearance {
    public static final String VERSION="photo-appearance-v1";
    static final List<String> FEATURES=List.of("coat","markings","ears","eyes","muzzle","nose","tail","build");
    public record Input(byte[] body,byte[] head,String description) {}
    private PhotoAppearance() {}
    public static Input prepare(byte[] photo,JsonNode analysis) {
        if(analysis==null || !analysis.isObject() || analysis.size()!=4
            || !analysis.path("dogCount").isIntegralNumber() || analysis.path("dogCount").asInt()!=1
            || !analysis.path("headVisible").isBoolean() || !analysis.path("headVisible").asBoolean()) throw bad();
        JsonNode features=analysis.path("features"),box=analysis.path("headBox");
        if(!features.isObject() || features.size()!=FEATURES.size() || !box.isObject() || box.size()!=4) throw bad();
        StringJoiner description=new StringJoiner(". ");
        for(String name:FEATURES) {
            var value=features.path(name);
            if(!value.isString() || value.asText().isBlank() || value.asText().length()>120
                || value.asText().chars().anyMatch(Character::isISOControl)) throw bad();
            description.add(name+": "+value.asText().strip());
        }
        double x=coordinate(box,"x"),y=coordinate(box,"y"),w=coordinate(box,"width"),h=coordinate(box,"height");
        if(w<.08 || h<.08 || x+w>1.000001 || y+h>1.000001 || w*h>.85) throw bad();
        try {
            SpriteNormalizer.dimensions(photo,1024);
            BufferedImage image=ImageIO.read(new ByteArrayInputStream(photo));
            if(w*image.getWidth()<24 || h*image.getHeight()<24) throw bad();
            // Keep ears and nose even when the approximate box is slightly tight. Never stretch pixels.
            int left=Math.max(0,(int)Math.floor((x-w*.12)*image.getWidth()));
            int top=Math.max(0,(int)Math.floor((y-h*.12)*image.getHeight()));
            int right=Math.min(image.getWidth(),(int)Math.ceil((x+w*1.12)*image.getWidth()));
            int bottom=Math.min(image.getHeight(),(int)Math.ceil((y+h*1.12)*image.getHeight()));
            if(right<=left || bottom<=top) throw bad();
            byte[] head=SpriteNormalizer.png(image.getSubimage(left,top,right-left,bottom-top));
            return new Input(photo,head,description.toString());
        } catch(AssetException e) { throw e; }
        catch(Exception e) { throw bad(); }
    }
    private static double coordinate(JsonNode box,String key) {
        var value=box.path(key);double number=value.asDouble(-1);
        if(!value.isNumber() || !Double.isFinite(number) || number<0 || number>1) throw bad();
        return number;
    }
    private static AssetException bad() { return new AssetException(422,"PHOTO_APPEARANCE_REQUIRES_REVIEW"); }
}
