package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StyledTailObservationConsistencyTest {
    static final Path ROOT=Path.of("scripts/fixtures/tail-observation-v42");
    final JsonMapper json=JsonMapper.builder().build();
    List<byte[]> seeds(String name)throws Exception {
        var out=new ArrayList<byte[]>();for(String d:StyledSpriteCodec.DIRECTIONS)out.add(Files.readAllBytes(ROOT.resolve(name).resolve(d+".png")));return out;
    }
    JsonNode recorded(String name)throws Exception{return json.readTree(Files.readAllBytes(ROOT.resolve(name).resolve("deployed-review.json")));}
    ObjectNode raw()throws Exception{return (ObjectNode)recorded("black").at("/tailEvidence/observation").deepCopy();}
    ObjectNode assess(JsonNode raw,List<byte[]> pixels){return StyledTailAnatomy.assess(json,raw,StyledTailGeometry.measure(json,pixels),List.of("west","east"));}
    ObjectNode view(JsonNode observation,String direction){return (ObjectNode)observation.path("views").valueStream().filter(v->v.path("direction").asText().equals(direction)).findFirst().orElseThrow();}
    @Test void actualIdenticalSideSilhouettesResolveOnlyTheContradictoryLengthLabel()throws Exception {
        var pixels=seeds("black");var before=pixels.stream().map(StyledSpriteCodec::sha).toList();
        var report=recorded("black");assertThat(report.path("passed").asBoolean()).isFalse();
        for(var history:report.at("/tailEvidence/observationHistory")) {
            var observation=history.path("observation");String raw=json.writeValueAsString(observation);
            var result=StyledTailAnatomy.assess(json,observation,StyledTailGeometry.measure(json,pixels),List.of("west","east"));
            assertThat(result.path("passed").asBoolean()).isTrue();
            assertThat(result.path("decision").asText()).isEqualTo("PASS");
            assertThat(result.at("/shapeConsistency/resolvedDirection").asText()).isEqualTo("east");
            assertThat(result.at("/shapeConsistency/originalVerdicts/east/decision").asText()).isEqualTo("UNCERTAIN");
            assertThat(json.writeValueAsString(observation)).isEqualTo(raw);
        }
        assertThat(pixels.stream().map(StyledSpriteCodec::sha).toList()).isEqualTo(before);
    }
    @Test void archivedEvidenceIsHashBoundAndResolutionIsDirectionSymmetric()throws Exception {
        var manifest=json.readTree(Files.readAllBytes(ROOT.resolve("evidence.json"))).path("sha256");
        for(String name:manifest.propertyNames())assertThat(StyledSpriteCodec.sha(Files.readAllBytes(ROOT.resolve(name)))).as(name).isEqualTo(manifest.path(name).asText());
        var observation=raw();view(observation,"west").put("tail","SHORT_STUB");view(observation,"east").put("tail","COMPLETE_CONNECTED");
        var result=assess(observation,seeds("black"));assertThat(result.path("passed").asBoolean()).isTrue();
        assertThat(result.at("/shapeConsistency/resolvedDirection").asText()).isEqualTo("west");
    }
    @Test void missingAmbiguousDetachedAndBothShortTailsCannotInheritTheOtherViewsPass()throws Exception {
        for(String tail:List.of("NOT_DISCERNIBLE","UNCERTAIN","DETACHED_OR_CROPPED")) {
            var observation=raw();view(observation,"east").put("tail",tail);
            assertThat(assess(observation,seeds("black")).path("passed").asBoolean()).as(tail).isFalse();
        }
        for(var item:Map.of("attachment","DETACHED","contour","ABSENT","tip","CROPPED").entrySet()) {
            var observation=raw();view(observation,"east").put(item.getKey(),item.getValue());
            assertThat(assess(observation,seeds("black")).path("passed").asBoolean()).as(item.getKey()).isFalse();
        }
        var both=raw();view(both,"west").put("tail","SHORT_STUB");assertThat(assess(both,seeds("black")).path("passed").asBoolean()).isFalse();
    }
    @Test void evenOneDifferentAlphaPixelDisablesCrossViewResolution()throws Exception {
        var pixels=seeds("black");var image=StyledSpriteCodec.nativeFrame(pixels.get(3));
        outer:for(int y=0;y<32;y++)for(int x=0;x<32;x++)if((image.getRGB(x,y)>>>24)>0){image.setRGB(x,y,0);break outer;}
        pixels.set(3,StyledSpriteCodec.png(image));var r=assess(raw(),pixels);
        assertThat(r.at("/geometry/shapeConsistency/exactMirroredAlpha").asBoolean()).isFalse();
        assertThat(r.path("passed").asBoolean()).isFalse();assertThat(r.has("shapeConsistency")).isFalse();
    }
    @Test void identicalMasksNeverWaiveClippedDisconnectedOrEmptyFrames()throws Exception {
        for(String condition:List.of("edgeContact","opaqueComponents","rearBranchSupport")) {
            var g=StyledTailGeometry.measure(json,seeds("black"));
            for(String d:List.of("west","east")) {var side=(ObjectNode)g.path(d);
                if(condition.equals("edgeContact"))side.put(condition,true);
                else if(condition.equals("opaqueComponents"))side.put(condition,2);
                else side.put(condition,false);
            }
            assertThat(StyledTailAnatomy.assess(json,raw(),g,List.of("west","east")).path("passed").asBoolean()).as(condition).isFalse();
        }
        var empty=seeds("black");var blank=StyledSpriteCodec.png(new java.awt.image.BufferedImage(32,32,java.awt.image.BufferedImage.TYPE_INT_ARGB));
        empty.set(2,blank);empty.set(3,blank);assertThatThrownBy(()->assess(raw(),empty)).isInstanceOf(AssetException.class);
    }
    ObjectNode review(boolean generalConflict)throws Exception {
        var pixels=seeds("black");var client=mock(OpenAiResponsesClient.class);var observation=raw();
        var second=observation.deepCopy();((tools.jackson.databind.node.ArrayNode)second.path("views")).remove(0);
        when(client.structuredImagesWithReasoning(anyString(),anyString(),anyMap(),anyMap(),eq("medium"))).thenReturn(observation,second);
        var report=(ObjectNode)recorded("black").deepCopy();var general=(ObjectNode)report.path("generalPropertyReview").deepCopy();
        // Recreate the pre-tail merge state of a fresh review, not an approval of the historical receipt.
        report.put("passed",true);report.putArray("issues");
        if(generalConflict){view(general,"east").putArray("issues").add("TAIL_MISSING");view(general,"east").put("tailPlausible",false);}
        var ai=new AiProperties(true,"unit-test-only","gpt-5.6-luna",60);
        var evidence=StyledTailAnatomy.review(client,ai,json,pixels.get(0),pixels,general);
        for(String field:List.of("inputSha256","photoSha256","rulesSha256","model"))report.set(field,evidence.path(field));
        report.set("propertyReview",general);StyledTailAnatomy.merge(json,report,evidence);
        verify(client,times(generalConflict?2:1)).structuredImagesWithReasoning(anyString(),anyString(),anyMap(),anyMap(),eq("medium"));
        return report;
    }
    @Test void productionReviewUsesOneObservationAndCannotLearnThisAsADefect()throws Exception {
        var report=review(false);assertThat(report.path("passed").asBoolean()).isTrue();assertThat(StyledTailAnatomy.boundPass(report)).isTrue();
        assertThat(report.path("issues").isEmpty()).isTrue();assertThat(report.at("/tailEvidence/observationHistory").size()).isEqualTo(1);
        var client=mock(OpenAiResponsesClient.class);
        assertThatThrownBy(()->StyledSeedRepair.plan(client,json,seeds("black"),report)).hasMessage("SEED_REPAIR_SELECTION_INVALID");
        verifyNoInteractions(client);
    }
    @Test void independentGeneralConflictAndTamperedBindingsCannotAuthorizeAPass()throws Exception {
        var conflict=review(true);assertThat(StyledTailAnatomy.unresolved(conflict)).isTrue();assertThat(StyledTailAnatomy.boundPass(conflict)).isFalse();
        assertThat(conflict.path("issues").isEmpty()).isTrue();
        var valid=review(false);
        var contradictoryGeneral=valid.deepCopy();view(contradictoryGeneral.path("generalPropertyReview"),"east").putArray("issues").add("TAIL_MISSING");
        assertThat(StyledTailAnatomy.boundPass(contradictoryGeneral)).isFalse();
        for(String path:List.of("audit","source","mask","geometryBinding","reportBinding")) {
            var r=valid.deepCopy();var e=(ObjectNode)r.path("tailEvidence");var proof=(ObjectNode)e.at("/geometry/shapeConsistency");
            switch(path) {
                case "audit" -> e.remove("shapeConsistency");
                case "source" -> ((ObjectNode)proof.path("sourceHashes")).put("west","0".repeat(64));
                case "mask" -> ((ObjectNode)proof.path("canonicalAlphaSha256")).put("west","0".repeat(64));
                case "geometryBinding" -> proof.put("inputSha256","0".repeat(64));
                case "reportBinding" -> {r.put("inputSha256","0".repeat(64));e.put("inputSha256","0".repeat(64));}
            }
            assertThat(StyledTailAnatomy.boundPass(r)).as(path).isFalse();
        }
    }
    @Test void actualChangedCreamRearTailRemainsBlockedWithOriginalNineFrames()throws Exception {
        var original=recorded("cream");var pixels=seeds("cream").stream().map(StyledSpriteCodec::paddedSeed).toList();
        var frames=StyledSpriteCodec.frames(Files.readAllBytes(ROOT.resolve("cream/idle-north.png")));
        var hashes=frames.stream().map(StyledSpriteCodec::sha).toList();var client=mock(OpenAiResponsesClient.class);
        when(client.structuredImagesWithReasoning(anyString(),anyString(),anyMap(),anyMap(),eq("medium")))
            .thenReturn(original.path("initialVision"),original.path("consistencyReview"));
        var r=new StyledQualityAgent(client,new AiProperties(true,"unit-test-only","gpt-5.6-luna",60),json)
            .review(json.readTree("{\"tailCarriage\":\"UNKNOWN\"}"),pixels,frames,"IDLE","north");
        assertThat(r.path("passed").asBoolean()).isFalse();assertThat(r.path("issues").toString()).contains("DISCONTINUITY","TAIL_CARRIAGE");
        assertThat(r.path("referencePoseUsable").asBoolean()).isFalse();assertThat(frames.stream().map(StyledSpriteCodec::sha).toList()).isEqualTo(hashes);
    }
}
