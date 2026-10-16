package org.shelterconnect.api.asset;

import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Converts bounded, photo-grounded observations to the established native32 input contract. */
public final class StyledPhotoInput {
    public static final String VERSION="styled-photo-analysis-v1";
    private StyledPhotoInput() {}
    static JsonNode pending(JsonMapper json) { return json.valueToTree(Map.of("automaticPhotoInput",VERSION)); }
    static boolean pending(JsonNode input) { return input!=null && VERSION.equals(input.path("automaticPhotoInput").asText()); }
    public static JsonNode traits(byte[] photo,JsonNode analysis,JsonMapper json) {
        // Reject multiple animals, hidden heads, tiny boxes and invalid observations before generation.
        // Vision uses a bounded photographic reference. Validate against that same
        // scale, while binding the resulting traits to the immutable stored original.
        PhotoAppearance.prepare(SpriteNormalizer.reference(photo),analysis);
        var features=analysis.path("features");
        for(String key:PhotoAppearance.FEATURES)if(features.path(key).asText().length()>80)
            throw new AssetException(422,"PHOTO_APPEARANCE_REQUIRES_REVIEW");
        var identity=new StringJoiner(". ");
        for(String key:PhotoAppearance.FEATURES)identity.add(key+": "+features.path(key).asText().strip());
        var box=analysis.path("headBox");double x=box.path("x").asDouble(),y=box.path("y").asDouble(),w=box.path("width").asDouble(),h=box.path("height").asDouble();
        String sha=StyledSpriteCodec.sha(photo);
        var result=json.valueToTree(Map.of(
            "sourcePhotoSha256",sha,
            "faceBox",List.of(Math.max(0,x-.12*w),Math.max(0,y-.12*h),Math.min(1,x+1.12*w),Math.min(1,y+1.12*h)),
            "identityDescription",identity.toString(),
            "motionDescription","Four-legged dog. "+features.path("build").asText()+". Coat: "+features.path("coat").asText()+". Tail: "+features.path("tail").asText(),
            "rearDescription","Same dog from behind. Coat: "+features.path("coat").asText()+". Tail: "+features.path("tail").asText()+". Face turned away; hidden rear anatomy is inferred, not observed.",
            "seed",Integer.parseInt(sha.substring(0,7),16),
            "reviewNote","Automatic "+VERSION+"; visible appearance only. Hidden features remain uncertain; no temperament or breed inference."));
        return StyledSpriteCodec.validateTraits(result);
    }
}
