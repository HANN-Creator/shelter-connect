package org.shelterconnect.api.asset;

import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StyledSeedTailEvidenceTest {
    final JsonMapper json=JsonMapper.builder().build();
    static JsonNode observation(JsonMapper json,String photo,String west,String east,double confidence){
        return json.valueToTree(Map.of("photoTail",photo,"photoEvidence","Synthetic photo visibility observation","photoConfidence",.95,
            "views",List.of(Map.of("direction","west","tail",west,"visibleEvidence","Synthetic west tail contour","confidence",confidence,"tailPixelPath",path()),
                Map.of("direction","east","tail",east,"visibleEvidence","Synthetic east tail contour","confidence",confidence,"tailPixelPath",path()))));
    }
    static List<Map<String,Integer>> path(){return List.of(Map.of("x",24,"y",16),Map.of("x",25,"y",16),Map.of("x",26,"y",16),Map.of("x",27,"y",16));}
    static byte[] tailSeed(){var im=new java.awt.image.BufferedImage(32,32,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for(int x=8;x<24;x++)for(int y=12;y<26;y++)im.setRGB(x,y,0xff998855);for(int x=24;x<28;x++)im.setRGB(x,16,0xff443322);return StyledSpriteCodec.png(im);}
    @Test void inventedDiscontinuousOrSingleBumpPathsAreNotPixelEvidence(){
        assertThat(StyledSeedTailEvidence.pixelPath(json,tailSeed(),json.valueToTree(path())).path("completeContour").asBoolean()).isTrue();
        assertThat(StyledSeedTailEvidence.pixelPath(json,tailSeed(),json.valueToTree(List.of(Map.of("x",24,"y",16),Map.of("x",27,"y",16)))).path("completeContour").asBoolean()).isTrue();
        for(var p:List.of(List.of(Map.of("x",24,"y",16)),List.of(Map.of("x",24,"y",16),Map.of("x",31,"y",16)),
            List.of(Map.of("x",24,"y",15),Map.of("x",25,"y",15),Map.of("x",26,"y",15),Map.of("x",27,"y",15))))
            assertThat(StyledSeedTailEvidence.pixelPath(json,tailSeed(),json.valueToTree(p)).path("completeContour").asBoolean()).isFalse();
    }
    @Test void unknownPhotoDoesNotPermitStubsAndMissingContoursButCompleteCurlsRemainValid(){
        for(String photo:List.of("OBSCURED","UNCERTAIN","VISIBLE_EXTENDED")){
            var bad=StyledSeedTailEvidence.assess(json,observation(json,photo,"SHORT_STUB","NOT_DISCERNIBLE",.95));
            assertThat(bad.path("passed").asBoolean()).isFalse();assertThat(bad.path("failedDirections").toString()).isEqualTo("[\"west\",\"east\"]");
            assertThat(StyledSeedTailEvidence.assess(json,observation(json,photo,"COMPLETE_CONNECTED","COMPLETE_CONNECTED",.95)).path("passed").asBoolean()).isTrue();
        }
        var supported=observation(json,"VISIBLE_NATURALLY_SHORT","SHORT_STUB","SHORT_STUB",.95);
        assertThat(StyledSeedTailEvidence.assess(json,supported).path("passed").asBoolean()).isTrue();
        ((ObjectNode)supported).put("photoConfidence",.7);
        assertThat(StyledSeedTailEvidence.assess(json,supported).path("passed").asBoolean()).isFalse();
        assertThat(StyledSeedTailEvidence.assess(json,observation(json,"VISIBLE_NATURALLY_SHORT","NOT_DISCERNIBLE","DETACHED_OR_CROPPED",.95)).path("passed").asBoolean()).isFalse();
    }
    @Test void uncertaintyAndMalformedDirectionListsNeverBecomeApproval(){
        assertThat(StyledSeedTailEvidence.assess(json,observation(json,"OBSCURED","COMPLETE_CONNECTED","COMPLETE_CONNECTED",.6)).path("passed").asBoolean()).isFalse();
        var duplicate=observation(json,"OBSCURED","COMPLETE_CONNECTED","COMPLETE_CONNECTED",.95);
        ((ObjectNode)duplicate.at("/views/1")).put("direction","west");
        assertThatThrownBy(()->StyledSeedTailEvidence.assess(json,duplicate)).hasMessage("QUALITY_RECOVERY_RESPONSE_INVALID");
        ((ObjectNode)duplicate.at("/views/1")).put("direction","south");
        assertThatThrownBy(()->StyledSeedTailEvidence.assess(json,duplicate)).hasMessage("QUALITY_RECOVERY_RESPONSE_INVALID");
    }
    @Test void actualFalsePassCannotOverrideTailObservationAndOnlyFailedSidesBecomeRepairTargets()throws Exception{
        var old=(ObjectNode)json.readTree(Files.readAllBytes(Path.of("scripts/fixtures/tail-evidence-v17/deployed-seed-review.json")));
        assertThat(old.path("passed").asBoolean()).isTrue();var original=old.path("propertyReview").deepCopy();
        var evidence=StyledSeedTailEvidence.assess(json,observation(json,"OBSCURED","SHORT_STUB","NOT_DISCERNIBLE",.95));
        StyledSeedTailEvidence.merge(json,old,evidence);
        assertThat(old.path("passed").asBoolean()).isFalse();assertThat(old.path("generalPropertyReview")).isEqualTo(original);
        assertThat(StyledSeedRepair.targets(old)).containsExactly("west","east");
        assertThat(old.at("/propertyReview/views/0")).isEqualTo(original.path("views").get(0));
        assertThat(old.at("/propertyReview/views/1")).isEqualTo(original.path("views").get(1));
        assertThat(old.path("repairDescription").asText()).contains("west,east","complete connected tail");
        // Same exact failed bytes + uncertain observation must hold, never buy an arbitrary redraw.
        var seeds=new ArrayList<byte[]>();for(String d:StyledSpriteCodec.DIRECTIONS)seeds.add(Files.readAllBytes(Path.of("scripts/fixtures/tail-evidence-v17/"+d+".png")));
        old.put("rulesSha256",StyledSpriteCodec.qualityRulesSha());
        ((ObjectNode)old.at("/propertyReview/views/2")).put("confidence",.6);
        var client=mock(OpenAiResponsesClient.class);
        assertThat(StyledSeedRepair.plan(client,json,seeds,old).path("status").asText()).isEqualTo("UNCERTAIN_VERDICT");verifyNoInteractions(client);
    }
    @Test void passingEvidenceMustMatchSourcePhotoAllFourSpritesModelAndCurrentRules()throws Exception{
        var ai=new AiProperties(true,"fixture-key","gpt-5.6-luna",30);var client=mock(OpenAiResponsesClient.class);
        when(client.structuredImages(anyString(),anyString(),anyMap(),anyMap())).thenReturn(observation(json,"OBSCURED","COMPLETE_CONNECTED","COMPLETE_CONNECTED",.95));
        byte[] seed=tailSeed();var seeds=Collections.nCopies(4,seed);
        var evidence=StyledSeedTailEvidence.review(client,ai,json,seed,seeds);
        var report=json.createObjectNode();for(String field:List.of("inputSha256","photoSha256","model","rulesSha256"))report.set(field,evidence.path(field));report.set("tailEvidence",evidence);
        assertThat(StyledSeedTailEvidence.boundPass(report)).isTrue();
        for(String field:List.of("inputSha256","photoSha256","model","rulesSha256")){
            var changed=report.deepCopy();changed.put(field,"changed");assertThat(StyledSeedTailEvidence.boundPass(changed)).isFalse();
        }
        ((ObjectNode)evidence.at("/observation/views/1")).put("tail","NOT_DISCERNIBLE");
        assertThat(StyledSeedTailEvidence.boundPass(report)).isFalse();
    }
    @Test void exactLiveNegativeAndPositiveReplayTheirNativePixelEvidence()throws Exception{
        for(String name:List.of("deployed","selected")){
            var root=Path.of("scripts/fixtures/tail-evidence-v17");
            var raw=json.readTree(Files.readAllBytes(root.resolve("live-"+name+"-review.json")));
            var images=new ArrayList<byte[]>();
            var source=name.equals("deployed")?root:Path.of("scripts/fixtures/selected-seed-repair-v16/selected");
            for(String d:StyledSpriteCodec.DIRECTIONS)images.add(Files.readAllBytes(source.resolve(d+".png")));
            assertThat(StyledSeedQualityAgent.binding(images)).isEqualTo(raw.path("inputSha256").asText());
            assertThat(raw.path("rulesSha256").asText()).isEqualTo("84a6c987de69c9e911997aac3b91df0262566396ab4e42279101af2e82768082");
            var result=StyledSeedTailEvidence.grounded(json,raw.path("observation"),images);
            assertThat(result.path("passed").asBoolean()).as(name).isEqualTo(name.equals("selected"));
            assertThat(result.path("failedDirections")).isEqualTo(json.valueToTree(name.equals("selected")?List.of():List.of("west","east")));
        }
    }
    @Test void policyCannotApproveLegacyOrUnboundTailEvidence()throws Exception{
        var policy=json.createObjectNode().put("seedQualityVersion",StyledSeedQualityAgent.VERSION).put("seedTailEvidenceVersion",StyledSeedTailEvidence.VERSION);
        var report=json.createObjectNode().put("passed",true);report.putArray("issues");
        assertThat(StyledSeedQualityAgent.passed(report,json.createObjectNode(),policy)).isFalse();
    }
}
