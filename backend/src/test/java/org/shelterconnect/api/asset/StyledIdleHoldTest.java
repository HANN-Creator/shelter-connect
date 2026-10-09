package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.shelterconnect.api.chat.*;

class StyledIdleHoldTest {
    final JsonMapper json=JsonMapper.builder().build();
    final Path root=Path.of("scripts/fixtures/idle-hold-v26");
    List<byte[]> seeds()throws Exception{var s=new ArrayList<byte[]>();for(String d:StyledSpriteCodec.DIRECTIONS)s.add(Files.readAllBytes(root.resolve("seeds/"+d+".png")));return s;}
    JsonNode old()throws Exception{return json.readTree(Files.readAllBytes(root.resolve("idle-south-review.json")));}
    @Test void actualFrontalAppendageConflictIsAConfirmedDefectNotAnApproval()throws Exception {
        var prior=old();var frames=StyledSpriteCodec.frames(Files.readAllBytes(root.resolve("idle-south.png")));var sources=seeds().stream().map(StyledSpriteCodec::paddedSeed).toList();
        var growth=StyledQualityAgent.frontalHeadGrowth(sources.getFirst(),frames,StyledSpriteCodec.qualityRules(json).at("/recovery/frontalHeadGrowth"));
        assertThat(growth).isNotEmpty();assertThat(prior.path("motionDecision").asText()).isEqualTo("UNCERTAIN");
        var ai=new AiProperties(true,"test-key","gpt-5.6-luna",60);var client=mock(OpenAiResponsesClient.class);
        when(client.structuredImagesWithReasoning(anyString(),anyString(),anyMap(),anyMap(),eq("medium")))
            .thenReturn(prior.path("initialVision"),prior.path("consistencyReview"));
        var r=StyledMotionReview.review(client,ai,json,json.readTree("{\"tailCarriage\":\"UNKNOWN\"}"),sources,frames,"IDLE","south",json.createArrayNode());
        assertThat(r.path("motionDecision").asText()).isEqualTo("CONFIRMED_DEFECT");assertThat(r.path("passed").asBoolean()).isFalse();
        assertThat(r.path("frontalAppendageConfirmed").asBoolean()).isTrue();assertThat(r.path("issues").toString()).contains("TAIL_CARRIAGE","IDLE_MOTION");
        assertThat(r.path("initialVision")).isEqualTo(prior.path("initialVision"));assertThat(r.path("consistencyReview")).isEqualTo(prior.path("consistencyReview"));
        verify(client,times(2)).structuredImagesWithReasoning(anyString(),anyString(),anyMap(),anyMap(),eq("medium"));
    }
    @Test void headResolutionNeverWaivesOtherUncertaintiesNormalEarsOrSideRearTail()throws Exception {
        var p=old();var a=p.path("initialVision");var b=p.path("consistencyReview");var lower=p.path("lowerBodyEvidence");
        for(String d:List.of("north","west","east"))assertThat(StyledMotionReview.resolveFrontalAppendage("IDLE",d,a,b,lower,List.of(8))).isFalse();
        assertThat(StyledMotionReview.resolveFrontalAppendage("WALK","south",a,b,lower,List.of(8))).isFalse();
        assertThat(StyledMotionReview.resolveFrontalAppendage("IDLE","south",a,b,lower,List.of())).isFalse();
        for(String k:StyledMotionReview.PROPERTIES.keySet()) {
            var changed=b.deepCopy();((tools.jackson.databind.node.ObjectNode)StyledMotionReview.property(changed,k)).put("state",k.equals("tail")?"PASS":"UNCERTAIN");
            assertThat(StyledMotionReview.resolveFrontalAppendage("IDLE","south",a,changed,lower,List.of(8))).as(k).isFalse();
        }
        var still=(tools.jackson.databind.node.ObjectNode)b.deepCopy();still.put("observedMotion","BREATH_BLINK");
        assertThat(StyledMotionReview.resolveFrontalAppendage("IDLE","south",a,still,lower,List.of(8))).isFalse();
    }
    StyledAssetStore.Work work(String action,int count,String status,JsonNode policy,JsonNode result) {
        return new StyledAssetStore.Work(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"idle-south",action,"south",status,null,null,"dog-photos","photo.png",json.createObjectNode(),null,result,null,count,policy);
    }
    @Test void staticRestIsExplicitLosslessNineFramesAndCannotRepeatOrReplaceWalk()throws Exception {
        var source=StyledSpriteCodec.paddedSeed(seeds().getFirst());var hashes=json.createObjectNode();for(int i=0;i<4;i++)hashes.put(StyledSpriteCodec.DIRECTIONS.get(i),StyledSpriteCodec.sha(seeds().get(i)));
        var previous=json.createObjectNode().put("sha256",StyledSpriteCodec.sha(Files.readAllBytes(root.resolve("idle-south.png"))));
        var derived=StyledIdleHold.result(source,hashes,"south",previous,json);
        var frames=derived.path("frames").valueStream().map(n->Base64.getDecoder().decode(n.asText())).toList();assertThat(frames).hasSize(9);
        for(var f:frames)assertThat(f).isEqualTo(source);
        assertThat(derived.at("/derivation/sourceMotionSha256")).isEqualTo(previous.path("sha256"));
        assertThat(derived.at("/derivation/seedHashes")).isEqualTo(hashes);
        var policy=json.createObjectNode().put("recoveryVersion",StyledRecovery.VERSION).put("idleHoldVersion",StyledIdleHold.VERSION)
            .put("rulesSha256",StyledSpriteCodec.qualityRulesSha()).put("maxRepairsPerClip",3);
        var report=json.createObjectNode().put("passed",false).put("motionDecision","CONFIRMED_DEFECT");report.putArray("issues").add("IDLE_MOTION");
        assertThat(StyledIdleHold.eligible(work("IDLE",3,"PERSISTING",policy,previous),report,previous)).isTrue();
        assertThat(StyledIdleHold.eligible(work("IDLE",2,"PERSISTING",policy,previous),report,previous)).isFalse();
        assertThat(StyledIdleHold.eligible(work("WALK",3,"PERSISTING",policy,previous),report,previous)).isFalse();
        assertThat(StyledIdleHold.eligible(work("IDLE",3,"CHECKING",policy,previous),report,previous)).isFalse();
        assertThat(StyledIdleHold.eligible(work("IDLE",3,"PERSISTING",policy,previous),report,derived)).isFalse();
        report.put("motionDecision","UNCERTAIN");assertThat(StyledIdleHold.eligible(work("IDLE",3,"PERSISTING",policy,previous),report,previous)).isFalse();
        report.put("motionDecision","PASS").put("passed",true);assertThat(StyledIdleHold.eligible(work("IDLE",3,"PERSISTING",policy,previous),report,previous)).isFalse();
    }
}
