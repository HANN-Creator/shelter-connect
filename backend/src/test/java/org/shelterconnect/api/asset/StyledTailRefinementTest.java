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

class StyledTailRefinementTest {
    final JsonMapper json=JsonMapper.builder().build();
    static final Path FIXTURE=Path.of("scripts/fixtures/tail-refinement-v18");
    final AiProperties ai=new AiProperties(true,"fixture-key","gpt-5.6-luna",30);
    JsonNode raw(String west,String east){return StyledSeedTailEvidenceTest.observation(json,"OBSCURED",west,east,.95);}
    List<byte[]> seeds()throws Exception{var result=new ArrayList<byte[]>();for(String d:StyledSpriteCodec.DIRECTIONS)result.add(Files.readAllBytes(FIXTURE.resolve(d+".png")));return result;}
    ObjectNode original()throws Exception{return (ObjectNode)json.readTree(Files.readAllBytes(FIXTURE.resolve("deployed-review.json")));}
    ObjectNode general(ObjectNode old){var r=old.deepCopy();r.set("propertyReview",old.path("generalPropertyReview").deepCopy());
        r.put("rulesSha256",StyledSpriteCodec.qualityRulesSha());r.remove("seedRepairPlan");return r;}
    @Test void actualCoordinateFailureMustNotEraseConfidentTailCarriageDefects()throws Exception{
        var old=original();var seeds=seeds();assertThat(StyledSeedQualityAgent.binding(seeds)).isEqualTo(old.path("inputSha256").asText());
        assertThat(old.at("/seedRepairPlan/status").asText()).isEqualTo("UNCERTAIN_VERDICT");
        var evidence=StyledSeedTailEvidence.grounded(json,old.at("/tailEvidence/observation"),seeds);
        assertThat(evidence.path("uncertainDirections").toString()).isEqualTo("[\"east\"]");
        var result=general(old);StyledSeedTailEvidence.merge(json,result,evidence);
        assertThat(result.path("passed").asBoolean()).isFalse();assertThat(StyledSeedTailEvidence.boundPass(result)).isFalse();
        var east=result.at("/propertyReview/views/3");assertThat(east.path("confidence").asDouble()).isEqualTo(.94);
        assertThat(east.path("issues").toString()).isEqualTo("[\"TAIL_CARRIAGE\"]");
        assertThat(east.path("repairEvidenceSource").asText()).isEqualTo("GENERAL_PROPERTY_REVIEW");
        var client=mock(OpenAiResponsesClient.class);var plan=StyledSeedRepair.plan(client,json,seeds,result);
        assertThat(plan.path("status").asText()).isEqualTo("READY");assertThat(plan.path("directions").toString()).isEqualTo("[\"west\",\"east\"]");
        assertThat(plan.path("method").asText()).isEqualTo("VIEW");verifyNoInteractions(client);
        assertThat(result.path("generalPropertyReview")).isEqualTo(old.path("generalPropertyReview"));
    }
    @Test void coordinatesAloneCannotAuthorizeRedrawOrInventMissingTail()throws Exception{
        var result=general(original());for(var v:result.at("/propertyReview/views")){
            ((ObjectNode)v).putArray("issues");for(String f:StyledRecoveryReview.BASE.keySet())((ObjectNode)v).put(f,true);
        }
        result.put("repairDescription","");
        var evidence=StyledSeedTailEvidence.grounded(json,original().at("/tailEvidence/observation"),seeds());
        StyledSeedTailEvidence.merge(json,result,evidence);var east=result.at("/propertyReview/views/3");
        assertThat(east.path("confidence").asDouble()).isLessThan(.75);assertThat(east.path("issues").isEmpty()).isTrue();
        assertThat(east.path("tailPlausible").asBoolean()).isTrue();assertThat(result.path("repairDescription").asText()).isEmpty();
        assertThat(StyledSeedRepair.plan(mock(OpenAiResponsesClient.class),json,seeds(),result).path("status").asText()).isEqualTo("UNCERTAIN_VERDICT");
    }
    @Test void actualLunaRefinementFixesCoordinatesButLowConfidenceStillHoldsApproval()throws Exception{
        var saved=json.readTree(Files.readAllBytes(FIXTURE.resolve("live-refinement-review.json")));
        assertThat(saved.at("/observationHistory/0/pixelAudit/east/opaque").asBoolean()).isFalse();
        var evidence=StyledSeedTailEvidence.grounded(json,saved.path("observation"),seeds());
        assertThat(evidence.at("/pixelAudit/east/completeContour").asBoolean()).isTrue();
        assertThat(evidence.path("passed").asBoolean()).isFalse();assertThat(evidence.path("uncertainDirections").toString()).isEqualTo("[\"east\"]");
        var report=general(original());StyledSeedTailEvidence.merge(json,report,evidence);
        assertThat(StyledSeedRepair.plan(mock(OpenAiResponsesClient.class),json,seeds(),report).path("status").asText()).isEqualTo("READY");
        assertThat(report.path("passed").asBoolean()).isFalse();assertThat(StyledSeedTailEvidence.boundPass(report)).isFalse();
    }
    @Test void uncertainOtherViewIsDeferredWithoutBlockingConfirmedRepairOrTouchingItsBytes()throws Exception{
        var old=original();var report=general(old);var east=(ObjectNode)report.at("/propertyReview/views/3");
        east.putArray("issues");for(String f:StyledRecoveryReview.BASE.keySet())east.put(f,true);
        StyledSeedTailEvidence.merge(json,report,StyledSeedTailEvidence.grounded(json,old.at("/tailEvidence/observation"),seeds()));
        var plan=StyledSeedRepair.plan(mock(OpenAiResponsesClient.class),json,seeds(),report);
        assertThat(plan.path("status").asText()).isEqualTo("READY");assertThat(plan.path("directions").toString()).isEqualTo("[\"west\"]");
        assertThat(plan.path("deferredDirections").toString()).isEqualTo("[\"east\"]");report.set("seedRepairPlan",plan);
        var before=seeds();var result=StyledSeedRepair.apply(before,report,json.valueToTree(Map.of("directions",Map.of("west",Base64.getEncoder().encodeToString(before.get(2))))));
        assertThat(result.seeds().get(3)).isEqualTo(before.get(3));assertThat(StyledSeedTailEvidence.boundPass(report)).isFalse();
        // A forged plan cannot redraw the deferred/unknown tail.
        ((ObjectNode)plan).set("directions",json.valueToTree(List.of("west","east")));
        assertThatThrownBy(()->StyledSeedRepair.directions(before,report)).hasMessage("SEED_REPAIR_SELECTION_INVALID");
        // Geometry has its own authority: an actual clipping direction must not be deferred with uncertain tail coordinates.
        report.set("edgeDirections",json.valueToTree(List.of("east")));
        var clipped=StyledSeedRepair.plan(mock(OpenAiResponsesClient.class),json,seeds(),report);
        assertThat(clipped.path("status").asText()).isEqualTo("READY");assertThat(clipped.path("directions").toString()).isEqualTo("[\"west\",\"east\"]");
    }
    @Test void opaqueGeometryCannotBeMisreportedAsDetachedOrCropped(){
        byte[] seed=StyledSeedTailEvidenceTest.tailSeed();var seeds=Collections.nCopies(4,seed);
        var evidence=StyledSeedTailEvidence.grounded(json,raw("DETACHED_OR_CROPPED","COMPLETE_CONNECTED"),seeds);
        assertThat(evidence.path("uncertainDirections").toString()).isEqualTo("[\"west\"]");
        assertThat(evidence.at("/geometry/west/edgeContact").asBoolean()).isFalse();
        assertThat(evidence.at("/geometry/west/opaqueComponents").asInt()).isEqualTo(1);
        assertThat(StyledSeedTailEvidence.geometry(json,seeds,true).at("/west/alphaRows").size()).isEqualTo(32);
    }
    @Test void reobserveOnceOnSameBytesAndRetainBothReplies(){
        var client=mock(OpenAiResponsesClient.class);var first=raw("UNCERTAIN","COMPLETE_CONNECTED");var second=raw("COMPLETE_CONNECTED","COMPLETE_CONNECTED");
        when(client.structuredImages(anyString(),anyString(),anyMap(),anyMap())).thenReturn(first,second.at("/views/0"));
        byte[] seed=StyledSeedTailEvidenceTest.tailSeed();var seeds=Collections.nCopies(4,seed);
        var result=StyledSeedTailEvidence.review(client,ai,json,seed,seeds);
        assertThat(result.path("passed").asBoolean()).isTrue();assertThat(result.path("observationHistory").size()).isEqualTo(2);
        assertThat(result.at("/observationHistory/0/observation")).isEqualTo(first);
        assertThat(result.at("/observationHistory/1/observation")).isEqualTo(second);
        verify(client).structuredImages(contains("Re-observe once per uncertain direction"),contains("alphaRows"),anyMap(),anyMap());
        verify(client,times(2)).structuredImages(anyString(),anyString(),anyMap(),anyMap());
    }
    @Test void unresolvedAndFailedReobservationKeepHoldAndHaveBoundedCost(){
        for(String mode:List.of("uncertain","timeout","malformed")){
            var client=mock(OpenAiResponsesClient.class);var response=when(client.structuredImages(anyString(),anyString(),anyMap(),anyMap())).thenReturn(raw("UNCERTAIN","COMPLETE_CONNECTED"));
            if(mode.equals("timeout"))response.thenThrow(new AiFailure("AI_TIMEOUT"));
            else response.thenReturn(mode.equals("malformed")?json.createObjectNode():raw("UNCERTAIN","COMPLETE_CONNECTED").at("/views/0"));
            byte[] seed=StyledSeedTailEvidenceTest.tailSeed();var result=StyledSeedTailEvidence.review(client,ai,json,seed,Collections.nCopies(4,seed));
            assertThat(result.path("passed").asBoolean()).as(mode).isFalse();
            assertThat(result.path("observationHistory").size()).isEqualTo(mode.equals("uncertain")?2:1);
            if(!mode.equals("uncertain"))assertThat(result.path("refinementFailureCode").asText()).isNotBlank();
            verify(client,times(2)).structuredImages(anyString(),anyString(),anyMap(),anyMap());
        }
    }
    @Test void clearTailDefectNeedsRepairNotAnotherCoordinateCall(){
        var client=mock(OpenAiResponsesClient.class);when(client.structuredImages(anyString(),anyString(),anyMap(),anyMap())).thenReturn(raw("SHORT_STUB","NOT_DISCERNIBLE"));
        byte[] seed=StyledSeedTailEvidenceTest.tailSeed();var result=StyledSeedTailEvidence.review(client,ai,json,seed,Collections.nCopies(4,seed));
        assertThat(result.path("passed").asBoolean()).isFalse();assertThat(result.path("observationHistory").size()).isEqualTo(1);
        verify(client).structuredImages(anyString(),contains("edgeContact"),anyMap(),anyMap());
    }
}
