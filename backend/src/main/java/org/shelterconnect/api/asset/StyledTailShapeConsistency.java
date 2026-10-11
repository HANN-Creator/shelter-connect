package org.shelterconnect.api.asset;

import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Resolve a length-label contradiction only when the actual side silhouettes are identical. */
final class StyledTailShapeConsistency {
    static final String VERSION="exact-tail-shape-consistency-v1";
    private static final List<String> SIDES=List.of("west","east");
    private StyledTailShapeConsistency(){}

    static ObjectNode measure(JsonMapper json,List<byte[]> seeds) {
        var proof=json.createObjectNode().put("version",VERSION).put("inputSha256",StyledSeedQualityAgent.binding(seeds));
        var hashes=proof.putObject("sourceHashes");
        for(int i=0;i<4;i++)hashes.put(StyledSpriteCodec.DIRECTIONS.get(i),StyledSpriteCodec.sha(seeds.get(i)));
        var masks=proof.putObject("canonicalAlphaSha256");var counts=proof.putObject("visiblePixels");
        for(String d:SIDES) {
            var image=StyledSpriteCodec.nativeFrame(seeds.get(StyledSpriteCodec.DIRECTIONS.indexOf(d)));var alpha=new byte[1024];int count=0;
            for(int y=0;y<32;y++)for(int x=0;x<32;x++) {
                int a=image.getRGB(x,y)>>>24;alpha[y*32+(d.equals("west")?31-x:x)]=(byte)a;if(a!=0)count++;
            }
            masks.put(d,StyledSpriteCodec.sha(alpha));counts.put(d,count);
        }
        proof.put("exactMirroredAlpha",masks.path("west").equals(masks.path("east")));return proof;
    }

    static boolean valid(JsonNode proof) {
        if(!VERSION.equals(proof.path("version").asText()) || !proof.path("exactMirroredAlpha").asBoolean()
            || proof.path("sourceHashes").size()!=4 || !StyledSpriteCodec.DIRECTIONS.stream().allMatch(d->sha(proof.at("/sourceHashes/"+d)))
            || !proof.path("inputSha256").asText().equals(StyledSeedQualityAgent.hashBinding(proof.path("sourceHashes"))))return false;
        var masks=proof.path("canonicalAlphaSha256");var counts=proof.path("visiblePixels");
        return sha(masks.path("west")) && masks.path("west").equals(masks.path("east"))
            && counts.path("west").isIntegralNumber() && counts.path("west").asInt()>0 && counts.path("west").asInt()<=1024
            && counts.path("west").equals(counts.path("east"));
    }

    static void reconcile(JsonMapper json,ObjectNode assessed,List<String> directions) {
        if(directions.size()!=2 || !new HashSet<>(directions).equals(new HashSet<>(SIDES)))return;
        var geometry=assessed.path("geometry");var proof=geometry.path("shapeConsistency");if(!valid(proof))return;
        var views=new HashMap<String,JsonNode>();assessed.at("/observation/views").forEach(v->views.put(v.path("direction").asText(),v));
        String anchor=null,target=null;
        for(String d:SIDES) {
            var v=views.get(d);var g=geometry.path(d);var verdict=assessed.at("/verdicts/"+d);
            if(v==null || !v.path("attachment").asText().equals("CONNECTED") || !v.path("contour").asText().equals("DISTINCT")
                || !v.path("tip").asText().equals("VISIBLE") || !StyledTailGeometry.VERSION.equals(g.path("branchVersion").asText())
                || !g.path("edgeContact").isBoolean() || g.path("edgeContact").asBoolean()
                || g.path("opaqueComponents").asInt()!=1 || !g.path("rearBranchSupport").asBoolean())return;
            if(v.path("tail").asText().equals("COMPLETE_CONNECTED") && verdict.path("decision").asText().equals("PASS"))anchor=d;
            else if(v.path("tail").asText().equals("SHORT_STUB") && verdict.path("decision").asText().equals("UNCERTAIN"))target=d;
            else return; // Missing, cropped, detached, ambiguous, both-stub or both-complete is not this contradiction.
        }
        if(anchor==null || target==null)return;
        var audit=assessed.putObject("shapeConsistency").put("version",VERSION).put("applied",true)
            .put("anchorDirection",anchor).put("resolvedDirection",target).put("inputSha256",proof.path("inputSha256").asText())
            .put("basis","EXACT_MIRRORED_ALPHA_WITH_COMPLETE_CONNECTED_OBSERVATION");
        audit.set("originalVerdicts",assessed.path("verdicts").deepCopy());
        ((ObjectNode)assessed.at("/verdicts/"+target)).put("decision","PASS").put("issue","");
        assessed.putArray("uncertainDirections");assessed.putArray("failedDirections");assessed.put("passed",true).put("decision","PASS");
        // Observations, photographs, source pixels and their distinct hashes remain untouched.
        // The independent general reviewer still reconciles its own contradictions afterwards.
    }
    private static boolean sha(JsonNode n){return n.isTextual() && n.asText().matches("[a-f0-9]{64}");}
}
